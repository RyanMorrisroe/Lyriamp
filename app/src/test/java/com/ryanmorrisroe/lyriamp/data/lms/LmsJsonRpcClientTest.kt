package com.ryanmorrisroe.lyriamp.data.lms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LmsJsonRpcClientTest {
    @Test
    fun buildsHttpAndHttpsEndpoints() {
        assertEquals(
            "http://lms.local:9000/jsonrpc.js",
            LmsServerConnection(host = " lms.local ").jsonRpcUrl()
        )
        assertEquals(
            "https://[::1]:9001/jsonrpc.js",
            LmsServerConnection(host = "::1", port = 9001, useHttps = true).jsonRpcUrl()
        )
    }

    @Test
    fun rejectsInvalidConnectionDetails() {
        assertThrows(IllegalArgumentException::class.java) {
            LmsServerConnection(host = "").jsonRpcUrl()
        }
        assertThrows(IllegalArgumentException::class.java) {
            LmsServerConnection(host = "lms.local/path").jsonRpcUrl()
        }
        assertThrows(IllegalArgumentException::class.java) {
            LmsServerConnection(host = "lms.local", port = 70000).jsonRpcUrl()
        }
    }

    @Test
    fun sendsServerStatusRequestAndMapsResponse() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson(
                """
                {"id":1,"result":{"version":"9.2.0","count":2,"players_loop":[{},{}]}}
                """.trimIndent()
            )
        }) {
        }

        try {
            val serverInfo = LmsJsonRpcClient(client).testConnection(
                LmsServerConnection("lms.local", username = "user", password = "secret")
            )

            assertEquals("9.2.0", serverInfo.version)
            assertEquals(2, serverInfo.playerCount)
            assertEquals("http://lms.local:9000/jsonrpc.js", capturedRequest?.url.toString())
            assertEquals(
                "Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray()),
                capturedRequest?.headers?.get(HttpHeaders.Authorization)
            )
            val body = (capturedRequest?.body as TextContent).text
            val requestJson = Json.parseToJsonElement(body).jsonObject
            assertEquals("slim.request", requestJson["method"]?.jsonPrimitive?.content)
            assertEquals(
                "serverstatus",
                requestJson["params"]?.jsonArray?.get(1)?.jsonArray?.get(0)?.jsonPrimitive?.content
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun listsPlayersUsingLmsQuery() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson(
                """
                {"id":1,"result":{"players_loop":[
                  {"playerid":"aa:bb:cc:dd:ee:ff","name":"Living Room","model":"Squeezebox","connected":1,"power":1},
                  {"playerid":"11:22:33:44:55:66","model":"Squeezelite","connected":0,"power":0}
                ]}}
                """.trimIndent()
            )
        })

        try {
            val players = LmsJsonRpcClient(client).getPlayers(LmsServerConnection("lms.local"))

            assertEquals(
                listOf(
                    LmsPlayer("aa:bb:cc:dd:ee:ff", "Living Room", "Squeezebox", true, true),
                    LmsPlayer("11:22:33:44:55:66", "Squeezelite", "Squeezelite", false, false)
                ),
                players
            )
            val body = (capturedRequest?.body as TextContent).text
            val requestJson = Json.parseToJsonElement(body).jsonObject
            assertEquals(
                "players",
                requestJson["params"]?.jsonArray?.get(1)?.jsonArray?.get(0)?.jsonPrimitive?.content
            )
            assertEquals(
                100,
                requestJson["params"]?.jsonArray?.get(1)?.jsonArray?.get(2)?.jsonPrimitive?.intOrNull
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun readsPlayerStatusAndMapsTrackMetadata() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson(
                """
                {"id":1,"result":{"mode":"play","time":"32.5","can_seek":1,
                  "playlist_loop":[{"title":"Track","artist":"Artist","album":"Album","duration":"210"}]}}
                """.trimIndent()
            )
        })

        try {
            val status = LmsJsonRpcClient(client).getPlayerStatus(
                LmsServerConnection("lms.local"),
                "aa:bb:cc:dd:ee:ff"
            )

            assertEquals(LmsPlaybackMode.PLAYING, status.mode)
            assertEquals("Track", status.title)
            assertEquals("Artist", status.artist)
            assertEquals("Album", status.album)
            assertEquals(32.5, status.positionSeconds, 0.0)
            assertEquals(210.0, status.durationSeconds!!, 0.0)
            assertTrue(status.canSeek)
            val requestJson = Json.parseToJsonElement(
                (capturedRequest?.body as TextContent).text
            ).jsonObject
            assertEquals(
                "aa:bb:cc:dd:ee:ff",
                requestJson["params"]?.jsonArray?.get(0)?.jsonPrimitive?.content
            )
            assertEquals(
                "status",
                requestJson["params"]?.jsonArray?.get(1)?.jsonArray?.get(0)?.jsonPrimitive?.content
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun sendsPlayerCommandsAndValidatesRanges() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson("{\"id\":1,\"result\":{}}")
        })

        try {
            LmsJsonRpcClient(client).sendCommand(
                LmsServerConnection("lms.local"),
                "aa:bb:cc:dd:ee:ff",
                LmsPlayerCommand.Next
            )

            val requestJson = Json.parseToJsonElement(
                (capturedRequest?.body as TextContent).text
            ).jsonObject
            assertEquals(
                listOf("playlist", "index", "+1"),
                requestJson["params"]?.jsonArray?.get(1)?.jsonArray?.map {
                    it.jsonPrimitive.content
                }
            )
            assertThrows(IllegalArgumentException::class.java) {
                LmsPlayerCommand.SeekTo(-1)
            }
            assertThrows(IllegalArgumentException::class.java) {
                LmsPlayerCommand.SetVolume(101)
            }
            assertEquals(listOf("mixer", "muting", "1"), LmsPlayerCommand.SetMuted(true).toCliTokens())
            assertEquals(listOf("power", "0"), LmsPlayerCommand.SetPower(false).toCliTokens())
            assertEquals(
                listOf("playlist", "shuffle", "2"),
                LmsPlayerCommand.SetShuffleMode(LmsShuffleMode.ALBUMS).toCliTokens()
            )
            assertEquals(
                listOf("playlist", "repeat", "1"),
                LmsPlayerCommand.SetRepeatMode(LmsRepeatMode.TRACK).toCliTokens()
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun readsPlayerPlaybackOptions() = runBlocking {
        val capturedCommands = mutableListOf<List<String>>()
        var responseIndex = 0
        val responses = listOf(
            "{\"id\":1,\"result\":{\"_shuffle\":2}}",
            "{\"id\":1,\"result\":{\"_repeat\":1}}"
        )
        val client = HttpClient(MockEngine { request ->
            val requestJson = Json.parseToJsonElement(
                (request.body as TextContent).text
            ).jsonObject
            capturedCommands += requestJson["params"]!!.jsonArray[1].jsonArray.map {
                it.jsonPrimitive.content
            }
            respondJson(responses[responseIndex++])
        })

        try {
            val options = LmsJsonRpcClient(client).getPlayerPlaybackOptions(
                LmsServerConnection("lms.local"),
                "aa:bb:cc:dd:ee:ff"
            )

            assertEquals(
                LmsPlayerPlaybackOptions(LmsShuffleMode.ALBUMS, LmsRepeatMode.TRACK),
                options
            )
            assertEquals(
                listOf(listOf("playlist", "shuffle", "?"), listOf("playlist", "repeat", "?")),
                capturedCommands
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun readsPlayerVolumeAndMuteState() = runBlocking {
        val capturedCommands = mutableListOf<List<String>>()
        var responseIndex = 0
        val responses = listOf(
            "{\"id\":1,\"result\":{\"_volume\":\"37\"}}",
            "{\"id\":1,\"result\":{\"_muting\":1}}"
        )
        val client = HttpClient(MockEngine { request ->
            val requestJson = Json.parseToJsonElement(
                (request.body as TextContent).text
            ).jsonObject
            capturedCommands += requestJson["params"]!!.jsonArray[1].jsonArray.map {
                it.jsonPrimitive.content
            }
            respondJson(responses[responseIndex++])
        })

        try {
            val mixerState = LmsJsonRpcClient(client).getPlayerMixerState(
                LmsServerConnection("lms.local"),
                "aa:bb:cc:dd:ee:ff"
            )

            assertEquals(LmsPlayerMixerState(37, true), mixerState)
            assertEquals(
                listOf(listOf("mixer", "volume", "?"), listOf("mixer", "muting", "?")),
                capturedCommands
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun reportsHttpAndProtocolFailures() {
        runBlocking {
            val unauthorizedClient = HttpClient(
                MockEngine { respond("", HttpStatusCode.Unauthorized) }
            )
            try {
                assertThrows(LmsHttpException::class.java) {
                    runBlocking {
                        LmsJsonRpcClient(unauthorizedClient).testConnection(LmsServerConnection("lms.local"))
                    }
                }
            } finally {
                unauthorizedClient.close()
            }

            val malformedClient = HttpClient(
                MockEngine { respondJson("{\"error\":\"invalid player\"}") }
            )
            try {
                assertThrows(LmsProtocolException::class.java) {
                    runBlocking {
                        LmsJsonRpcClient(malformedClient).testConnection(LmsServerConnection("lms.local"))
                    }
                }
            } finally {
                malformedClient.close()
            }
        }
    }

    private suspend fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(
            content = body,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )
}