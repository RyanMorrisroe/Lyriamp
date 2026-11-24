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
    fun createsCredentialIndependentNormalizedEndpointKeys() {
        assertEquals(
            "http://lms.local:9000",
            LmsServerConnection(
                host = " LMS.LOCAL ",
                username = "listener",
                password = "secret"
            ).endpointKey()
        )
        assertEquals(
            "https://[::1]:9001",
            LmsServerConnection(host = "::1", port = 9001, useHttps = true).endpointKey()
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
    fun usesServerStatusFallbacksAndRequiresVersion() = runBlocking {
        val responses = listOf(
            """{"id":1,"result":{"_version":"9.2.1","players_loop":[{},{}]}}""",
            """{"id":1,"result":{"count":0}}"""
        )
        var responseIndex = 0
        val client = HttpClient(MockEngine { respondJson(responses[responseIndex++]) })

        try {
            assertEquals(
                LmsServerInfo("9.2.1", 2),
                LmsJsonRpcClient(client).testConnection(LmsServerConnection("lms.local"))
            )
            assertThrows(LmsProtocolException::class.java) {
                runBlocking {
                    LmsJsonRpcClient(client).testConnection(LmsServerConnection("lms.local"))
                }
            }
        } finally {
            client.close()
        }
        Unit
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
                                    {"id":42,"album":"Blue Train","artist":"John Coltrane","year":1967,
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
                    artworkUrl = "../imageproxy/cover.jpg",
                    year = "1967"
                ),
                page.items.single()
            )
            val command = capturedCommand(capturedRequest)
            assertEquals(
                listOf("albums", "50", "40", "tags:aljKy", "artist_id:7", "search:blue train"),
                command
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun requestsSelectedAlbumSortMode() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson(
                """{"id":1,"result":{"count":1,"albums_loop":[
                    {"id":42,"album":"Blue Train","artist":"John Coltrane"}
                ]}}"""
            )
        })

        try {
            val page = LmsJsonRpcClient(client).getPage(
                connection = LmsServerConnection("lms.local"),
                query = LmsLibraryQuery.ALBUMS,
                offset = 0,
                limit = 30,
                sort = LmsLibrarySort.YEAR_ALBUM
            )

            assertEquals(1, page.totalCount)
            assertEquals(
                listOf("albums", "0", "30", "tags:aljKy", "sort:yearalbum"),
                capturedCommand(capturedRequest)
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
                    listOf("albums", "0", "1", "tags:aljKy", "artist_id:42")
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
                listOf("titles", "0", "30", "tags:galdeitKJc"),
                capturedCommand(capturedRequest)
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun readsAlbumTrackDiscAndTrackNumbersAndRequestsAlbumTrackSort() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson(
                """
                {"id":1,"result":{"count":1,"titles_loop":[
                    {"id":7,"title":"Track","album":"Album","album_id":42,
                     "disc":"2","tracknum":"3/10","duration":180}
                ]}}
                """.trimIndent()
            )
        })

        try {
            val page = LmsJsonRpcClient(client).getPage(
                connection = LmsServerConnection("lms.local"),
                query = LmsLibraryQuery.TRACKS,
                offset = 0,
                limit = 30,
                filter = LmsLibraryFilter(albumId = "42")
            )
            val track = page.items.single()

            assertEquals("42", track.albumId)
            assertEquals(2, track.discNumber)
            assertEquals(3, track.trackNumber)
            assertEquals(
                listOf(
                    "titles",
                    "0",
                    "30",
                    "tags:galdeitKJc",
                    "sort:albumtrack",
                    "album_id:42"
                ),
                capturedCommand(capturedRequest)
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun playingAlbumTrackQueuesLaterTracksInDiscAndTrackOrder() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val client = HttpClient(MockEngine { request ->
            val requestJson = Json.parseToJsonElement(
                (request.body as TextContent).text
            ).jsonObject
            commands += requestJson["params"]!!.jsonArray[1].jsonArray.map {
                it.jsonPrimitive.content
            }
            respondJson(
                """
                {"id":1,"result":{"count":4,"titles_loop":[
                    {"id":11,"title":"Disc 1 Track 1","album_id":42,"disc":1,"tracknum":1},
                    {"id":12,"title":"Disc 1 Track 2","album_id":42,"disc":1,"tracknum":2},
                    {"id":21,"title":"Disc 2 Track 1","album_id":42,"disc":2,"tracknum":1},
                    {"id":22,"title":"Disc 2 Track 2","album_id":42,"disc":2,"tracknum":2}
                ]}}
                """.trimIndent()
            )
        })

        try {
            LmsJsonRpcClient(client).playNow(
                connection = LmsServerConnection("lms.local"),
                playerId = "player-1",
                item = LmsLibraryItem(
                    id = "12",
                    type = LmsLibraryItemType.TRACK,
                    title = "Disc 1 Track 2",
                    albumId = "42",
                    discNumber = 1,
                    trackNumber = 2
                )
            )

            assertEquals(
                listOf(
                    listOf("titles", "0", "100", "tags:galdeitKJc", "sort:albumtrack", "album_id:42"),
                    listOf("playlistcontrol", "cmd:load", "track_id:12"),
                    listOf("playlistcontrol", "cmd:add", "track_id:21"),
                    listOf("playlistcontrol", "cmd:add", "track_id:22")
                ),
                commands
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
        val capturedRequests = mutableListOf<HttpRequestData>()
        var responseIndex = 0
        val client = HttpClient(MockEngine { request ->
            capturedRequests += request
            val response = when (responseIndex++) {
                0 -> """
                    {"id":1,"result":{
                      "contributors_count":2,"contributors_loop":[{"contributor_id":3,"contributor":"Artist"}],
                      "albums_count":4,"albums_loop":[{"album_id":5,"album":"Album","artwork":6}],
                      "tracks_count":8,"tracks_loop":[{"track_id":7,"track":"Track","artist":"Track Artist",
                        "album":"Track Album","duration":180,"coverid":"5466de2c"}]
                    }}
                """.trimIndent()
                1 -> """
                    {"id":1,"result":{"count":1,"albums_loop":[
                      {"id":5,"album":"Album","artist":"Album Artist","year":2001}
                    ]}}
                """.trimIndent()
                else -> error("Unexpected LMS request.")
            }
            respondJson(response)
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
            assertEquals("Album Artist", results.albums.items.single().artist)
            assertEquals("2001", results.albums.items.single().year)
            assertEquals("Track Artist", results.tracks.items.single().artist)
            assertEquals("Track Album", results.tracks.items.single().album)
            assertEquals(180.0, results.tracks.items.single().durationSeconds!!, 0.0)
            assertEquals("5466de2c", results.tracks.items.single().artworkId)
            assertEquals(8, results.tracks.totalCount)
            assertEquals(
                listOf(
                    listOf("search", "20", "10", "term:blue train", "extended:1"),
                    listOf("albums", "0", "1", "tags:aljKy", "album_id:5")
                ),
                capturedRequests.map(::capturedCommand)
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun searchWithoutAlbumsSkipsAlbumMetadataLookup() = runBlocking {
        val capturedCommands = mutableListOf<List<String>>()
        val client = HttpClient(MockEngine { request ->
            capturedCommands += capturedCommand(request)
            respondJson("""{"id":1,"result":{"contributors_count":0,"albums_count":0,"tracks_count":0}}""")
        })

        try {
            val results = LmsJsonRpcClient(client).search(
                connection = LmsServerConnection("lms.local"),
                term = "artist",
                offset = 0,
                limit = 30
            )

            assertEquals(0, results.albums.totalCount)
            assertEquals(
                listOf(listOf("search", "0", "30", "term:artist", "extended:1")),
                capturedCommands
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
    fun playsAllArtistAndGenreTracksInAlbumOrder() = runBlocking {
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
                LmsLibraryItem("artist-1", LmsLibraryItemType.ARTIST, "Artist")
            )
            gateway.playNow(
                connection,
                "player-1",
                LmsLibraryItem("genre-1", LmsLibraryItemType.GENRE, "Genre")
            )

            assertEquals(
                listOf(
                    listOf("playlistcontrol", "cmd:load", "artist_id:artist-1", "sort:album"),
                    listOf("playlistcontrol", "cmd:load", "genre_id:genre-1", "sort:album")
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
                {"id":1,"result":{"mode":"play","time":"32.5","can_seek":1,"playlist_cur_index":0,
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
            assertEquals(0, status.playlistIndex)
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
                listOf("status", "-", "1", "tags:aclKN"),
                capturedCommand(capturedRequest)
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun readsPlayerStatusFallbackFieldsAndBooleanValues() = runBlocking {
        val responses = listOf(
            """{"id":1,"result":{"mode":"pause","current_title":"Fallback title","duration":"90","can_seek":"no","time":"invalid"}}""",
            """{"id":1,"result":{"duration":"90","can_seek":"unknown"}}"""
        )
        var responseIndex = 0
        val client = HttpClient(MockEngine { respondJson(responses[responseIndex++]) })

        try {
            val clientUnderTest = LmsJsonRpcClient(client)
            val status = clientUnderTest.getPlayerStatus(LmsServerConnection("lms.local"), "player")
            assertEquals(LmsPlaybackMode.PAUSED, status.mode)
            assertEquals("Fallback title", status.title)
            assertEquals(0.0, status.positionSeconds, 0.0)
            assertEquals(90.0, status.durationSeconds!!, 0.0)
            assertEquals(false, status.canSeek)

            val fallbackSeekStatus =
                clientUnderTest.getPlayerStatus(LmsServerConnection("lms.local"), "player")
            assertEquals(LmsPlaybackMode.UNKNOWN, fallbackSeekStatus.mode)
            assertEquals(true, fallbackSeekStatus.canSeek)
        } finally {
            client.close()
        }
    }

    @Test
    fun queriesAndChangesSyncGroupsUsingLmsCommands() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val playerIds = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            commands += capturedCommand(request)
            playerIds += capturedPlayerId(request)
            respondJson("""{"id":1,"result":{"_sync":"leader,member"}}""")
        })

        try {
            val gateway = LmsJsonRpcClient(client)
            val connection = LmsServerConnection("lms.local")

            assertEquals(
                listOf("leader", "member"),
                gateway.getSyncGroupMembers(connection, "leader")
            )
            gateway.syncPlayers(connection, "leader", "new-member")
            gateway.unsyncPlayer(connection, "member")

            assertEquals(
                listOf(
                    listOf("sync", "?"),
                    listOf("sync", "new-member"),
                    listOf("sync", "-")
                ),
                commands
            )
            assertEquals(listOf("leader", "leader", "member"), playerIds)
        } finally {
            client.close()
        }
    }

    @Test
    fun treatsLmsDashSyncResponseAsAnUnselectedPlayer() = runBlocking {
        val client = HttpClient(MockEngine {
            respondJson("""{"id":1,"result":{"_sync":"-"}}""")
        })

        try {
            assertEquals(
                listOf("solo-player"),
                LmsJsonRpcClient(client).getSyncGroupMembers(
                    LmsServerConnection("lms.local"),
                    "solo-player"
                )
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun readsQueuePagesWithAbsoluteIndexesAndMetadata() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respondJson(
                """
                {"id":1,"result":{"playlist_cur_index":1,"playlist_tracks":5,
                  "playlist_loop":[
                    {"playlist index":3,"id":"track-3","title":"Next track","artist":"Artist",
                      "album":"Album","duration":"180","coverid":"cover-3"}
                  ]}}
                """.trimIndent()
            )
        })

        try {
            val page = LmsJsonRpcClient(client).getPlayerQueue(
                LmsServerConnection("lms.local"),
                "player-1",
                offset = 3,
                limit = 2
            )

            assertEquals(3, page.offset)
            assertEquals(5, page.totalCount)
            assertEquals(1, page.currentIndex)
            assertEquals(
                listOf(LmsQueueItem(3, "track-3", "Next track", "Artist", "Album", 180.0, "cover-3", null)),
                page.items
            )
            assertEquals(listOf("status", "3", "2", "tags:aclKN"), capturedCommand(capturedRequest))
        } finally {
            client.close()
        }
    }

    @Test
    fun sendsQueueAddRemoveAndMoveCommands() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val client = HttpClient(MockEngine { request ->
            commands += capturedCommand(request)
            respondJson("{\"id\":1,\"result\":{}}")
        })

        try {
            val gateway = LmsJsonRpcClient(client)
            val connection = LmsServerConnection("lms.local")
            gateway.addQueueItem(
                connection,
                "player-1",
                LmsLibraryItem(
                    "track-1",
                    LmsLibraryItemType.TRACK,
                    "Track",
                    albumId = "42",
                    discNumber = 1,
                    trackNumber = 3
                ),
                LmsQueueAddPosition.END
            )
            gateway.addQueueItem(
                connection,
                "player-1",
                LmsLibraryItem("album-1", LmsLibraryItemType.ALBUM, "Album"),
                LmsQueueAddPosition.NEXT
            )
            gateway.addQueueItem(
                connection,
                "player-1",
                LmsLibraryItem("artist-1", LmsLibraryItemType.ARTIST, "Artist"),
                LmsQueueAddPosition.END
            )
            gateway.addQueueItem(
                connection,
                "player-1",
                LmsLibraryItem("genre-1", LmsLibraryItemType.GENRE, "Genre"),
                LmsQueueAddPosition.NEXT
            )
            gateway.addQueueItem(
                connection,
                "player-1",
                LmsLibraryItem("playlist-1", LmsLibraryItemType.PLAYLIST, "Playlist"),
                LmsQueueAddPosition.END
            )
            gateway.removeQueueItem(connection, "player-1", 4)
            gateway.moveQueueItem(connection, "player-1", fromIndex = 5, toIndex = 2)

            assertEquals(
                listOf(
                    listOf("playlistcontrol", "cmd:add", "track_id:track-1"),
                    listOf("playlistcontrol", "cmd:insert", "album_id:album-1"),
                    listOf("playlistcontrol", "cmd:add", "artist_id:artist-1", "sort:album"),
                    listOf("playlistcontrol", "cmd:insert", "genre_id:genre-1", "sort:album"),
                    listOf("playlistcontrol", "cmd:add", "playlist_id:playlist-1"),
                    listOf("playlist", "delete", "4"),
                    listOf("playlist", "move", "5", "2")
                ),
                commands
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    gateway.addQueueItem(
                        connection,
                        "player-1",
                        LmsLibraryItem(
                            "folder-1",
                            LmsLibraryItemType.PLAYLIST,
                            "Playlist folder",
                            isContainer = true
                        ),
                        LmsQueueAddPosition.END
                    )
                }
            }
            Unit
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
    fun rejectsInvalidMixerAndPlaybackOptionResponses() = runBlocking {
        val responses = listOf(
            """{"id":1,"result":{"_volume":101}}""",
            """{"id":1,"result":{"_volume":37}}""",
            """{"id":1,"result":{}}""",
            """{"id":1,"result":{}}""",
            """{"id":1,"result":{"_shuffle":9}}""",
            """{"id":1,"result":{"_shuffle":0}}""",
            """{"id":1,"result":{}}""",
            """{"id":1,"result":{"_shuffle":0}}""",
            """{"id":1,"result":{"_repeat":9}}"""
        )
        var responseIndex = 0
        val client = HttpClient(MockEngine { respondJson(responses[responseIndex++]) })

        try {
            val clientUnderTest = LmsJsonRpcClient(client)
            assertThrows(LmsProtocolException::class.java) {
                runBlocking {
                    clientUnderTest.getPlayerMixerState(LmsServerConnection("lms.local"), "player")
                }
            }
            assertThrows(LmsProtocolException::class.java) {
                runBlocking {
                    clientUnderTest.getPlayerMixerState(LmsServerConnection("lms.local"), "player")
                }
            }
            assertThrows(LmsProtocolException::class.java) {
                runBlocking {
                    clientUnderTest.getPlayerPlaybackOptions(LmsServerConnection("lms.local"), "player")
                }
            }
            assertThrows(LmsProtocolException::class.java) {
                runBlocking {
                    clientUnderTest.getPlayerPlaybackOptions(LmsServerConnection("lms.local"), "player")
                }
            }
            assertThrows(LmsProtocolException::class.java) {
                runBlocking {
                    clientUnderTest.getPlayerPlaybackOptions(LmsServerConnection("lms.local"), "player")
                }
            }
            assertThrows(LmsProtocolException::class.java) {
                runBlocking {
                    clientUnderTest.getPlayerPlaybackOptions(LmsServerConnection("lms.local"), "player")
                }
            }
        } finally {
            client.close()
        }
        Unit
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

    @Test
    fun reportsInvalidJsonAndNonObjectResponses() {
        runBlocking {
            val responses = listOf("not-json", "[]")
            var responseIndex = 0
            val client = HttpClient(MockEngine {
                respond(
                    content = responses[responseIndex++],
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            })

            try {
                repeat(2) {
                    assertThrows(LmsProtocolException::class.java) {
                        runBlocking {
                            LmsJsonRpcClient(client).testConnection(LmsServerConnection("lms.local"))
                        }
                    }
                }
            } finally {
                client.close()
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