package com.ryanmorrisroe.lyriamp.data.lms

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URI
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class LmsServerConnection(
    val host: String,
    val port: Int = 9000,
    val useHttps: Boolean = false,
    val username: String? = null,
    val password: String? = null
) {
    fun jsonRpcUrl(): String {
        val normalizedHost = host.trim()
        require(normalizedHost.isNotEmpty()) { "Enter a server address." }
        require(
            "://" !in normalizedHost && '/' !in normalizedHost &&
                '?' !in normalizedHost && '#' !in normalizedHost
        ) { "Enter a hostname or IP address without a scheme or path." }
        require(port in 1..65535) { "Port must be between 1 and 65535." }

        val uriHost = normalizedHost
            .removePrefix("[")
            .removeSuffix("]")
        return URI(
            if (useHttps) "https" else "http",
            null,
            uriHost,
            port,
            "/jsonrpc.js",
            null,
            null
        ).toASCIIString()
    }
}

internal fun LmsServerConnection.basicAuthorizationHeader(): String? {
    val username = username?.takeIf(String::isNotBlank) ?: return null
    val credentials = "$username:${password.orEmpty()}"
    val encodedCredentials = Base64.getEncoder()
        .encodeToString(credentials.toByteArray(Charsets.UTF_8))
    return "Basic $encodedCredentials"
}

data class LmsServerInfo(
    val version: String,
    val playerCount: Int
)

fun interface LmsConnectionGateway {
    suspend fun testConnection(connection: LmsServerConnection): LmsServerInfo
}

class LmsHttpException(val statusCode: Int) : Exception("LMS returned HTTP $statusCode.")

class LmsProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

class LmsJsonRpcClient(
    private val httpClient: HttpClient
) : LmsConnectionGateway, LmsPlayerDirectory, LmsPlayerRemoteGateway, LmsLibraryGateway {
    override suspend fun testConnection(connection: LmsServerConnection): LmsServerInfo {
        val result = request(connection, "", serverStatusCommand())
        val version = result["version"]?.jsonPrimitive?.contentOrNull
            ?: result["_version"]?.jsonPrimitive?.contentOrNull
            ?: throw LmsProtocolException("LMS response did not include a server version.")
        val playerCount = result["count"]?.jsonPrimitive?.intOrNull
            ?: (result["players_loop"] as? JsonArray)?.size
            ?: 0

        return LmsServerInfo(version, playerCount)
    }

    override suspend fun getPlayers(connection: LmsServerConnection): List<LmsPlayer> {
        val result = request(connection, "", playersCommand())
        val players = result["players_loop"] as? JsonArray
            ?: throw LmsProtocolException("LMS response did not include the player list.")
        return players.map { item ->
            val player = item as? JsonObject
                ?: throw LmsProtocolException("LMS returned an invalid player entry.")
            val playerId = player["playerid"]?.jsonPrimitive?.contentOrNull
                ?: throw LmsProtocolException("LMS player entry did not include an ID.")
            val modelName = player["model"]?.jsonPrimitive?.contentOrNull
                ?: "LMS player"
            LmsPlayer(
                id = playerId,
                name = player["name"]?.jsonPrimitive?.contentOrNull ?: modelName,
                modelName = modelName,
                connected = player["connected"].asFlag(),
                power = player["power"].asFlag()
            )
        }
    }

    override suspend fun getPage(
        connection: LmsServerConnection,
        query: LmsLibraryQuery,
        offset: Int,
        limit: Int,
        filter: LmsLibraryFilter,
        search: String?
    ): LmsLibraryPage {
        validateLibraryPage(offset, limit)
        val result = request(connection, "", libraryPageCommand(query, offset, limit, filter, search))
        val totalCount = result["count"].asInt()
            ?: throw LmsProtocolException("LMS response did not include a library result count.")
        return LmsLibraryPage(
            items = result.libraryItems(
                query.loopName,
                query.idField,
                query.titleField,
                query.itemType,
                totalCount,
                query == LmsLibraryQuery.PLAYLISTS
            ),
            offset = offset,
            totalCount = totalCount
        )
    }

    override suspend fun search(
        connection: LmsServerConnection,
        term: String,
        offset: Int,
        limit: Int
    ): LmsLibrarySearchResults {
        validateLibraryPage(offset, limit)
        require(term.isNotBlank()) { "Enter a search term." }
        val result = request(connection, "", librarySearchCommand(term, offset, limit))
        return LmsLibrarySearchResults(
            artists = result.searchPage(
                loopName = "contributors_loop",
                countField = "contributors_count",
                idField = "contributor_id",
                titleField = "contributor",
                itemType = LmsLibraryItemType.ARTIST,
                offset = offset
            ),
            albums = result.searchPage(
                loopName = "albums_loop",
                countField = "albums_count",
                idField = "album_id",
                titleField = "album",
                itemType = LmsLibraryItemType.ALBUM,
                offset = offset,
                artworkIdFields = arrayOf("artwork")
            ),
            tracks = result.searchPage(
                loopName = "tracks_loop",
                countField = "tracks_count",
                idField = "track_id",
                titleField = "track",
                itemType = LmsLibraryItemType.TRACK,
                offset = offset,
                artworkIdFields = arrayOf("coverid", "artwork_track_id")
            )
        )
    }

    override suspend fun playNow(
        connection: LmsServerConnection,
        playerId: String,
        item: LmsLibraryItem
    ) {
        require(!item.isContainer) { "A playlist folder cannot be played." }
        val filterName = when (item.type) {
            LmsLibraryItemType.TRACK -> "track_id"
            LmsLibraryItemType.ALBUM -> "album_id"
            LmsLibraryItemType.PLAYLIST -> "playlist_id"
            LmsLibraryItemType.ARTIST,
            LmsLibraryItemType.GENRE -> throw IllegalArgumentException("This library item cannot be played directly.")
        }
        request(connection, playerId, playlistControlCommand("load", filterName, item.id))
    }

    override suspend fun getPlayerStatus(
        connection: LmsServerConnection,
        playerId: String
    ): LmsPlayerStatus {
        val result = request(connection, playerId, playerStatusCommand())
        val currentTrack = (result["playlist_loop"] as? JsonArray)
            ?.firstOrNull() as? JsonObject
        val durationSeconds = currentTrack?.get("duration").asDouble()
            ?: result["duration"].asDouble()
        return LmsPlayerStatus(
            mode = when (result["mode"]?.jsonPrimitive?.contentOrNull) {
                "play" -> LmsPlaybackMode.PLAYING
                "pause" -> LmsPlaybackMode.PAUSED
                "stop" -> LmsPlaybackMode.STOPPED
                else -> LmsPlaybackMode.UNKNOWN
            },
            title = currentTrack?.get("title").asText()
                ?: result["current_title"].asText(),
            artist = currentTrack?.get("artist").asText(),
            album = currentTrack?.get("album").asText(),
            positionSeconds = result["time"].asDouble() ?: 0.0,
            durationSeconds = durationSeconds,
            canSeek = result["can_seek"].asBoolean() ?: (durationSeconds != null)
        )
    }

    override suspend fun getPlayerMixerState(
        connection: LmsServerConnection,
        playerId: String
    ): LmsPlayerMixerState {
        val volume = request(connection, playerId, mixerQueryCommand("volume"))
            .firstInt("_volume", "volume")
            ?.takeIf { it in 0..100 }
            ?: throw LmsProtocolException("LMS response did not include a valid player volume.")
        val isMuted = request(connection, playerId, mixerQueryCommand("muting"))
            .firstBoolean("_muting", "muting")
            ?: throw LmsProtocolException("LMS response did not include the player mute state.")
        return LmsPlayerMixerState(volume, isMuted)
    }

    override suspend fun getPlayerPlaybackOptions(
        connection: LmsServerConnection,
        playerId: String
    ): LmsPlayerPlaybackOptions {
        val shuffleValue = request(connection, playerId, playlistQueryCommand("shuffle"))
            .firstInt("_shuffle", "shuffle")
            ?: throw LmsProtocolException("LMS response did not include a valid shuffle mode.")
        val repeatValue = request(connection, playerId, playlistQueryCommand("repeat"))
            .firstInt("_repeat", "repeat")
            ?: throw LmsProtocolException("LMS response did not include a valid repeat mode.")
        return LmsPlayerPlaybackOptions(
            shuffleMode = LmsShuffleMode.fromCliValue(shuffleValue)
                ?: throw LmsProtocolException("LMS returned an unsupported shuffle mode."),
            repeatMode = LmsRepeatMode.fromCliValue(repeatValue)
                ?: throw LmsProtocolException("LMS returned an unsupported repeat mode.")
        )
    }

    override suspend fun sendCommand(
        connection: LmsServerConnection,
        playerId: String,
        command: LmsPlayerCommand
    ) {
        request(connection, playerId, command.toJsonArray())
    }

    private suspend fun request(
        connection: LmsServerConnection,
        playerId: String,
        command: JsonArray
    ): JsonObject {
        val response = httpClient.post(connection.jsonRpcUrl()) {
            contentType(ContentType.Application.Json)
            setBody(jsonRpcRequest(playerId, command).toString())

            connection.basicAuthorizationHeader()?.let {
                header(HttpHeaders.Authorization, it)
            }
        }

        if (!response.status.isSuccess()) {
            throw LmsHttpException(response.status.value)
        }

        val responseJson = try {
            Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: throw LmsProtocolException("LMS returned an unexpected response.")
        } catch (exception: SerializationException) {
            throw LmsProtocolException("LMS returned invalid JSON.", exception)
        }

        responseJson["error"]
            ?.takeUnless { it == JsonNull }
            ?.let { throw LmsProtocolException("LMS reported an error: ${it.asMessage()}") }

        return responseJson["result"] as? JsonObject
            ?: throw LmsProtocolException("LMS response did not include a result.")
    }

    private fun jsonRpcRequest(playerId: String, command: JsonArray) = buildJsonObject {
        put("id", 1)
        put("method", "slim.request")
        put(
            "params",
            buildJsonArray {
                add(JsonPrimitive(playerId))
                add(command)
            }
        )
    }

    private fun serverStatusCommand() = buildJsonArray {
        add(JsonPrimitive("serverstatus"))
        add(JsonPrimitive(0))
        add(JsonPrimitive(1))
    }

    private fun playersCommand() = buildJsonArray {
        add(JsonPrimitive("players"))
        add(JsonPrimitive(0))
        add(JsonPrimitive(100))
    }

    private fun playerStatusCommand() = buildJsonArray {
        add(JsonPrimitive("status"))
        add(JsonPrimitive("-"))
        add(JsonPrimitive(1))
        add(JsonPrimitive("tags:cgAB"))
    }

    private fun mixerQueryCommand(control: String) = buildJsonArray {
        add(JsonPrimitive("mixer"))
        add(JsonPrimitive(control))
        add(JsonPrimitive("?"))
    }

    private fun playlistQueryCommand(control: String) = buildJsonArray {
        add(JsonPrimitive("playlist"))
        add(JsonPrimitive(control))
        add(JsonPrimitive("?"))
    }

    private fun libraryPageCommand(
        query: LmsLibraryQuery,
        offset: Int,
        limit: Int,
        filter: LmsLibraryFilter,
        search: String?
    ) = buildJsonArray {
        query.command.forEach { add(JsonPrimitive(it)) }
        add(JsonPrimitive(offset))
        add(JsonPrimitive(limit))
        when (query) {
            LmsLibraryQuery.ALBUMS -> add(JsonPrimitive("tags:laJK"))
            LmsLibraryQuery.TRACKS,
            LmsLibraryQuery.PLAYLIST_TRACKS -> add(JsonPrimitive("tags:galdeKc"))
            LmsLibraryQuery.PLAYLISTS -> add(JsonPrimitive("tags:sux"))
            LmsLibraryQuery.ARTISTS,
            LmsLibraryQuery.GENRES -> Unit
        }
        filter.toQueryParameters().forEach { add(JsonPrimitive(it)) }
        search?.takeIf(String::isNotBlank)?.let { add(JsonPrimitive("search:$it")) }
    }

    private fun librarySearchCommand(term: String, offset: Int, limit: Int) = buildJsonArray {
        add(JsonPrimitive("search"))
        add(JsonPrimitive(offset))
        add(JsonPrimitive(limit))
        add(JsonPrimitive("term:$term"))
        add(JsonPrimitive("extended:1"))
    }

    private fun playlistControlCommand(action: String, filterName: String, id: String) = buildJsonArray {
        add(JsonPrimitive("playlistcontrol"))
        add(JsonPrimitive("cmd:$action"))
        add(JsonPrimitive("$filterName:$id"))
    }

    private fun validateLibraryPage(offset: Int, limit: Int) {
        require(offset >= 0) { "Library result offset cannot be negative." }
        require(limit in 1..MAX_LIBRARY_PAGE_SIZE) {
            "Library page size must be between 1 and $MAX_LIBRARY_PAGE_SIZE."
        }
    }

    private fun LmsPlayerCommand.toJsonArray() = buildJsonArray {
        toCliTokens().forEach { add(JsonPrimitive(it)) }
    }

    private fun JsonElement?.asFlag(): Boolean =
        (this as? JsonPrimitive)?.contentOrNull in setOf("1", "true", "yes")

    private fun JsonElement?.asBoolean(): Boolean? = when ((this as? JsonPrimitive)?.contentOrNull) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }

    private fun JsonElement?.asDouble(): Double? =
        (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun JsonElement?.asInt(): Int? =
        (this as? JsonPrimitive)?.intOrNull

    private fun JsonObject.firstInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> this[key].asInt() }

    private fun JsonObject.firstBoolean(vararg keys: String): Boolean? =
        keys.firstNotNullOfOrNull { key -> this[key].asBoolean() }

    private fun JsonObject.libraryItems(
        loopName: String,
        idField: String,
        titleField: String,
        itemType: LmsLibraryItemType,
        totalCount: Int,
        detectContainers: Boolean = false
    ): List<LmsLibraryItem> = libraryLoop(loopName, totalCount).map { entry ->
        val id = entry[idField].asText()
            ?: throw LmsProtocolException("LMS library item did not include an ID.")
        val title = entry[titleField].asText()
            ?: entry["title"].asText()
            ?: throw LmsProtocolException("LMS library item did not include a title.")
        entry.toLibraryItem(
            id = id,
            type = itemType,
            title = title,
            detectContainer = detectContainers
        )
    }

    private fun JsonObject.searchPage(
        loopName: String,
        countField: String,
        idField: String,
        titleField: String,
        itemType: LmsLibraryItemType,
        offset: Int,
        artworkIdFields: Array<String> = emptyArray()
    ): LmsLibraryPage {
        val totalCount = this[countField].asInt() ?: 0
        val items = libraryLoop(loopName, totalCount).map { entry ->
            val id = entry[idField].asText()
                ?: throw LmsProtocolException("LMS search result did not include an ID.")
            val title = entry[titleField].asText()
                ?: throw LmsProtocolException("LMS search result did not include a title.")
            entry.toLibraryItem(id, itemType, title, artworkIdFields = artworkIdFields)
        }
        return LmsLibraryPage(items, offset, totalCount)
    }

    private fun JsonObject.libraryLoop(loopName: String, totalCount: Int): List<JsonObject> {
        val entries = this[loopName] as? JsonArray
            ?: if (totalCount == 0) return emptyList()
            else throw LmsProtocolException("LMS response did not include $loopName.")
        return entries.map { item ->
            item as? JsonObject
                ?: throw LmsProtocolException("LMS returned an invalid library entry.")
        }
    }

    private fun JsonObject.toLibraryItem(
        id: String,
        type: LmsLibraryItemType,
        title: String,
        detectContainer: Boolean = false,
        artworkIdFields: Array<String> = arrayOf("artwork_track_id", "coverid", "artwork")
    ) = LmsLibraryItem(
        id = id,
        type = type,
        title = title,
        artist = this["artist"].asText(),
        album = this["album"].asText().takeUnless { type == LmsLibraryItemType.ALBUM },
        durationSeconds = this["duration"].asDouble() ?: this["secs"].asDouble(),
        artworkId = artworkIdFields.firstNotNullOfOrNull { key ->
            this[key].asText()?.takeUnless { it.startsWith("http://") || it.startsWith("https://") }
        },
        artworkUrl = (listOf("artwork_url") + artworkIdFields.asList()).firstNotNullOfOrNull { key ->
            this[key].asText()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        },
        isContainer = detectContainer && (
            this["type"].asText() == "folder" ||
                id.startsWith("file:", ignoreCase = true) || '/' in id || '\\' in id
            )
    )

    private fun LmsLibraryFilter.toQueryParameters(): List<String> = buildList {
        artistId?.takeIf(String::isNotBlank)?.let { add("artist_id:$it") }
        albumId?.takeIf(String::isNotBlank)?.let { add("album_id:$it") }
        genreId?.takeIf(String::isNotBlank)?.let { add("genre_id:$it") }
        playlistId?.takeIf(String::isNotBlank)?.let { add("playlist_id:$it") }
        folderId?.takeIf(String::isNotBlank)?.let { add("folder_id:$it") }
    }

    private fun JsonElement?.asText(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val MAX_LIBRARY_PAGE_SIZE = 100
    }

    private fun JsonElement.asMessage(): String =
        (this as? JsonPrimitive)?.contentOrNull ?: toString()
}