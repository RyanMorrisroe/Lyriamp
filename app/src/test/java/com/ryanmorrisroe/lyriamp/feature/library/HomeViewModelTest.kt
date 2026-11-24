package com.ryanmorrisroe.lyriamp.feature.library

import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayerStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryFilter
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItemType
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryPage
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryQuery
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibrarySearchResults
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibrarySort
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleScope
import com.ryanmorrisroe.lyriamp.data.persistence.HomeDensity
import com.ryanmorrisroe.lyriamp.data.persistence.HomePin
import com.ryanmorrisroe.lyriamp.data.persistence.HomePreferences
import com.ryanmorrisroe.lyriamp.data.persistence.HomePreferencesRepository
import com.ryanmorrisroe.lyriamp.data.persistence.HomeSection
import com.ryanmorrisroe.lyriamp.data.persistence.HomeSectionType
import com.ryanmorrisroe.lyriamp.data.persistence.HomeSpecialAction
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsConfiguredShelvesForTheActiveServer() = runTest {
        val store = LmsActivePlayerStore()
        val preferences = FakeHomePreferencesRepository(
            initial = HomePreferences(
                pins = listOf(
                    HomePin.special(HomeSpecialAction.SHUFFLE_TRACKS, "Shuffle")
                )
            )
        )
        val gateway = FakeHomeLibraryGateway()
        val viewModel = HomeViewModel(gateway, store, preferences)

        store.select(
            LmsActivePlayer(
                connection = LmsServerConnection("lms.local"),
                playerId = "player-1",
                playerName = "Living Room"
            )
        )
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.preferences.pins.size)
        assertFalse(viewModel.state.value.shelves.isEmpty())
        assertEquals(
            LmsLibraryQuery.ALBUMS,
            gateway.requestedQueries[HomeSectionType.RECENTLY_ADDED_ALBUMS]
        )
        assertEquals(
            LmsLibrarySort.NEW,
            gateway.requestedSorts[HomeSectionType.RECENTLY_ADDED_ALBUMS]
        )
    }

    @Test
    fun artistShelvesResolvePortraitArtwork() = runTest {
        val store = LmsActivePlayerStore()
        val gateway = FakeHomeLibraryGateway()
        val preferences = FakeHomePreferencesRepository(
            initial = HomePreferences(
                sections = listOf(
                    HomeSection(
                        HomeSectionType.RECENTLY_ADDED_ARTISTS,
                        enabled = true,
                        limit = 20
                    )
                )
            )
        )
        val viewModel = HomeViewModel(gateway, store, preferences)
        store.select(
            LmsActivePlayer(
                connection = LmsServerConnection("lms.local"),
                playerId = "player-1",
                playerName = "Living Room"
            )
        )
        advanceUntilIdle()

        val shelf = viewModel.state.value.shelves[
            HomeSectionType.RECENTLY_ADDED_ARTISTS
        ]
        assertEquals("/contributor/1/image", shelf?.items?.single()?.artworkUrl)
    }

    @Test
    fun togglingASectionPreservesItsPosition() = runTest {
        val store = LmsActivePlayerStore()
        store.select(
            LmsActivePlayer(
                connection = LmsServerConnection("lms.local"),
                playerId = "player-1",
                playerName = "Living Room"
            )
        )
        val preferences = FakeHomePreferencesRepository()
        val viewModel = HomeViewModel(FakeHomeLibraryGateway(), store, preferences)
        advanceUntilIdle()

        val originalOrder = viewModel.state.value.preferences.sections.map { it.type }
        val mostPlayed = viewModel.state.value.preferences.sections
            .first { it.type == HomeSectionType.MOST_PLAYED_ALBUMS }

        viewModel.setSection(mostPlayed.copy(enabled = !mostPlayed.enabled))
        advanceUntilIdle()

        assertEquals(
            originalOrder,
            viewModel.state.value.preferences.sections.map { it.type }
        )
    }

    @Test
    fun pinAndLayoutMutationsPersistAndShuffleExecutes() = runTest {
        val store = LmsActivePlayerStore()
        store.select(
            LmsActivePlayer(
                connection = LmsServerConnection("lms.local"),
                playerId = "player-1",
                playerName = "Living Room"
            )
        )
        val preferences = FakeHomePreferencesRepository()
        val gateway = FakeHomeLibraryGateway()
        val viewModel = HomeViewModel(gateway, store, preferences)
        advanceUntilIdle()

        viewModel.setDensity(HomeDensity.COMPACT)
        viewModel.addPin(
            HomePin.libraryItem(
                itemType = LmsLibraryItemType.ALBUM,
                itemId = "42",
                title = "Blue Train",
                filter = LmsLibraryFilter(albumId = "42")
            )
        )
        advanceUntilIdle()

        assertEquals(HomeDensity.COMPACT, viewModel.state.value.preferences.density)
        assertEquals("Blue Train", viewModel.state.value.preferences.pins.single().title)

        viewModel.executeSpecialAction(HomeSpecialAction.SHUFFLE_ALBUMS)
        advanceUntilIdle()

        assertEquals(LmsShuffleScope.ALBUMS, gateway.shuffleScope)
        assertTrue(viewModel.state.value.actionMessage == "Shuffle started.")
    }

    @Test
    fun sectionPinAndSearchMutationsAndFavoritesLocationWork() = runTest {
        val store = LmsActivePlayerStore()
        store.select(
            LmsActivePlayer(
                connection = LmsServerConnection("lms.local"),
                playerId = "player-1",
                playerName = "Living Room"
            )
        )
        val preferences = FakeHomePreferencesRepository()
        val gateway = FakeHomeLibraryGateway()
        val viewModel = HomeViewModel(gateway, store, preferences)
        advanceUntilIdle()

        val albumPin = HomePin.libraryItem(
            itemType = LmsLibraryItemType.ALBUM,
            itemId = "42",
            title = "Blue Train",
            filter = LmsLibraryFilter(albumId = "42")
        )
        viewModel.addPin(albumPin)
        viewModel.addPin(albumPin)
        viewModel.movePin(0, 0)
        viewModel.movePin(-1, 99)
        viewModel.removePin(albumPin)
        viewModel.setSection(
            HomeSection(
                HomeSectionType.RECENTLY_ADDED_ARTISTS,
                enabled = true,
                limit = 30
            )
        )
        viewModel.moveSection(0, 2)
        viewModel.moveSection(-1, 99)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.preferences.pins.isEmpty())
        assertTrue(
            viewModel.state.value.preferences.sections.any {
                it.type == HomeSectionType.RECENTLY_ADDED_ARTISTS &&
                    it.enabled &&
                    it.limit == 30
            }
        )

        viewModel.searchPinCandidates(LmsLibraryItemType.ARTIST, "Davis")
        advanceUntilIdle()
        assertEquals(LmsLibraryItemType.ARTIST, viewModel.state.value.pinCandidateType)
        assertFalse(viewModel.state.value.pinCandidates.isEmpty())
        assertEquals("Test item", viewModel.state.value.pinCandidates.single().title)
        viewModel.clearPinCandidates()
        assertEquals(emptyList<LmsLibraryItem>(), viewModel.state.value.pinCandidates)

        viewModel.resetPreferences()
        advanceUntilIdle()
        assertEquals(HomePreferences(), viewModel.state.value.preferences)
        assertEquals("Favorites", viewModel.favoritesLocation().title)
        assertEquals(LmsLibraryQuery.FAVORITES, viewModel.favoritesLocation().query)

        viewModel.executeSpecialAction(HomeSpecialAction.FAVORITES)
        viewModel.clearActionMessage()
    }

    @Test
    fun handlesMissingPlayerSearchAndShuffleFailures() = runTest {
        val store = LmsActivePlayerStore()
        val preferences = FakeHomePreferencesRepository()
        val gateway = FakeHomeLibraryGateway()
        val viewModel = HomeViewModel(gateway, store, preferences)
        advanceUntilIdle()

        viewModel.searchPinCandidates(LmsLibraryItemType.ALBUM, "Blue")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.pinSearchError?.contains("connected player") == true)

        viewModel.executeSpecialAction(HomeSpecialAction.SHUFFLE_TRACKS)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.actionMessage?.contains("Select a connected player") == true)

        store.select(
            LmsActivePlayer(
                connection = LmsServerConnection("lms.local"),
                playerId = "player-1",
                playerName = "Living Room"
            )
        )
        gateway.shuffleError = IllegalStateException("shuffle failed")
        advanceUntilIdle()
        viewModel.executeSpecialAction(HomeSpecialAction.SHUFFLE_ARTISTS)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.actionMessage?.contains("Could not start shuffle") == true)

        gateway.pageError = IllegalStateException("page failed")
        viewModel.retryShelf(HomeSectionType.RECENTLY_ADDED_ALBUMS)
        advanceUntilIdle()
        assertTrue(
            viewModel.state.value.shelves[HomeSectionType.RECENTLY_ADDED_ALBUMS]
                ?.error?.contains("Could not load") == true
        )
    }

    @Test
    fun coversAllPinSearchTypesArtistSectionsAndRepositoryErrors() = runTest {
        val store = LmsActivePlayerStore()
        store.select(
            LmsActivePlayer(
                connection = LmsServerConnection("lms.local"),
                playerId = "player-1",
                playerName = "Living Room"
            )
        )
        val preferences = FakeHomePreferencesRepository()
        val gateway = FakeHomeLibraryGateway()
        val viewModel = HomeViewModel(gateway, store, preferences)
        advanceUntilIdle()

        listOf(
            LmsLibraryItemType.ALBUM,
            LmsLibraryItemType.GENRE,
            LmsLibraryItemType.PLAYLIST,
            LmsLibraryItemType.TRACK
        ).forEach { type ->
            viewModel.searchPinCandidates(type, "test")
            advanceUntilIdle()
            assertEquals(type, viewModel.state.value.pinCandidateType)
        }

        gateway.pageError = IllegalStateException("search failed")
        viewModel.searchPinCandidates(LmsLibraryItemType.ALBUM, "broken")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.pinSearchError?.contains("Could not search") == true)
        gateway.pageError = null

        viewModel.setSection(
            HomeSection(HomeSectionType.RECENTLY_ADDED_ARTISTS, enabled = true, limit = 20)
        )
        viewModel.setSection(
            HomeSection(HomeSectionType.MOST_PLAYED_ARTISTS, enabled = true, limit = 20)
        )
        viewModel.setSection(
            HomeSection(HomeSectionType.RECENTLY_PLAYED_ARTISTS, enabled = true, limit = 20)
        )
        advanceUntilIdle()
        assertEquals(
            LmsLibraryQuery.ARTISTS,
            gateway.requestedQueries[HomeSectionType.RECENTLY_ADDED_ARTISTS]
        )

        viewModel.addPin(
            HomePin.special(HomeSpecialAction.SHUFFLE_TRACKS, "Shuffle")
        )
        viewModel.addPin(
            HomePin.special(HomeSpecialAction.SHUFFLE_TRACKS, "Shuffle")
        )
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.preferences.pins.size)

        viewModel.executeSpecialAction(HomeSpecialAction.SHUFFLE_TRACKS)
        advanceUntilIdle()
        assertEquals(LmsShuffleScope.TRACKS, gateway.shuffleScope)

        preferences.observeError = IllegalStateException("read failed")
        preferences.setError = IllegalStateException("write failed")
        store.clear()
        advanceUntilIdle()
        store.select(
            LmsActivePlayer(
                connection = LmsServerConnection("lms.local"),
                playerId = "player-1",
                playerName = "Living Room"
            )
        )
        advanceUntilIdle()
    }

    @Test
    fun handlesInvalidServerEndpointAndSavesErrors() = runTest {
        val store = LmsActivePlayerStore()
        val preferences = FakeHomePreferencesRepository()
        preferences.setError = IllegalStateException("write failed")
        val gateway = FakeHomeLibraryGateway()
        val viewModel = HomeViewModel(gateway, store, preferences)

        store.select(
            LmsActivePlayer(
                connection = LmsServerConnection(host = "", port = 9000),
                playerId = "player-1",
                playerName = "Living Room"
            )
        )
        advanceUntilIdle()
        assertTrue(viewModel.state.value.actionMessage?.contains("Could not identify") == true)

        store.select(
            LmsActivePlayer(
                connection = LmsServerConnection(host = "lms.local"),
                playerId = "player-1",
                playerName = "Living Room"
            )
        )
        advanceUntilIdle()
        viewModel.setDensity(HomeDensity.SPACIOUS)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.actionMessage?.contains("Could not save") == true)
    }

    @Test
    fun factoryCreatesAndRejectsUnsupportedTypes() {
        val gateway = FakeHomeLibraryGateway()
        val store = LmsActivePlayerStore()
        val preferences = FakeHomePreferencesRepository()
        val factory = HomeViewModel.Factory(gateway, store, preferences)

        assertNotNull(factory.create(HomeViewModel::class.java))
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(UnsupportedHomeViewModel::class.java)
        }
    }

    private class UnsupportedHomeViewModel : ViewModel()

    private class FakeHomePreferencesRepository(
        private val initial: HomePreferences = HomePreferences()
    ) : HomePreferencesRepository {
        private val value = MutableStateFlow(initial)
        var observeError: Exception? = null
        var setError: Exception? = null

        override fun observe(endpointKey: String) =
            observeError?.let { error ->
                flow {
                    throw error
                }
            } ?: value

        override suspend fun setPreferences(
            endpointKey: String,
            preferences: HomePreferences
        ) {
            setError?.let { throw it }
            value.value = preferences
        }

        override suspend fun reset(endpointKey: String) {
            value.value = HomePreferences()
        }
    }

    private class FakeHomeLibraryGateway : LmsLibraryGateway {
        val requestedQueries = mutableMapOf<HomeSectionType, LmsLibraryQuery>()
        val requestedSorts = mutableMapOf<HomeSectionType, LmsLibrarySort?>()
        var shuffleScope: LmsShuffleScope? = null
        var shuffleError: Exception? = null
        var pageError: Exception? = null
        var searchError: Exception? = null
        var artistArtworkUrl: String? = "/contributor/1/image"

        override suspend fun getPage(
            connection: LmsServerConnection,
            query: LmsLibraryQuery,
            offset: Int,
            limit: Int,
            filter: LmsLibraryFilter,
            search: String?,
            sort: LmsLibrarySort?
        ): LmsLibraryPage {
            pageError?.let { throw it }
            val section = when (sort) {
                LmsLibrarySort.NEW -> if (query == LmsLibraryQuery.ALBUMS) {
                    HomeSectionType.RECENTLY_ADDED_ALBUMS
                } else {
                    HomeSectionType.RECENTLY_ADDED_ARTISTS
                }
                LmsLibrarySort.PLAY_COUNT -> if (query == LmsLibraryQuery.ALBUMS) {
                    HomeSectionType.MOST_PLAYED_ALBUMS
                } else {
                    HomeSectionType.MOST_PLAYED_ARTISTS
                }
                LmsLibrarySort.RECENTLY_PLAYED -> if (query == LmsLibraryQuery.ALBUMS) {
                    HomeSectionType.RECENTLY_PLAYED_ALBUMS
                } else {
                    HomeSectionType.RECENTLY_PLAYED_ARTISTS
                }
                else -> null
            }
            section?.let {
                requestedQueries[it] = query
                requestedSorts[it] = sort
            }
            val itemType = when (query) {
                LmsLibraryQuery.ARTISTS -> LmsLibraryItemType.ARTIST
                LmsLibraryQuery.ALBUMS -> LmsLibraryItemType.ALBUM
                LmsLibraryQuery.GENRES -> LmsLibraryItemType.GENRE
                LmsLibraryQuery.TRACKS,
                LmsLibraryQuery.PLAYLIST_TRACKS,
                LmsLibraryQuery.FAVORITES -> LmsLibraryItemType.TRACK
                LmsLibraryQuery.PLAYLISTS -> LmsLibraryItemType.PLAYLIST
            }
            val items = listOf(
                LmsLibraryItem(
                    id = "1",
                    type = itemType,
                    title = "Test item",
                    artist = "Test artist"
                )
            )
            return LmsLibraryPage(items, 0, items.size)
        }

        override suspend fun search(
            connection: LmsServerConnection,
            term: String,
            offset: Int,
            limit: Int
        ): LmsLibrarySearchResults {
            searchError?.let { throw it }
            val page = LmsLibraryPage(emptyList(), 0, 0)
            return LmsLibrarySearchResults(page, page, page)
        }

        override suspend fun getArtistArtworkUrl(
            connection: LmsServerConnection,
            artistId: String
        ): String? = artistArtworkUrl

        override suspend fun playNow(
            connection: LmsServerConnection,
            playerId: String,
            item: LmsLibraryItem
        ) = Unit

        override suspend fun shuffleLibrary(
            connection: LmsServerConnection,
            playerId: String,
            scope: LmsShuffleScope
        ) {
            shuffleError?.let { throw it }
            shuffleScope = scope
        }
    }
}
