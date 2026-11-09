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
) : LmsConnectionGateway, LmsPlayerDirectory, LmsPlayerRemoteGateway {
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

            val username = connection.username
            if (!username.isNullOrBlank()) {
                val credentials = "$username:${connection.password.orEmpty()}"
                val encodedCredentials = Base64.getEncoder()
                    .encodeToString(credentials.toByteArray(Charsets.UTF_8))
                header(HttpHeaders.Authorization, "Basic $encodedCredentials")
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

    private fun JsonElement?.asText(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.asMessage(): String =
        (this as? JsonPrimitive)?.contentOrNull ?: toString()
}