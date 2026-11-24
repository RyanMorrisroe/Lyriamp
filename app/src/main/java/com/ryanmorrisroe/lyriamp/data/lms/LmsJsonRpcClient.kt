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
        search: String?,
        sort: LmsLibrarySort?
    ): LmsLibraryPage {
        validateLibraryPage(offset, limit)
        val result = request(connection, "", libraryPageCommand(query, offset, limit, filter, search, sort))
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
            ).map { item ->
                if (query == LmsLibraryQuery.TRACKS && filter.albumId != null) {
                    item.copy(albumId = item.albumId ?: filter.albumId)
                } else {
                    item
                }
            },
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
        val artists = result.searchPage(
            loopName = "contributors_loop",
            countField = "contributors_count",
            idField = "contributor_id",
            titleField = "contributor",
            itemType = LmsLibraryItemType.ARTIST,
            offset = offset
        )
        val albums = result.searchPage(
            loopName = "albums_loop",
            countField = "albums_count",
            idField = "album_id",
            titleField = "album",
            itemType = LmsLibraryItemType.ALBUM,
            offset = offset,
            artworkIdFields = arrayOf("artwork", "artwork_track_id")
        )
        val albumIds = albums.items.map { it.id }.distinct()
        val albumMetadataById = if (albumIds.isEmpty()) {
            emptyMap()
        } else {
            getPage(
                connection = connection,
                query = LmsLibraryQuery.ALBUMS,
                offset = 0,
                limit = albumIds.size,
                filter = LmsLibraryFilter(albumId = albumIds.joinToString(",")),
                search = null
            ).items.associateBy { it.id }
        }
        val enrichedAlbums = albums.copy(
            items = albums.items.map { item ->
                val metadata = albumMetadataById[item.id] ?: return@map item
                item.copy(
                    artist = metadata.artist ?: item.artist,
                    artworkId = item.artworkId ?: metadata.artworkId,
                    artworkUrl = item.artworkUrl ?: metadata.artworkUrl,
                    year = metadata.year ?: item.year
                )
            }
        )
        return LmsLibrarySearchResults(
            artists = artists,
            albums = enrichedAlbums,
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

    override suspend fun getArtistArtworkUrl(
        connection: LmsServerConnection,
        artistId: String
    ): String? {
        if (artistId.toLongOrNull() == null) {
            throw LmsProtocolException("LMS artist entry did not include a valid numeric ID.")
        }
        val result = request(connection, "-", artistArtworkCommand(artistId))
        val artistPhotoUrl = (result["item_loop"] as? JsonArray)
            ?.firstNotNullOfOrNull { photo ->
                (photo as? JsonObject)?.get("url").asText()?.takeIf(String::isNotBlank)
            }
        artistPhotoUrl?.let { return it }

        val firstAlbum = getPage(
            connection,
            LmsLibraryQuery.ALBUMS,
            offset = 0,
            limit = 1,
            filter = LmsLibraryFilter(artistId = artistId)
        ).items.firstOrNull() ?: return null

        return firstAlbum.artworkUrl
            ?: firstAlbum.artworkId?.let { "/music/$it/cover.jpg" }
    }

    override suspend fun playNow(
        connection: LmsServerConnection,
        playerId: String,
        item: LmsLibraryItem
    ) {
        require(!item.isContainer) { "A playlist folder cannot be played." }
        if (item.type == LmsLibraryItemType.TRACK && item.albumId != null) {
            val albumTracks = getSortedAlbumTracks(connection, item.albumId)
            request(
                connection,
                playerId,
                playlistControlCommand("load", item.playlistControlFilterName(), item.id)
            )
            val selectedIndex = albumTracks.indexOfFirst { it.id == item.id }
            if (selectedIndex >= 0) {
                albumTracks.drop(selectedIndex + 1).forEach { laterTrack ->
                    request(
                        connection,
                        playerId,
                        playlistControlCommand("add", "track_id", laterTrack.id)
                    )
                }
            }
        } else {
            request(
                connection,
                playerId,
                playlistControlCommand("load", item.playlistControlFilterName(), item.id, item.playlistControlSort())
            )
        }
    }

    private suspend fun getSortedAlbumTracks(
        connection: LmsServerConnection,
        albumId: String
    ): List<LmsLibraryItem> {
        val tracks = mutableListOf<LmsLibraryItem>()
        var offset = 0
        var totalCount: Int? = null

        while (totalCount == null || offset < totalCount) {
            val page = getPage(
                connection = connection,
                query = LmsLibraryQuery.TRACKS,
                offset = offset,
                limit = 100,
                filter = LmsLibraryFilter(albumId = albumId)
            )
            totalCount = page.totalCount
            if (page.items.isEmpty()) break
            tracks.addAll(page.items)
            offset += page.items.size
        }

        return tracks.sortedForAlbumTracks()
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
        val artwork = currentTrack?.artworkReference(arrayOf("coverid", "artwork_track_id", "artwork"))
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
            canSeek = result["can_seek"].asBoolean() ?: (durationSeconds != null),
            artworkId = artwork?.id,
            artworkUrl = artwork?.url,
            playlistIndex = result["playlist_cur_index"].asInt()
                ?: currentTrack?.get("playlist index").asInt(),
            trackId = currentTrack?.get("id").asText()
        )
    }

    override suspend fun getSyncGroupMembers(
        connection: LmsServerConnection,
        playerId: String
    ): List<String> {
        require(playerId.isNotBlank()) { "Player ID cannot be blank." }
        val result = request(connection, playerId, syncGroupQueryCommand())
        val syncResponse = result["_sync"] ?: result["sync"]
            ?: throw LmsProtocolException("LMS response did not include this player's sync group.")
        val rawMembers = when (syncResponse) {
            is JsonPrimitive -> syncResponse.contentOrNull
                ?: throw LmsProtocolException("LMS returned an invalid sync group.")
            is JsonArray -> syncResponse.joinToString(",") { member ->
                (member as? JsonPrimitive)?.contentOrNull
                    ?: throw LmsProtocolException("LMS returned an invalid sync-group member.")
            }
            else -> throw LmsProtocolException("LMS returned an invalid sync group.")
        }
        if (rawMembers.isBlank()) {
            throw LmsProtocolException("LMS returned an empty sync-group response.")
        }
        val members = rawMembers.takeUnless { it == "-" }?.split(',').orEmpty()
        return (members + playerId)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
    }

    override suspend fun syncPlayers(
        connection: LmsServerConnection,
        leaderPlayerId: String,
        memberPlayerId: String
    ) {
        require(leaderPlayerId.isNotBlank()) { "Leader player ID cannot be blank." }
        require(memberPlayerId.isNotBlank()) { "Member player ID cannot be blank." }
        require(leaderPlayerId != memberPlayerId) { "A player cannot be synced to itself." }
        request(connection, leaderPlayerId, syncPlayerCommand(memberPlayerId))
    }

    override suspend fun unsyncPlayer(connection: LmsServerConnection, playerId: String) {
        require(playerId.isNotBlank()) { "Player ID cannot be blank." }
        request(connection, playerId, unsyncPlayerCommand())
    }

    override suspend fun getPlayerQueue(
        connection: LmsServerConnection,
        playerId: String,
        offset: Int,
        limit: Int
    ): LmsPlayerQueuePage {
        validateQueuePage(offset, limit)
        val result = request(connection, playerId, playerQueueCommand(offset, limit))
        val totalCount = result["playlist_tracks"].asInt()
            ?: throw LmsProtocolException("LMS response did not include the queue size.")
        if (totalCount < 0) {
            throw LmsProtocolException("LMS returned an invalid queue size.")
        }
        val currentIndex = result["playlist_cur_index"].asInt()
            ?.takeIf { it in 0 until totalCount }
        val playlist = result["playlist_loop"] as? JsonArray
        val entries = when {
            playlist != null -> playlist
            totalCount == 0 || offset >= totalCount -> JsonArray(emptyList())
            else -> throw LmsProtocolException("LMS response did not include the queue items.")
        }
        if (entries.isEmpty() && offset < totalCount) {
            throw LmsProtocolException("LMS returned no queue items for a non-empty queue page.")
        }
        val items = entries.map { item ->
            val entry = item as? JsonObject
                ?: throw LmsProtocolException("LMS returned an invalid queue entry.")
            val index = entry["playlist index"].asInt()
                ?: throw LmsProtocolException("LMS queue entry did not include its playlist index.")
            val title = entry["title"].asText()
                ?: throw LmsProtocolException("LMS queue entry did not include a title.")
            val artwork = entry.artworkReference(arrayOf("coverid", "artwork_track_id", "artwork"))
            LmsQueueItem(
                index = index,
                trackId = entry["id"].asText(),
                title = title,
                artist = entry["artist"].asText(),
                album = entry["album"].asText(),
                durationSeconds = entry["duration"].asDouble(),
                artworkId = artwork?.id,
                artworkUrl = artwork?.url
            )
        }
        return LmsPlayerQueuePage(items, offset, totalCount, currentIndex)
    }

    override suspend fun addQueueItem(
        connection: LmsServerConnection,
        playerId: String,
        item: LmsLibraryItem,
        position: LmsQueueAddPosition
    ) {
        require(!item.isContainer) { "A playlist folder cannot be added to the queue." }
        val action = when (position) {
            LmsQueueAddPosition.END -> "add"
            LmsQueueAddPosition.NEXT -> "insert"
        }
        request(
            connection,
            playerId,
            playlistControlCommand(action, item.playlistControlFilterName(), item.id, item.playlistControlSort())
        )
    }

    override suspend fun removeQueueItem(
        connection: LmsServerConnection,
        playerId: String,
        index: Int
    ) {
        require(index >= 0) { "Queue index cannot be negative." }
        request(connection, playerId, playlistItemCommand("delete", index))
    }

    override suspend fun moveQueueItem(
        connection: LmsServerConnection,
        playerId: String,
        fromIndex: Int,
        toIndex: Int
    ) {
        require(fromIndex >= 0) { "Queue index cannot be negative." }
        require(toIndex >= 0) { "Queue index cannot be negative." }
        request(
            connection,
            playerId,
            buildJsonArray {
                add(JsonPrimitive("playlist"))
                add(JsonPrimitive("move"))
                add(JsonPrimitive(fromIndex))
                add(JsonPrimitive(toIndex))
            }
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
        add(JsonPrimitive(PLAYER_TRACK_TAGS))
    }

    private fun playerQueueCommand(offset: Int, limit: Int) = buildJsonArray {
        add(JsonPrimitive("status"))
        add(JsonPrimitive(offset))
        add(JsonPrimitive(limit))
        add(JsonPrimitive(PLAYER_TRACK_TAGS))
    }

    private fun playlistItemCommand(command: String, index: Int) = buildJsonArray {
        add(JsonPrimitive("playlist"))
        add(JsonPrimitive(command))
        add(JsonPrimitive(index))
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

    private fun syncGroupQueryCommand() = buildJsonArray {
        add(JsonPrimitive("sync"))
        add(JsonPrimitive("?"))
    }

    private fun syncPlayerCommand(memberPlayerId: String) = buildJsonArray {
        add(JsonPrimitive("sync"))
        add(JsonPrimitive(memberPlayerId))
    }

    private fun unsyncPlayerCommand() = buildJsonArray {
        add(JsonPrimitive("sync"))
        add(JsonPrimitive("-"))
    }

    private fun libraryPageCommand(
        query: LmsLibraryQuery,
        offset: Int,
        limit: Int,
        filter: LmsLibraryFilter,
        search: String?,
        sort: LmsLibrarySort? = null
    ) = buildJsonArray {
        query.command.forEach { add(JsonPrimitive(it)) }
        add(JsonPrimitive(offset))
        add(JsonPrimitive(limit))
        when (query) {
            LmsLibraryQuery.ALBUMS -> add(JsonPrimitive("tags:aljKy"))
            LmsLibraryQuery.TRACKS,
            LmsLibraryQuery.PLAYLIST_TRACKS -> add(JsonPrimitive("tags:galdeitKJc"))
            LmsLibraryQuery.PLAYLISTS -> add(JsonPrimitive("tags:suxKJc"))
            LmsLibraryQuery.ARTISTS -> {
                add(JsonPrimitive("tags:4"))
                add(JsonPrimitive("role_id:ALBUMARTIST"))
            }
            LmsLibraryQuery.GENRES -> Unit
        }
        if (query == LmsLibraryQuery.TRACKS && filter.albumId != null) {
            add(JsonPrimitive("sort:albumtrack"))
        } else if (sort != null) {
            add(JsonPrimitive("sort:${sort.lmsKey}"))
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

    private fun artistArtworkCommand(artistId: String) = buildJsonArray {
        add(JsonPrimitive("musicartistinfo"))
        add(JsonPrimitive("artistphotos"))
        add(JsonPrimitive("artist_id:$artistId"))
    }

    private fun playlistControlCommand(
        action: String,
        filterName: String,
        id: String,
        sort: String? = null
    ) = buildJsonArray {
        add(JsonPrimitive("playlistcontrol"))
        add(JsonPrimitive("cmd:$action"))
        add(JsonPrimitive("$filterName:$id"))
        sort?.let { add(JsonPrimitive("sort:$it")) }
    }

    private fun validateQueuePage(offset: Int, limit: Int) {
        require(offset >= 0) { "Queue result offset cannot be negative." }
        require(limit in 1..MAX_QUEUE_PAGE_SIZE) {
            "Queue page size must be between 1 and $MAX_QUEUE_PAGE_SIZE."
        }
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

    private fun JsonObject.firstTrackNumber(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            this[key].asText()
                ?.substringBefore('/')
                ?.trim()
                ?.toIntOrNull()
        }

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
    ): LmsLibraryItem {
        val artwork = if (type == LmsLibraryItemType.ARTIST) {
            val portraitId = this["portraitid"].asText()?.takeIf(String::isLmsArtworkId)
            portraitId?.let {
                LmsArtworkReference(null, "/contributor/$it/image")
            } ?: artworkReference(artworkIdFields)
        } else {
            artworkReference(artworkIdFields)
        }
        return LmsLibraryItem(
            id = id,
            type = type,
            title = title,
            artist = this["artist"].asText(),
            album = this["album"].asText().takeUnless { type == LmsLibraryItemType.ALBUM },
            durationSeconds = this["duration"].asDouble() ?: this["secs"].asDouble(),
            artworkId = artwork?.id,
            artworkUrl = artwork?.url,
            isContainer = detectContainer && (
                this["type"].asText() == "folder" ||
                    id.startsWith("file:", ignoreCase = true) || '/' in id || '\\' in id
                ),
            year = this["year"].asText()?.takeIf(String::isNotBlank),
            albumId = this["album_id"].asText()
                ?: this["albumid"].asText()
                ?: this["albumId"].asText(),
            discNumber = firstInt("disc", "discnumber", "disc_number"),
            trackNumber = firstTrackNumber("tracknum", "tracknumber", "track_number", "track")
        )
    }

    private fun JsonObject.artworkReference(artworkIdFields: Array<String>): LmsArtworkReference? {
        val values = artworkIdFields.mapNotNull { key ->
            this[key].asText()?.takeIf(String::isNotBlank)
        }
        val explicitUrl = this["artwork_url"].asText()?.takeIf(String::isNotBlank)
        val url = explicitUrl ?: values.firstOrNull { value ->
            value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) ||
                '/' in value || '?' in value
        }
        val id = values.firstOrNull { it.isLmsArtworkId() }
        return if (id == null && url == null) null else LmsArtworkReference(id, url)
    }

    private fun LmsLibraryFilter.toQueryParameters(): List<String> = buildList {
        artistId?.takeIf(String::isNotBlank)?.let { add("artist_id:$it") }
        albumId?.takeIf(String::isNotBlank)?.let { add("album_id:$it") }
        genreId?.takeIf(String::isNotBlank)?.let { add("genre_id:$it") }
        playlistId?.takeIf(String::isNotBlank)?.let { add("playlist_id:$it") }
        folderId?.takeIf(String::isNotBlank)?.let { add("folder_id:$it") }
    }

    private fun LmsLibraryItem.playlistControlFilterName(): String = when (type) {
        LmsLibraryItemType.TRACK -> "track_id"
        LmsLibraryItemType.ALBUM -> "album_id"
        LmsLibraryItemType.PLAYLIST -> "playlist_id"
        LmsLibraryItemType.ARTIST -> "artist_id"
        LmsLibraryItemType.GENRE -> "genre_id"
    }

    private fun LmsLibraryItem.playlistControlSort(): String? = when (type) {
        LmsLibraryItemType.ARTIST, LmsLibraryItemType.GENRE -> "album"
        else -> null
    }

    private fun JsonElement?.asText(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private data class LmsArtworkReference(val id: String?, val url: String?)

    private companion object {
        const val MAX_LIBRARY_PAGE_SIZE = 100
        const val MAX_QUEUE_PAGE_SIZE = 100
        const val PLAYER_TRACK_TAGS = "tags:aclKN"
    }

    private fun JsonElement.asMessage(): String =
        (this as? JsonPrimitive)?.contentOrNull ?: toString()
}