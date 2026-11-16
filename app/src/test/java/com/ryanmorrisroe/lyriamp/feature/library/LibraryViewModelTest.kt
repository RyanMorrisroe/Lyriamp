package com.ryanmorrisroe.lyriamp.feature.library

import androidx.paging.PagingSource
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.lifecycle.ViewModel
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayerStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryFilter
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItemType
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryPage
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryQuery
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibrarySearchResults
import com.ryanmorrisroe.lyriamp.data.persistence.LastSessionScrollAnchor
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @Test
    fun categoryNavigationBuildsAndTrimsTheBrowsePath() {
        runLibraryViewModelTest {
            val viewModel = LibraryViewModel(FakeLibraryGateway(), LmsActivePlayerStore())

            viewModel.openCategory(libraryCategories.first())
            viewModel.openItem(LmsLibraryItem("artist-1", LmsLibraryItemType.ARTIST, "Artist"))
            advanceUntilIdle()

            assertEquals(3, viewModel.uiState.value.locationStack.size)
            assertEquals(LmsLibraryQuery.ALBUMS, viewModel.uiState.value.location.query)
            assertEquals("artist-1", viewModel.uiState.value.location.filter.artistId)

            viewModel.navigateBack()
            advanceUntilIdle()

            assertEquals("Artists", viewModel.uiState.value.location.title)
            viewModel.navigateToBreadcrumb(0)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isAtRoot)
        }
    }

    @Test
    fun restoreSessionRestoresBrowsePathSearchAndScrollAnchor() {
        runLibraryViewModelTest {
            val viewModel = LibraryViewModel(FakeLibraryGateway(), LmsActivePlayerStore())
            val locations = listOf(
                LibraryLocation("Library"),
                LibraryLocation("Artists", LmsLibraryQuery.ARTISTS),
                LibraryLocation("Artist", LmsLibraryQuery.ALBUMS, LmsLibraryFilter(artistId = "a1"))
            )
            val anchor = LastSessionScrollAnchor(
                libraryLocationKey(locations),
                "blue",
                "album-42",
                42,
                18
            )

            viewModel.restoreSession(locations, "blue", anchor)
            advanceUntilIdle()

            assertEquals(locations, viewModel.uiState.value.locationStack)
            assertEquals("blue", viewModel.uiState.value.searchText)
            assertEquals(anchor, viewModel.uiState.value.scrollAnchor)
        }
    }

    @Test
    fun restoreSessionAddsLibraryRootAndResetClearsBrowseState() {
        runLibraryViewModelTest {
            val viewModel = LibraryViewModel(FakeLibraryGateway(), LmsActivePlayerStore())
            val restoredLocation = LibraryLocation("Albums", LmsLibraryQuery.ALBUMS)

            viewModel.restoreSession(listOf(restoredLocation), "  query  ", null)
            advanceUntilIdle()
            assertEquals(listOf(LibraryLocation("Library"), restoredLocation), viewModel.uiState.value.locationStack)
            assertEquals("  query  ", viewModel.uiState.value.searchText)

            viewModel.resetToRoot()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isAtRoot)
            assertEquals("", viewModel.uiState.value.searchText)
            assertEquals(null, viewModel.uiState.value.scrollAnchor)
        }
    }

    @Test
    fun openItemMapsBrowsableTypesAndLeavesTracksAtCurrentLocation() {
        runLibraryViewModelTest {
            val viewModel = LibraryViewModel(FakeLibraryGateway(), LmsActivePlayerStore())
            val cases = listOf(
                LmsLibraryItem("artist-1", LmsLibraryItemType.ARTIST, "Artist") to
                    LibraryLocation("Artist", LmsLibraryQuery.ALBUMS, LmsLibraryFilter(artistId = "artist-1")),
                LmsLibraryItem("album-1", LmsLibraryItemType.ALBUM, "Album") to
                    LibraryLocation("Album", LmsLibraryQuery.TRACKS, LmsLibraryFilter(albumId = "album-1")),
                LmsLibraryItem("genre-1", LmsLibraryItemType.GENRE, "Genre") to
                    LibraryLocation("Genre", LmsLibraryQuery.ARTISTS, LmsLibraryFilter(genreId = "genre-1")),
                LmsLibraryItem("folder-1", LmsLibraryItemType.PLAYLIST, "Folder", isContainer = true) to
                    LibraryLocation("Folder", LmsLibraryQuery.PLAYLISTS, LmsLibraryFilter(folderId = "folder-1")),
                LmsLibraryItem("playlist-1", LmsLibraryItemType.PLAYLIST, "Playlist") to
                    LibraryLocation(
                        "Playlist",
                        LmsLibraryQuery.PLAYLIST_TRACKS,
                        LmsLibraryFilter(playlistId = "playlist-1")
                    )
            )

            cases.forEach { (item, expectedLocation) ->
                viewModel.resetToRoot()
                advanceUntilIdle()
                viewModel.openItem(item)
                advanceUntilIdle()
                assertEquals(expectedLocation, viewModel.uiState.value.location)
            }

            val stackBeforeTrack = viewModel.uiState.value.locationStack
            viewModel.openItem(LmsLibraryItem("track-1", LmsLibraryItemType.TRACK, "Track"))
            advanceUntilIdle()
            assertEquals(stackBeforeTrack, viewModel.uiState.value.locationStack)
        }
    }

    @Test
    fun itemsFlowReturnsForNoPlayerBrowseAndSearchRequests() {
        runLibraryViewModelTest {
            val noPlayerViewModel = LibraryViewModel(FakeLibraryGateway(), LmsActivePlayerStore())
            assertNotNull(noPlayerViewModel.items.first())

            val browseStore = LmsActivePlayerStore().apply { select(activePlayer()) }
            val browseViewModel = LibraryViewModel(FakeLibraryGateway(), browseStore)
            browseViewModel.openCategory(libraryCategories.first())
            assertNotNull(browseViewModel.items.first())

            val searchStore = LmsActivePlayerStore().apply { select(activePlayer()) }
            val searchViewModel = LibraryViewModel(FakeLibraryGateway(), searchStore)
            searchViewModel.setSearchText(" query ")
            assertNotNull(searchViewModel.items.first())
        }
    }

    @Test
    fun browsePagingUsesReturnedItemCountAsTheNextOffset() = runTest {
        val gateway = FakeLibraryGateway().apply {
            page = LmsLibraryPage(
                items = listOf(LmsLibraryItem("artist-1", LmsLibraryItemType.ARTIST, "Artist")),
                offset = 0,
                totalCount = 3
            )
        }
        val source = LmsLibraryPagingSource(
            gateway,
            activePlayer(),
            LmsLibraryQuery.ARTISTS,
            LmsLibraryFilter()
        )

        val result = source.load(PagingSource.LoadParams.Refresh(null, 30, false))

        assertTrue(result is PagingSource.LoadResult.Page)
        assertEquals(1, (result as PagingSource.LoadResult.Page).nextKey)
        assertEquals(0, gateway.requestedOffset)
        assertEquals(30, gateway.requestedLimit)
    }

    @Test
    fun searchPagingMapsGlobalOffsetsAcrossCategoryBoundaries() = runTest {
        val gateway = FakeLibraryGateway().apply {
            searchHandler = { offset, limit ->
                LmsLibrarySearchResults(
                    searchPage(LmsLibraryItemType.ARTIST, 2, offset, limit),
                    searchPage(LmsLibraryItemType.ALBUM, 1, offset, limit),
                    searchPage(LmsLibraryItemType.TRACK, 4, offset, limit)
                )
            }
        }
        val source = LmsLibrarySearchPagingSource(gateway, activePlayer(), "query")

        val result = source.load(PagingSource.LoadParams.Refresh(1, 3, false))

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(listOf("ARTIST-1", "ALBUM-0", "TRACK-0"), page.data.map { it.id })
        assertEquals(1, page.itemsBefore)
        assertEquals(3, page.itemsAfter)
        assertEquals(4, page.nextKey)
        assertEquals(listOf(0 to 3), gateway.searchRequests)
    }

    @Test
    fun searchPagingFetchesTheCategoryOffsetForDeepGlobalPositions() = runTest {
        val gateway = FakeLibraryGateway().apply {
            searchHandler = { offset, limit ->
                LmsLibrarySearchResults(
                    searchPage(LmsLibraryItemType.ARTIST, 2, offset, limit),
                    searchPage(LmsLibraryItemType.ALBUM, 1, offset, limit),
                    searchPage(LmsLibraryItemType.TRACK, 100, offset, limit)
                )
            }
        }
        val source = LmsLibrarySearchPagingSource(gateway, activePlayer(), "query")

        val result = source.load(PagingSource.LoadParams.Refresh(30, 2, false))

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(listOf("TRACK-27", "TRACK-28"), page.data.map { it.id })
        assertEquals(30, page.itemsBefore)
        assertEquals(listOf(0 to 2, 27 to 2), gateway.searchRequests)
    }

    @Test
    fun searchPagingFetchesAnUncachedAlbumPageAtItsCategoryOffset() = runTest {
        val gateway = FakeLibraryGateway().apply {
            searchHandler = { offset, limit ->
                LmsLibrarySearchResults(
                    searchPage(LmsLibraryItemType.ARTIST, 2, offset, limit),
                    searchPage(LmsLibraryItemType.ALBUM, 5, offset, limit),
                    searchPage(LmsLibraryItemType.TRACK, 1, offset, limit)
                )
            }
        }
        val source = LmsLibrarySearchPagingSource(gateway, activePlayer(), "query")

        val result = source.load(PagingSource.LoadParams.Refresh(3, 1, false))

        assertTrue(result is PagingSource.LoadResult.Page)
        assertEquals("ALBUM-1", (result as PagingSource.LoadResult.Page).data.single().id)
        assertEquals(listOf(0 to 1, 1 to 1), gateway.searchRequests)
    }

    @Test
    fun pagingSourcesClampRequestsAndReturnRefreshKeys() = runTest {
        val gateway = FakeLibraryGateway().apply {
            page = LmsLibraryPage(
                items = listOf(LmsLibraryItem("artist-1", LmsLibraryItemType.ARTIST, "Artist")),
                offset = 50,
                totalCount = 1_000
            )
        }
        val source = LmsLibraryPagingSource(
            gateway,
            activePlayer(),
            LmsLibraryQuery.ARTISTS,
            LmsLibraryFilter()
        )

        val result = source.load(PagingSource.LoadParams.Refresh(50, 150, false))

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(0, page.prevKey)
        assertEquals(51, page.nextKey)
        assertEquals(100, gateway.requestedLimit)
        assertEquals(20, source.getRefreshKey(pagingState(prevKey = 10, nextKey = 30)))
        assertEquals(20, source.getRefreshKey(pagingState(prevKey = null, nextKey = 30)))

        val searchSource = LmsLibrarySearchPagingSource(gateway, activePlayer(), "query")
        assertEquals(20, searchSource.getRefreshKey(pagingState(prevKey = 10, nextKey = 30)))
        assertEquals(20, searchSource.getRefreshKey(pagingState(prevKey = null, nextKey = 30)))
    }

    @Test
    fun pagingSourcesReturnErrorsAndStopAtEmptyResults() = runTest {
        val gateway = FakeLibraryGateway()
        val source = LmsLibraryPagingSource(
            gateway,
            activePlayer(),
            LmsLibraryQuery.ARTISTS,
            LmsLibraryFilter()
        )

        val emptyResult = source.load(PagingSource.LoadParams.Refresh(0, 30, false))
        assertTrue(emptyResult is PagingSource.LoadResult.Page)
        assertEquals(null, (emptyResult as PagingSource.LoadResult.Page).nextKey)

        gateway.pageError = IllegalStateException("offline")
        val failedResult = source.load(PagingSource.LoadParams.Refresh(0, 30, false))
        assertTrue(failedResult is PagingSource.LoadResult.Error)

        gateway.searchHandler = { _, _ -> throw IllegalStateException("offline") }
        val searchSource = LmsLibrarySearchPagingSource(gateway, activePlayer(), "query")
        assertTrue(
            searchSource.load(PagingSource.LoadParams.Refresh(null, 30, false)) is
                PagingSource.LoadResult.Error
        )
    }

    @Test
    fun playNowUsesTheActivePlayerAndReportsCompletion() {
        runLibraryViewModelTest {
            val playerStore = LmsActivePlayerStore().apply { select(activePlayer()) }
            val gateway = FakeLibraryGateway()
            val viewModel = LibraryViewModel(gateway, playerStore)

            viewModel.playNow(LmsLibraryItem("track-1", LmsLibraryItemType.TRACK, "Track"))
            advanceUntilIdle()

            assertEquals("track-1", gateway.playedItem?.id)
            assertEquals("player-1", gateway.playedPlayerId)
            assertEquals(1, viewModel.uiState.value.playbackRevision)
            assertEquals("Playing Track.", viewModel.uiState.value.message)
        }
    }

    @Test
    fun playNowHandlesMissingPlayerContainersAndGatewayFailure() {
        runLibraryViewModelTest {
            val playerStore = LmsActivePlayerStore()
            val gateway = FakeLibraryGateway()
            val viewModel = LibraryViewModel(gateway, playerStore)
            val track = LmsLibraryItem("track-1", LmsLibraryItemType.TRACK, "Track")

            viewModel.playNow(track)
            advanceUntilIdle()
            assertEquals("Select a connected player to play music.", viewModel.uiState.value.message)
            viewModel.clearMessage()
            advanceUntilIdle()
            assertEquals(null, viewModel.uiState.value.message)

            playerStore.select(activePlayer())
            advanceUntilIdle()
            viewModel.playNow(track.copy(type = LmsLibraryItemType.PLAYLIST, isContainer = true))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isPlayingItem)
            assertEquals(0, viewModel.uiState.value.playbackRevision)

            gateway.playNowError = IllegalStateException("offline")
            viewModel.playNow(track)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isPlayingItem)
            assertEquals("Could not play Track.", viewModel.uiState.value.message)
        }
    }

    @Test
    fun factoryCreatesLibraryViewModelAndRejectsUnsupportedTypes() {
        val factory = LibraryViewModel.Factory(FakeLibraryGateway(), LmsActivePlayerStore())

        assertNotNull(factory.create(LibraryViewModel::class.java))
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(UnsupportedViewModel::class.java)
        }
    }

    private class FakeLibraryGateway : LmsLibraryGateway {
        var page = LmsLibraryPage(emptyList(), 0, 0)
        var searchResults = LmsLibrarySearchResults(page, page, page)
        var searchHandler: suspend (Int, Int) -> LmsLibrarySearchResults = { _, _ -> searchResults }
        val searchRequests = mutableListOf<Pair<Int, Int>>()
        var requestedOffset: Int? = null
        var requestedLimit: Int? = null
        var playedItem: LmsLibraryItem? = null
        var playedPlayerId: String? = null
        var pageError: Exception? = null
        var playNowError: Exception? = null

        override suspend fun getPage(
            connection: LmsServerConnection,
            query: LmsLibraryQuery,
            offset: Int,
            limit: Int,
            filter: LmsLibraryFilter,
            search: String?
        ): LmsLibraryPage {
            pageError?.let { throw it }
            requestedOffset = offset
            requestedLimit = limit
            return page.copy(offset = offset)
        }

        override suspend fun search(
            connection: LmsServerConnection,
            term: String,
            offset: Int,
            limit: Int
        ): LmsLibrarySearchResults {
            searchRequests += offset to limit
            return searchHandler(offset, limit)
        }

        override suspend fun playNow(
            connection: LmsServerConnection,
            playerId: String,
            item: LmsLibraryItem
        ) {
            playNowError?.let { throw it }
            playedItem = item
            playedPlayerId = playerId
        }
    }

    private fun page(title: String, type: LmsLibraryItemType, id: Int, totalCount: Int) =
        LmsLibraryPage(listOf(LmsLibraryItem(id.toString(), type, title)), 0, totalCount)

    private fun searchPage(type: LmsLibraryItemType, totalCount: Int, offset: Int, limit: Int) =
        LmsLibraryPage(
            items = (offset until minOf(offset + limit, totalCount)).map { index ->
                LmsLibraryItem("${type.name}-$index", type, "${type.name}-$index")
            },
            offset = offset,
            totalCount = totalCount
        )

    private fun activePlayer() = LmsActivePlayer(
        LmsServerConnection("lms.local"),
        "player-1",
        "Living Room"
    )

    private fun pagingState(
        prevKey: Int?,
        nextKey: Int?
    ) = PagingState(
        pages = listOf(
            PagingSource.LoadResult.Page(
                data = listOf(LmsLibraryItem("item", LmsLibraryItemType.ARTIST, "Item")),
                prevKey = prevKey,
                nextKey = nextKey
            )
        ),
        anchorPosition = 0,
        config = PagingConfig(pageSize = 10),
        leadingPlaceholderCount = 0
    )

    private class UnsupportedViewModel : ViewModel()

    private fun runLibraryViewModelTest(block: suspend TestScope.() -> Unit) {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                block()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }
}