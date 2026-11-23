package com.ryanmorrisroe.lyriamp.feature.library

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayerStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsConnectionGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsJsonRpcClient
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryFilter
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItemType
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryPage
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryQuery
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibrarySearchResults
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlaybackMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerCommand
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerDirectory
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerMixerState
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerPlaybackOptions
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerQueuePage
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerRemoteGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerStatus
import com.ryanmorrisroe.lyriamp.data.lms.LmsQueueAddPosition
import com.ryanmorrisroe.lyriamp.data.lms.LmsRepeatMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerInfo
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleMode
import com.ryanmorrisroe.lyriamp.data.persistence.Appearance
import com.ryanmorrisroe.lyriamp.data.persistence.DisplayPreferences
import com.ryanmorrisroe.lyriamp.data.persistence.DisplayPreferencesRepository
import com.ryanmorrisroe.lyriamp.data.persistence.LibraryLayout
import com.ryanmorrisroe.lyriamp.data.persistence.LibraryViewType
import com.ryanmorrisroe.lyriamp.feature.connection.ConnectionViewModel
import com.ryanmorrisroe.lyriamp.feature.settings.DisplayPreferencesViewModel
import com.ryanmorrisroe.lyriamp.feature.settings.SettingsScreen
import com.ryanmorrisroe.lyriamp.ui.theme.LyriampTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val httpClient = HttpClient(Android)

    @After
    fun closeClient() {
        httpClient.close()
    }

    @Test
    fun layoutsAreIndependentAndSongsDoNotOfferCoverWall() {
        val preferences = FakeDisplayPreferences()
        showLibrary(preferences)

        compose.onAllNodesWithText("Artists").onFirst().performClick()
        compose.onNodeWithContentDescription("Cover wall").performClick()
        compose.onAllNodesWithText("Albums").onFirst().performClick()
        compose.onNodeWithContentDescription("Compact").performClick()
        compose.onAllNodesWithText("Artists").onFirst().performClick()
        compose.onNodeWithContentDescription("Cover wall").assertIsOn()
        compose.onAllNodesWithText("Albums").onFirst().performClick()
        compose.onNodeWithContentDescription("Compact").assertIsOn()
        compose.onAllNodesWithText("Songs").onFirst().performClick()
        compose.onNodeWithContentDescription("Cover wall").assertDoesNotExist()
        compose.onNodeWithContentDescription("Comfortable").assertIsOn()
    }

    @Test
    fun playlistTabRemainsReachableFromScrollableNavigation() {
        showLibrary(FakeDisplayPreferences())

        compose.onAllNodesWithText("Playlists").onFirst().performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Playlist 0").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun toolbarAndPlayerIconActionsHaveAccessibleLabels() {
        showLibrary(FakeDisplayPreferences())
        compose.onAllNodesWithText("Artists").onFirst().performClick()

        compose.onNodeWithContentDescription("Back").fetchSemanticsNode()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Search artists, albums, and tracks").fetchSemanticsNode()
        compose.onNodeWithContentDescription("Close search").fetchSemanticsNode()
        compose.onNodeWithContentDescription("Previous track").fetchSemanticsNode()
        compose.onNodeWithContentDescription("Play playback").fetchSemanticsNode()
        compose.onNodeWithContentDescription("Next track").fetchSemanticsNode()

        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Players").fetchSemanticsNode()
        compose.onNodeWithText("Settings").fetchSemanticsNode()
    }

    @Test
    fun playerControlsPlayActionUsesAnAccessibleIcon() {
        var playbackStarted = false
        val isPlaying = mutableStateOf(false)
        compose.setContent {
            LyriampTheme(dynamicColor = false) {
                PlayerControlsPlaybackButton(isPlaying = isPlaying.value) {
                    playbackStarted = true
                    isPlaying.value = true
                }
            }
        }

        compose.onNodeWithContentDescription("Play playback").performClick()
        compose.onNodeWithText("Play", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertTrue(playbackStarted) }
        compose.onNodeWithContentDescription("Pause playback").fetchSemanticsNode()

        compose.onNodeWithContentDescription("Pause playback").fetchSemanticsNode()
    }

    @Test
    fun dedicatedListsUsePlayIconsWithoutTypeLabels() {
        showLibrary(FakeDisplayPreferences())

        compose.onAllNodesWithText("Artists").onFirst().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Artist 0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Play Artist 0").fetchSemanticsNode()
        compose.onNodeWithText("Artist", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(
            1,
            compose.onAllNodesWithText("Artist 0", useUnmergedTree = true)
                .fetchSemanticsNodes().size
        )

        compose.onAllNodesWithText("Genres").onFirst().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Genre 0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Play Genre 0").fetchSemanticsNode()
        compose.onNodeWithText("Genre", useUnmergedTree = true).assertDoesNotExist()

        compose.onAllNodesWithText("Albums").onFirst().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Album 0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Play Album 0").fetchSemanticsNode()
        compose.onNodeWithText("Album", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithText("Album Artist · 1999", useUnmergedTree = true)
            .onFirst().fetchSemanticsNode()
        compose.onNodeWithTag("library-layout-option-COVER_WALL")
            .assertWidthIsEqualTo(40.dp)
        compose.onNodeWithTag("library-layout-glyph-COVER_WALL", useUnmergedTree = true)
            .assertWidthIsEqualTo(22.dp)

        compose.onAllNodesWithText("Songs").onFirst().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Song 0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Play Song 0").fetchSemanticsNode()
        compose.onNodeWithText("Track", useUnmergedTree = true).assertDoesNotExist()

        compose.onAllNodesWithText("Playlists").onFirst().performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Playlist 0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Play Playlist 0").fetchSemanticsNode()
        compose.onNodeWithText("Playlist", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun playTapRemainsImmediateAndLongPressOffersQueueActions() {
        val store = LmsActivePlayerStore().apply {
            select(LmsActivePlayer(LmsServerConnection("localhost"), "player", "Living room"))
        }
        val queueAdds = CopyOnWriteArrayList<Pair<LmsLibraryItem, LmsQueueAddPosition>>()
        val connection = connectedPlayer(store, AtomicInteger(), queueAdds)
        val gateway = showLibrary(FakeDisplayPreferences(), store, connection)

        compose.onAllNodesWithText("Songs").onFirst().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Song 0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Play Song 0").performClick()
        compose.waitUntil(10_000) { gateway.playedItems.isNotEmpty() }
        compose.runOnIdle {
            assertEquals("Song 0", gateway.playedItems.last().title)
            assertTrue(queueAdds.isEmpty())
        }

        compose.onNodeWithContentDescription("Play Song 0").performTouchInput {
            down(center)
            advanceEventTime(700)
            up()
        }
        compose.onNodeWithText("Add to end").performClick()
        compose.waitUntil(10_000) { queueAdds.size == 1 }
        compose.runOnIdle {
            assertEquals("Song 0", queueAdds.single().first.title)
            assertEquals(LmsQueueAddPosition.END, queueAdds.single().second)
            assertEquals(1, gateway.playedItems.size)
        }
    }

    @Test
    fun queuePickerAddsItemsWithoutLeavingTheLibrary() {
        val store = LmsActivePlayerStore().apply {
            select(LmsActivePlayer(LmsServerConnection("localhost"), "player", "Living room"))
        }
        val queueAdds = CopyOnWriteArrayList<Pair<LmsLibraryItem, LmsQueueAddPosition>>()
        val connection = connectedPlayer(store, AtomicInteger(), queueAdds)
        showLibrary(
            FakeDisplayPreferences(),
            store,
            connection,
            queuePickerMode = true
        )

        compose.onAllNodesWithText("Songs").onFirst().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Add Song 0 to queue")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Add Song 0 to queue").performClick()
        compose.waitUntil(10_000) { queueAdds.size == 1 }

        compose.onNodeWithText("Add to queue").fetchSemanticsNode()
        compose.runOnIdle {
            assertEquals("Song 0", queueAdds.single().first.title)
            assertEquals(LmsQueueAddPosition.END, queueAdds.single().second)
        }
    }

    @Test
    fun albumRowsKeepYearWhenOpenedFromAnArtist() {
        showLibrary(FakeDisplayPreferences())

        compose.onAllNodesWithText("Artists").onFirst().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Artist 0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Artist 0").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Album 0").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onAllNodesWithText("Album Artist · 1999", useUnmergedTree = true)
            .onFirst().fetchSemanticsNode()
        compose.onNodeWithText("Album", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun mixedSearchResultsKeepItemTypeLabels() {
        val gateway = showLibrary(FakeDisplayPreferences(), searchText = "mixed")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Search Artist").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Search Album").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Search Track").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onAllNodesWithText("Artist", useUnmergedTree = true).onFirst().fetchSemanticsNode()
        compose.onNodeWithText("Artist · Search Artist", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(
            "Album · Album Artist · 2001",
            useUnmergedTree = true
        ).fetchSemanticsNode()
        compose.onNodeWithText(
            "Track · Track Artist · Track Album · 3:00",
            useUnmergedTree = true
        ).fetchSemanticsNode()
        compose.onNodeWithContentDescription("Play Search Artist").fetchSemanticsNode()
        compose.waitUntil(10_000) { gateway.artistArtworkRequests.get() > 0 }
    }

    @Test
    fun settingsOfferAppearanceAndConnections() {
        val preferences = FakeDisplayPreferences()
        val display = DisplayPreferencesViewModel(preferences)
        var connectionsOpened = false
        compose.setContent {
            LyriampTheme(dynamicColor = false) {
                SettingsScreen(display, onBack = {}, onConnections = { connectionsOpened = true })
            }
        }
        compose.onNodeWithText("Dark").performClick()
        compose.runOnIdle { assertEquals(Appearance.DARK, preferences.observe().value.appearance) }
        compose.onNodeWithText("Light").performClick()
        compose.runOnIdle { assertEquals(Appearance.LIGHT, preferences.observe().value.appearance) }
        compose.onNodeWithText("Follow system").performClick()
        compose.runOnIdle { assertEquals(Appearance.SYSTEM, preferences.observe().value.appearance) }
        compose.onNodeWithText("Connections").performClick()
        compose.runOnIdle { assertTrue(connectionsOpened) }
    }

    @Test
    fun deepScrollStaysNearSameItemWhenSwitchingLayout() {
        val gateway = showLibrary(FakeDisplayPreferences())
        compose.onAllNodesWithText("Albums").onFirst().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Album 0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("library-results").performScrollToIndex(90000)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Album 90000").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Artists").onFirst().performClick()
        compose.onAllNodesWithText("Albums").onFirst().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Album 90000").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Cover wall").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Album 90000").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Expected a direct request near item 90000", gateway.requests.any { it.second >= 89900 })
        assertTrue("Deep navigation must not scan thousands of pages", gateway.requests.size < 40)
    }

    @Test
    fun playerStripProgressRefreshesFromLmsWhileLibraryIsVisible() {
        val store = LmsActivePlayerStore()
        val statusReads = AtomicInteger()
        val connection = connectedPlayer(store, statusReads)
        val readsBeforeLibrary = statusReads.get()
        showLibrary(FakeDisplayPreferences(), store, connection)
        val progress = compose.onNodeWithTag("player-strip-progress")
        val initialPosition = progress.fetchSemanticsNode().config[
            SemanticsProperties.ProgressBarRangeInfo
        ].current

        compose.waitUntil(10_000) {
            statusReads.get() > readsBeforeLibrary + 1 &&
                progress.fetchSemanticsNode().config[
                    SemanticsProperties.ProgressBarRangeInfo
                ].current > initialPosition
        }
    }

    private fun showLibrary(
        preferences: FakeDisplayPreferences,
        store: LmsActivePlayerStore = LmsActivePlayerStore().apply {
            select(LmsActivePlayer(LmsServerConnection("localhost"), "player", "Living room"))
        },
        connection: ConnectionViewModel = run {
            val client = LmsJsonRpcClient(httpClient)
            ConnectionViewModel(client, client, client, store)
        },
        searchText: String = "",
        queuePickerMode: Boolean = false
    ): FakeLibrary {
        val gateway = FakeLibrary()
        val library = LibraryViewModel(gateway, store)
        if (searchText.isNotBlank()) library.setSearchText(searchText)
        val display = DisplayPreferencesViewModel(preferences)
        compose.setContent {
            LyriampTheme(dynamicColor = false) {
                LibraryScreen(
                    viewModel = library,
                    connectionViewModel = connection,
                    displayPreferencesViewModel = display,
                    mediaPlayer = null,
                    onChangePlayer = {},
                    onOpenSettings = {},
                    onOpenNowPlaying = {},
                    queuePickerMode = queuePickerMode
                )
            }
        }
        return gateway
    }

    private fun connectedPlayer(
        store: LmsActivePlayerStore,
        statusReads: AtomicInteger,
        queueAdds: MutableList<Pair<LmsLibraryItem, LmsQueueAddPosition>> = mutableListOf()
    ): ConnectionViewModel {
        val directory = object : LmsConnectionGateway, LmsPlayerDirectory {
            override suspend fun testConnection(connection: LmsServerConnection) =
                LmsServerInfo("test", 1)

            override suspend fun getPlayers(connection: LmsServerConnection) =
                listOf(LmsPlayer("player", "Living room", "Test", true, true))
        }
        val remote = object : LmsPlayerRemoteGateway {
            override suspend fun getPlayerStatus(connection: LmsServerConnection, playerId: String) =
                LmsPlayerStatus(
                    mode = LmsPlaybackMode.PLAYING,
                    title = "Playing",
                    artist = "Test artist",
                    album = "Test album",
                    positionSeconds = statusReads.incrementAndGet().toDouble(),
                    durationSeconds = 120.0,
                    canSeek = true
                )

            override suspend fun getPlayerQueue(
                connection: LmsServerConnection,
                playerId: String,
                offset: Int,
                limit: Int
            ) = LmsPlayerQueuePage(emptyList(), offset, 0, null)

            override suspend fun addQueueItem(
                connection: LmsServerConnection,
                playerId: String,
                item: LmsLibraryItem,
                position: LmsQueueAddPosition
            ) {
                queueAdds += item to position
            }

            override suspend fun removeQueueItem(
                connection: LmsServerConnection,
                playerId: String,
                index: Int
            ) = Unit

            override suspend fun moveQueueItem(
                connection: LmsServerConnection,
                playerId: String,
                fromIndex: Int,
                toIndex: Int
            ) = Unit

            override suspend fun getPlayerMixerState(connection: LmsServerConnection, playerId: String) =
                LmsPlayerMixerState(50, false)

            override suspend fun getPlayerPlaybackOptions(connection: LmsServerConnection, playerId: String) =
                LmsPlayerPlaybackOptions(LmsShuffleMode.OFF, LmsRepeatMode.OFF)

            override suspend fun sendCommand(
                connection: LmsServerConnection,
                playerId: String,
                command: LmsPlayerCommand
            ) = Unit
        }
        val viewModel = ConnectionViewModel(directory, directory, remote, store)
        viewModel.onHostChanged("lms.local")
        viewModel.testConnection()
        compose.waitUntil(5_000) { viewModel.uiState.value.activeConnection != null }
        viewModel.selectPlayer("player")
        compose.waitUntil(5_000) {
            !viewModel.uiState.value.isLoadingPlayerStatus && viewModel.uiState.value.playerStatus != null
        }
        return viewModel
    }

    private class FakeDisplayPreferences : DisplayPreferencesRepository {
        private val value = MutableStateFlow(DisplayPreferences())
        override fun observe() = value
        override suspend fun setAppearance(appearance: Appearance) {
            value.update { it.copy(appearance = appearance) }
        }
        override suspend fun setLayout(type: LibraryViewType, layout: LibraryLayout) {
            value.update { it.copy(layouts = it.layouts + (type to layout)) }
        }
    }

    private class FakeLibrary : LmsLibraryGateway {
        val requests = CopyOnWriteArrayList<Pair<LmsLibraryQuery, Int>>()
        val artistArtworkRequests = AtomicInteger()
        val playedItems = CopyOnWriteArrayList<LmsLibraryItem>()

        override suspend fun getPage(
            connection: LmsServerConnection,
            query: LmsLibraryQuery,
            offset: Int,
            limit: Int,
            filter: LmsLibraryFilter,
            search: String?
        ): LmsLibraryPage {
            requests.add(query to offset)
            val type = when (query) {
                LmsLibraryQuery.ARTISTS -> LmsLibraryItemType.ARTIST
                LmsLibraryQuery.ALBUMS -> LmsLibraryItemType.ALBUM
                LmsLibraryQuery.GENRES -> LmsLibraryItemType.GENRE
                LmsLibraryQuery.TRACKS, LmsLibraryQuery.PLAYLIST_TRACKS -> LmsLibraryItemType.TRACK
                LmsLibraryQuery.PLAYLISTS -> LmsLibraryItemType.PLAYLIST
            }
            val title = when (type) {
                LmsLibraryItemType.ARTIST -> "Artist"
                LmsLibraryItemType.ALBUM -> "Album"
                LmsLibraryItemType.GENRE -> "Genre"
                LmsLibraryItemType.TRACK -> "Song"
                LmsLibraryItemType.PLAYLIST -> "Playlist"
            }
            val count = 100001
            return LmsLibraryPage(
                (offset until minOf(offset + limit, count)).map {
                    LmsLibraryItem(
                        id = it.toString(),
                        type = type,
                        title = "$title $it",
                        artist = when (type) {
                            LmsLibraryItemType.ARTIST -> "$title $it"
                            LmsLibraryItemType.ALBUM -> "Album Artist"
                            else -> null
                        },
                        year = if (type == LmsLibraryItemType.ALBUM) "1999" else null
                    )
                },
                offset,
                count
            )
        }

        override suspend fun search(
            connection: LmsServerConnection,
            term: String,
            offset: Int,
            limit: Int
        ): LmsLibrarySearchResults {
            fun page(
                type: LmsLibraryItemType,
                title: String,
                year: String? = null,
                artist: String? = null,
                album: String? = null,
                durationSeconds: Double? = null
            ) =
                LmsLibraryPage(
                    items = if (offset == 0) {
                        listOf(
                            LmsLibraryItem(
                                id = "search-${type.name}",
                                type = type,
                                title = title,
                                artist = artist,
                                album = album,
                                durationSeconds = durationSeconds,
                                year = year
                            )
                        )
                    } else {
                        emptyList()
                    },
                    offset = offset,
                    totalCount = 1
                )

            return LmsLibrarySearchResults(
                artists = page(
                    LmsLibraryItemType.ARTIST,
                    "Search Artist",
                    artist = "Search Artist"
                ),
                albums = page(
                    LmsLibraryItemType.ALBUM,
                    "Search Album",
                    year = "2001",
                    artist = "Album Artist"
                ),
                tracks = page(
                    LmsLibraryItemType.TRACK,
                    "Search Track",
                    artist = "Track Artist",
                    album = "Track Album",
                    durationSeconds = 180.0
                )
            )
        }

        override suspend fun getArtistArtworkUrl(
            connection: LmsServerConnection,
            artistId: String
        ): String? {
            artistArtworkRequests.incrementAndGet()
            return null
        }

        override suspend fun playNow(connection: LmsServerConnection, playerId: String, item: LmsLibraryItem) {
            playedItems += item
        }
    }
}
