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
    fun readsBoundedAlbumPagesWithFiltersAndArtwork() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson(
                """
                {"id":1,"result":{"count":101,"albums_loop":[
                                    {"id":42,"album":"Blue Train","artist":"John Coltrane",
                   "artwork_track_id":501,"artwork_url":"../imageproxy/cover.jpg"}
                ]}}
                """.trimIndent()
            )
        })

        try {
            val page = LmsJsonRpcClient(client).getPage(
                connection = LmsServerConnection("lms.local"),
                query = LmsLibraryQuery.ALBUMS,
                offset = 50,
                limit = 40,
                filter = LmsLibraryFilter(artistId = "7"),
                search = "blue train"
            )

            assertEquals(50, page.offset)
            assertEquals(101, page.totalCount)
            assertEquals(
                LmsLibraryItem(
                    id = "42",
                    type = LmsLibraryItemType.ALBUM,
                    title = "Blue Train",
                    artist = "John Coltrane",
                    artworkId = "501",
                    artworkUrl = "../imageproxy/cover.jpg"
                ),
                page.items.single()
            )
            val command = capturedCommand(capturedRequest)
            assertEquals(
                listOf("albums", "50", "40", "tags:aljK", "artist_id:7", "search:blue train"),
                command
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun requestsAlbumArtistPortraitsForArtistPages() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson(
                """{"id":1,"result":{"count":1,"artists_loop":[
                    {"id":7,"artist":"Album Artist","portraitid":"portrait-42"}
                ]}}"""
            )
        })

        try {
            val page = LmsJsonRpcClient(client).getPage(
                connection = LmsServerConnection("lms.local"),
                query = LmsLibraryQuery.ARTISTS,
                offset = 0,
                limit = 30
            )

            assertEquals("Album Artist", page.items.single().title)
            assertEquals("/contributor/portrait-42/image", page.items.single().artworkUrl)
            assertEquals(
                listOf("artists", "0", "30", "tags:4", "role_id:ALBUMARTIST"),
                capturedCommand(capturedRequest)
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun doesNotRequestArtworkForGenrePages() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson(
                """{"id":1,"result":{"count":1,"genres_loop":[
                    {"id":3,"genre":"Jazz"}
                ]}}"""
            )
        })

        try {
            val page = LmsJsonRpcClient(client).getPage(
                connection = LmsServerConnection("lms.local"),
                query = LmsLibraryQuery.GENRES,
                offset = 0,
                limit = 30
            )

            assertEquals("Jazz", page.items.single().title)
            assertEquals(null, page.items.single().artworkId)
            assertEquals(null, page.items.single().artworkUrl)
            assertEquals(listOf("genres", "0", "30"), capturedCommand(capturedRequest))
        } finally {
            client.close()
        }
    }

    @Test
    fun looksUpAlbumArtistArtworkFromMusicArtistInfoPhotoList() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson(
                """{"id":1,"result":{"item_loop":[
                    {"artist_id":"42","url":"https://images.example/artist-42-first.jpg"},
                    {"artist_id":"42","url":"https://images.example/artist-42-second.jpg"}
                ],"count":2,"offset":0}}"""
            )
        })

        try {
            val artworkUrl = LmsJsonRpcClient(client).getArtistArtworkUrl(
                connection = LmsServerConnection("lms.local"),
                artistId = "42"
            )

            assertEquals("https://images.example/artist-42-first.jpg", artworkUrl)
            assertEquals("-", capturedPlayerId(capturedRequest))
            assertEquals(
                listOf("musicartistinfo", "artistphotos", "artist_id:42"),
                capturedCommand(capturedRequest)
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun fallsBackToFirstAlbumCoverWhenAlbumArtistPortraitIsUnavailable() = runBlocking {
        val capturedCommands = mutableListOf<List<String>>()
        val client = HttpClient(MockEngine { request ->
            val command = capturedCommand(request)
            capturedCommands += command
            when (command.first()) {
                "musicartistinfo" -> respondJson(
                    """{"id":1,"result":{"error":"No artist photo found"}}"""
                )
                "albums" -> respondJson(
                    """{"id":1,"result":{"count":1,"albums_loop":[
                        {"id":8,"album":"First Album","artist":"Album Artist","artwork_track_id":"cover-8"}
                    ]}}"""
                )
                else -> error("Unexpected LMS command: $command")
            }
        })

        try {
            val artworkUrl = LmsJsonRpcClient(client).getArtistArtworkUrl(
                connection = LmsServerConnection("lms.local"),
                artistId = "42"
            )

            assertEquals("/music/cover-8/cover.jpg", artworkUrl)
            assertEquals(
                listOf(
                    listOf("musicartistinfo", "artistphotos", "artist_id:42"),
                    listOf("albums", "0", "1", "tags:aljK", "artist_id:42")
                ),
                capturedCommands
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun requestsArtworkTrackIdForTrackPages() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson(
                """{"id":1,"result":{"count":1,"titles_loop":[
                    {"id":7,"title":"Track","artwork_track_id":501}
                ]}}"""
            )
        })

        try {
            val page = LmsJsonRpcClient(client).getPage(
                connection = LmsServerConnection("lms.local"),
                query = LmsLibraryQuery.TRACKS,
                offset = 0,
                limit = 30
            )

            assertEquals("501", page.items.single().artworkId)
            assertEquals(
                listOf("titles", "0", "30", "tags:galdeKJc"),
                capturedCommand(capturedRequest)
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun readsLegacyTitleFieldForAlbumPages() = runBlocking {
        val client = HttpClient(MockEngine {
            respondJson(
                """
                {"id":1,"result":{"count":1,"albums_loop":[
                  {"id":42,"title":"Blue Train"}
                ]}}
                """.trimIndent()
            )
        })

        try {
            val page = LmsJsonRpcClient(client).getPage(
                connection = LmsServerConnection("lms.local"),
                query = LmsLibraryQuery.ALBUMS,
                offset = 0,
                limit = 10
            )

            assertEquals("Blue Train", page.items.single().title)
        } finally {
            client.close()
        }
    }

    @Test
    fun identifiesPlaylistFoldersFromUrlLikeIds() = runBlocking {
        val client = HttpClient(MockEngine {
            respondJson(
                """{"id":1,"result":{"count":1,"playlists_loop":[
                  {"id":"file:///music/playlists/Jazz","playlist":"Jazz"}
                ]}}""".trimIndent()
            )
        })

        try {
            val page = LmsJsonRpcClient(client).getPage(
                LmsServerConnection("lms.local"),
                LmsLibraryQuery.PLAYLISTS,
                offset = 0,
                limit = 30
            )

            assertTrue(page.items.single().isContainer)
            assertEquals(LmsLibraryItemType.PLAYLIST, page.items.single().type)
        } finally {
            client.close()
        }
    }

    @Test
    fun searchesArtistsAlbumsAndTracksInBoundedGroups() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson(
                """
                {"id":1,"result":{
                  "contributors_count":2,"contributors_loop":[{"contributor_id":3,"contributor":"Artist"}],
                  "albums_count":4,"albums_loop":[{"album_id":5,"album":"Album","artwork":6}],
                  "tracks_count":8,"tracks_loop":[{"track_id":7,"track":"Track","coverid":"5466de2c"}]
                }}
                """.trimIndent()
            )
        })

        try {
            val results = LmsJsonRpcClient(client).search(
                connection = LmsServerConnection("lms.local"),
                term = "blue train",
                offset = 20,
                limit = 10
            )

            assertEquals(listOf("Artist"), results.artists.items.map { it.title })
            assertEquals(2, results.artists.totalCount)
            assertEquals("6", results.albums.items.single().artworkId)
            assertEquals("5466de2c", results.tracks.items.single().artworkId)
            assertEquals(8, results.tracks.totalCount)
            assertEquals(
                listOf("search", "20", "10", "term:blue train", "extended:1"),
                capturedCommand(capturedRequest)
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun playsLibraryTracksByReplacingTheCurrentPlaylist() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson("{\"id\":1,\"result\":{}}")
        })

        try {
            LmsJsonRpcClient(client).playNow(
                connection = LmsServerConnection("lms.local"),
                playerId = "aa:bb:cc:dd:ee:ff",
                item = LmsLibraryItem("17", LmsLibraryItemType.TRACK, "Track")
            )

            val requestJson = Json.parseToJsonElement(
                (capturedRequest?.body as TextContent).text
            ).jsonObject
            assertEquals(
                "aa:bb:cc:dd:ee:ff",
                requestJson["params"]?.jsonArray?.get(0)?.jsonPrimitive?.content
            )
            assertEquals(
                listOf("playlistcontrol", "cmd:load", "track_id:17"),
                requestJson["params"]?.jsonArray?.get(1)?.jsonArray?.map {
                    it.jsonPrimitive.content
                }
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun playsAlbumsAndPlaylistsByReplacingTheCurrentPlaylist() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val client = HttpClient(MockEngine { request ->
            val requestJson = Json.parseToJsonElement(
                (request.body as TextContent).text
            ).jsonObject
            commands += requestJson["params"]!!.jsonArray[1].jsonArray.map {
                it.jsonPrimitive.content
            }
            respondJson("{\"id\":1,\"result\":{}}")
        })

        try {
            val gateway = LmsJsonRpcClient(client)
            val connection = LmsServerConnection("lms.local")
            gateway.playNow(
                connection,
                "player-1",
                LmsLibraryItem("album-1", LmsLibraryItemType.ALBUM, "Album")
            )
            gateway.playNow(
                connection,
                "player-1",
                LmsLibraryItem("playlist-1", LmsLibraryItemType.PLAYLIST, "Playlist")
            )

            assertEquals(
                listOf(
                    listOf("playlistcontrol", "cmd:load", "album_id:album-1"),
                    listOf("playlistcontrol", "cmd:load", "playlist_id:playlist-1")
                ),
                commands
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectsUnboundedLibraryPages() = runBlocking {
        val client = HttpClient(MockEngine { error("No request expected") })

        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    LmsJsonRpcClient(client).getPage(
                        LmsServerConnection("lms.local"),
                        LmsLibraryQuery.TRACKS,
                        offset = 0,
                        limit = 101
                    )
                }
            }
            Unit
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
                  "playlist_loop":[{"title":"Track","artist":"Artist","album":"Album","duration":"210",
                    "coverid":27,"artwork_url":"../imageproxy/cover.jpg"}]}}
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
            assertEquals("27", status.artworkId)
            assertEquals("../imageproxy/cover.jpg", status.artworkUrl)
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
            assertEquals(
                listOf("status", "-", "1", "tags:cgABKJ"),
                capturedCommand(capturedRequest)
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

    private fun capturedCommand(request: HttpRequestData?): List<String> {
        val requestJson = Json.parseToJsonElement((request?.body as TextContent).text).jsonObject
        return requestJson["params"]!!.jsonArray[1].jsonArray.map { it.jsonPrimitive.content }
    }

    private fun capturedPlayerId(request: HttpRequestData?): String {
        val requestJson = Json.parseToJsonElement((request?.body as TextContent).text).jsonObject
        return requestJson["params"]!!.jsonArray[0].jsonPrimitive.content
    }

    private suspend fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(
            content = body,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )
}