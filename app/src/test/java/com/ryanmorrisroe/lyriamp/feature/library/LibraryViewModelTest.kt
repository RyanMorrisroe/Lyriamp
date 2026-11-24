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
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibrarySort
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibrarySortOrder
import com.ryanmorrisroe.lyriamp.data.persistence.LibrarySortPreference
import com.ryanmorrisroe.lyriamp.data.persistence.LibraryViewType
import com.ryanmorrisroe.lyriamp.data.persistence.LastSessionScrollAnchor
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import com.ryanmorrisroe.lyriamp.data.persistence.Appearance
import com.ryanmorrisroe.lyriamp.data.persistence.DisplayPreferences
import com.ryanmorrisroe.lyriamp.data.persistence.DisplayPreferencesRepository
import com.ryanmorrisroe.lyriamp.data.persistence.LibraryLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @Test
    fun libraryItemKeysAreUniqueWhenLmsReturnsDuplicateIds() {
        val artist = LmsLibraryItem("8115", LmsLibraryItemType.ARTIST, "Artist")
        val duplicateArtist = artist.copy(title = "Duplicate")

        assertEquals("ARTIST:8115", libraryItemKey(artist, 0, listOf(artist)))
        assertNotEquals(
            libraryItemKey(artist, 0, listOf(artist, duplicateArtist)),
            libraryItemKey(duplicateArtist, 1, listOf(artist, duplicateArtist))
        )
    }

    @Test
    fun tabsPreserveDeepLocationsAndAnchorsAcrossSwitchesAndRestore() {
        runLibraryViewModelTest {
            val viewModel = LibraryViewModel(FakeLibraryGateway(), LmsActivePlayerStore())
            viewModel.selectTab(LibraryTab.ARTISTS)
            viewModel.openItem(LmsLibraryItem("artist-1", LmsLibraryItemType.ARTIST, "Artist"))
            advanceUntilIdle()
            val artistLocations = viewModel.uiState.value.locationStack
            val artistAnchor = LastSessionScrollAnchor(libraryLocationKey(artistLocations), "", "album-9", 9, 12)
            viewModel.updateScrollAnchor(artistAnchor)

            viewModel.selectTab(LibraryTab.SONGS)
            advanceUntilIdle()
            assertEquals(LmsLibraryQuery.TRACKS, viewModel.uiState.value.location.query)
            val songLocations = viewModel.uiState.value.locationStack
            val songAnchor = LastSessionScrollAnchor(libraryLocationKey(songLocations), "", "song-90000", 90000, 2)
            viewModel.updateScrollAnchor(songAnchor)
            viewModel.selectTab(LibraryTab.HOME)
            advanceUntilIdle()
            assertEquals(LibraryTab.SONGS, viewModel.uiState.value.lastVisitedTab)

            val restored = LibraryViewModel(FakeLibraryGateway(), LmsActivePlayerStore())
            restored.restoreSession(
                locationStack = listOf(LibraryLocation("Library")),
                searchText = "",
                scrollAnchor = null,
                activeTab = LibraryTab.HOME.name,
                lastVisitedTab = viewModel.lastVisitedTab()?.name,
                tabs = viewModel.savedTabs()
            )
            restored.selectTab(LibraryTab.ARTISTS)
            advanceUntilIdle()
            assertEquals(artistLocations, restored.uiState.value.locationStack)
            assertEquals(artistAnchor, restored.uiState.value.scrollAnchor)
            restored.selectTab(LibraryTab.SONGS)
            advanceUntilIdle()
            assertEquals(songLocations, restored.uiState.value.locationStack)
            assertEquals(songAnchor, restored.uiState.value.scrollAnchor)
        }
    }

    @Test
    fun albumTrackNavigationCreatesTheDedicatedPagingSourceAndKeepsLocationMatching() {
        runLibraryViewModelTest {
            val gateway = FakeLibraryGateway()
            val playerStore = LmsActivePlayerStore().apply { select(activePlayer()) }
            val viewModel = LibraryViewModel(gateway, playerStore)

            viewModel.selectTab(LibraryTab.ALBUMS)
            viewModel.openItem(LmsLibraryItem("album-1", LmsLibraryItemType.ALBUM, "Album"))
            advanceUntilIdle()
            val locations = viewModel.uiState.value.locationStack
            assertTrue(viewModel.isCurrentLocation(locations, ""))
            viewModel.updateScrollAnchor(
                LastSessionScrollAnchor(libraryLocationKey(locations), "", "track-1", 1, 0)
            )
            viewModel.items.first()
            advanceUntilIdle()

            viewModel.navigateBack()
            advanceUntilIdle()
            assertEquals(LibraryTab.ALBUMS, viewModel.currentTab())
        }
    }

    @Test
    fun openMetadataItemHandlesUnsupportedNoPlayerAndLookupFailure() {
        runLibraryViewModelTest {
            val gateway = FakeLibraryGateway()
            val noPlayerViewModel = LibraryViewModel(gateway, LmsActivePlayerStore())
            assertThrows(IllegalArgumentException::class.java) {
                noPlayerViewModel.openMetadataItem(LmsLibraryItemType.TRACK, "Track")
            }
            noPlayerViewModel.openMetadataItem(LmsLibraryItemType.ARTIST, "Artist")
            advanceUntilIdle()
            assertEquals("Artist", noPlayerViewModel.uiState.value.searchText)

            gateway.pageError = IllegalStateException("offline")
            val playerStore = LmsActivePlayerStore().apply { select(activePlayer()) }
            val playerViewModel = LibraryViewModel(gateway, playerStore)
            playerViewModel.openMetadataItem(LmsLibraryItemType.ALBUM, "Album")
            advanceUntilIdle()
            assertEquals("Album", playerViewModel.uiState.value.searchText)
            assertTrue(
                playerViewModel.uiState.value.message?.contains("Could not verify") == true
            )
        }
    }

    @Test
    fun searchDoesNotOverwriteBrowseAnchorAndResetDropsOtherServerState() {
        runLibraryViewModelTest {
            val viewModel = LibraryViewModel(FakeLibraryGateway(), LmsActivePlayerStore())
            viewModel.selectTab(LibraryTab.ALBUMS)
            advanceUntilIdle()
            val locations = viewModel.uiState.value.locationStack
            val browse = LastSessionScrollAnchor(libraryLocationKey(locations), "", "album-90", 90, 0)
            viewModel.updateScrollAnchor(browse)
            viewModel.setSearchText("blue")
            viewModel.updateScrollAnchor(browse.copy(searchText = "blue", index = 3))
            viewModel.selectTab(LibraryTab.HOME)
            viewModel.selectTab(LibraryTab.ALBUMS)
            advanceUntilIdle()
            assertEquals(browse, viewModel.uiState.value.scrollAnchor)

            viewModel.resetToRoot()
            viewModel.selectTab(LibraryTab.ALBUMS)
            advanceUntilIdle()
            assertEquals(null, viewModel.uiState.value.scrollAnchor)
        }
    }

    @Test
    fun browsePagingLoadsDeepSongPageWithoutEarlierPages() = runTest {
        val gateway = FakeLibraryGateway().apply {
            page = LmsLibraryPage(
                listOf(LmsLibraryItem("song-90000", LmsLibraryItemType.TRACK, "Song")),
                90000,
                100001
            )
        }
        val source = LmsLibraryPagingSource(gateway, activePlayer(), LmsLibraryQuery.TRACKS, LmsLibraryFilter())
        val page = source.load(PagingSource.LoadParams.Refresh(90000, 30, true)) as PagingSource.LoadResult.Page
        assertEquals(90000, gateway.requestedOffset)
        assertEquals(30, gateway.requestedLimit)
        assertEquals(90000, page.itemsBefore)
        assertEquals(90001, page.nextKey)
    }

    @Test
    fun browsePagingClampsRestoredOffsetWhenLibraryShrinks() = runTest {
        val gateway = FakeLibraryGateway().apply {
            page = LmsLibraryPage(
                listOf(LmsLibraryItem("last", LmsLibraryItemType.TRACK, "Last song")),
                0,
                10
            )
        }
        val source = LmsLibraryPagingSource(gateway, activePlayer(), LmsLibraryQuery.TRACKS, LmsLibraryFilter())
        val result = source.load(PagingSource.LoadParams.Refresh(90000, 30, true)) as PagingSource.LoadResult.Page
        assertEquals(9, gateway.requestedOffset)
        assertEquals(9, result.itemsBefore)
        assertEquals(0, result.itemsAfter)
        assertEquals(null, result.nextKey)
    }

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
    fun restoreSessionReturnsToHomeWhenPlaylistBrowseWasSaved() {
        runLibraryViewModelTest {
            val viewModel = LibraryViewModel(FakeLibraryGateway(), LmsActivePlayerStore())

            viewModel.restoreSession(
                locationStack = listOf(
                    LibraryLocation("Library"),
                    LibraryLocation("Playlists", LmsLibraryQuery.PLAYLISTS)
                ),
                searchText = "",
                scrollAnchor = null,
                activeTab = LibraryTab.PLAYLISTS.name,
                lastVisitedTab = LibraryTab.PLAYLISTS.name
            )
            advanceUntilIdle()

            assertEquals(
                listOf(LibraryLocation("Library")),
                viewModel.uiState.value.locationStack
            )
            assertEquals(LibraryTab.HOME, viewModel.currentTab())
            assertEquals(null, viewModel.lastVisitedTab())
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
    fun metadataNavigationUsesUniqueExactArtistAndAlbumMatches() {
        runLibraryViewModelTest {
            val gateway = FakeLibraryGateway()
            val store = LmsActivePlayerStore().apply { select(activePlayer()) }
            val viewModel = LibraryViewModel(gateway, store)

            gateway.page = LmsLibraryPage(
                listOf(LmsLibraryItem("artist-1", LmsLibraryItemType.ARTIST, "Arlo Parks")),
                0,
                1
            )
            viewModel.openMetadataItem(LmsLibraryItemType.ARTIST, "Arlo Parks")
            advanceUntilIdle()

            assertEquals(LmsLibraryQuery.ARTISTS, gateway.requestedQuery)
            assertEquals("Arlo Parks", gateway.requestedSearch)
            assertEquals(LmsLibraryQuery.ALBUMS, viewModel.uiState.value.location.query)
            assertEquals("artist-1", viewModel.uiState.value.location.filter.artistId)

            gateway.page = LmsLibraryPage(
                listOf(LmsLibraryItem("album-1", LmsLibraryItemType.ALBUM, "Collapsed in Sunbeams")),
                0,
                1
            )
            viewModel.openMetadataItem(LmsLibraryItemType.ALBUM, "Collapsed in Sunbeams")
            advanceUntilIdle()

            assertEquals(LmsLibraryQuery.ALBUMS, gateway.requestedQuery)
            assertEquals(LmsLibraryQuery.TRACKS, viewModel.uiState.value.location.query)
            assertEquals("album-1", viewModel.uiState.value.location.filter.albumId)
        }
    }

    @Test
    fun metadataNavigationFallsBackToGlobalSearchForMissingOrAmbiguousMatches() {
        runLibraryViewModelTest {
            val gateway = FakeLibraryGateway()
            val store = LmsActivePlayerStore().apply { select(activePlayer()) }
            val viewModel = LibraryViewModel(gateway, store)
            gateway.page = LmsLibraryPage(emptyList(), 0, 0)

            viewModel.openMetadataItem(LmsLibraryItemType.ARTIST, "Unknown Artist")
            advanceUntilIdle()

            assertEquals("Unknown Artist", viewModel.uiState.value.searchText)
            assertTrue(viewModel.uiState.value.isAtRoot)

            gateway.page = LmsLibraryPage(
                listOf(LmsLibraryItem("album-1", LmsLibraryItemType.ALBUM, "Blue")),
                0,
                2
            )
            viewModel.openMetadataItem(LmsLibraryItemType.ALBUM, "Blue")
            advanceUntilIdle()

            assertEquals("Blue", viewModel.uiState.value.searchText)
            assertTrue(viewModel.uiState.value.isAtRoot)
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
    fun albumTrackPagingSortsByDiscThenTrack() = runTest {
        val gateway = FakeLibraryGateway().apply {
            page = LmsLibraryPage(
                items = listOf(
                    LmsLibraryItem(
                        "22", LmsLibraryItemType.TRACK, "Disc 2 Track 2",
                        discNumber = 2, trackNumber = 2
                    ),
                    LmsLibraryItem(
                        "12", LmsLibraryItemType.TRACK, "Disc 1 Track 2",
                        discNumber = 1, trackNumber = 2
                    ),
                    LmsLibraryItem(
                        "21", LmsLibraryItemType.TRACK, "Disc 2 Track 1",
                        discNumber = 2, trackNumber = 1
                    ),
                    LmsLibraryItem(
                        "11", LmsLibraryItemType.TRACK, "Disc 1 Track 1",
                        discNumber = 1, trackNumber = 1
                    )
                ),
                offset = 0,
                totalCount = 4
            )
        }
        val source = LmsAlbumTracksPagingSource(
            gateway,
            activePlayer(),
            LmsLibraryFilter(albumId = "42")
        )

        val result = source.load(PagingSource.LoadParams.Refresh(null, 30, false))

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(listOf("11", "12", "21", "22"), page.data.map { it.id })
        assertEquals(0, page.itemsBefore)
        assertEquals(0, page.itemsAfter)
        assertEquals(100, gateway.requestedLimit)
    }

    @Test
    fun albumTrackPagingFallsBackWithoutDiscOrTrackMetadata() = runTest {
        val gateway = FakeLibraryGateway().apply {
            page = LmsLibraryPage(
                items = listOf(
                    LmsLibraryItem("unknown", LmsLibraryItemType.TRACK, "Unknown"),
                    LmsLibraryItem(
                        "12",
                        LmsLibraryItemType.TRACK,
                        "Disc 1 Track 2",
                        discNumber = 1,
                        trackNumber = 2
                    ),
                    LmsLibraryItem(
                        "11",
                        LmsLibraryItemType.TRACK,
                        "Disc 1 Track 1",
                        discNumber = 1,
                        trackNumber = 1
                    )
                ),
                offset = 0,
                totalCount = 3
            )
        }
        val source = LmsAlbumTracksPagingSource(
            gateway,
            activePlayer(),
            LmsLibraryFilter(albumId = "42")
        )

        val result = source.load(PagingSource.LoadParams.Refresh(null, 30, false))

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(listOf("11", "12", "unknown"), page.data.map { it.id })
    }

    @Test
    fun descendingAlbumPagingRequestsAndReversesLmsPages() = runTest {
        val gateway = FakeLibraryGateway().apply {
            pageHandler = { offset, limit ->
                val items = when (offset) {
                    0 -> if (limit == 1) {
                        listOf(LmsLibraryItem("A", LmsLibraryItemType.ALBUM, "A"))
                    } else {
                        listOf(
                            LmsLibraryItem("A", LmsLibraryItemType.ALBUM, "A"),
                            LmsLibraryItem("B", LmsLibraryItemType.ALBUM, "B")
                        )
                    }
                    3 -> listOf(
                        LmsLibraryItem("D", LmsLibraryItemType.ALBUM, "D"),
                        LmsLibraryItem("E", LmsLibraryItemType.ALBUM, "E")
                    )
                    1 -> listOf(
                        LmsLibraryItem("B", LmsLibraryItemType.ALBUM, "B"),
                        LmsLibraryItem("C", LmsLibraryItemType.ALBUM, "C")
                    )
                    else -> emptyList()
                }
                LmsLibraryPage(items, offset, 5)
            }
        }
        val source = LmsLibraryPagingSource(
            gateway,
            activePlayer(),
            LmsLibraryQuery.ALBUMS,
            LmsLibraryFilter(),
            LibrarySortPreference(LmsLibrarySort.ALBUM, LmsLibrarySortOrder.DESCENDING)
        )

        val first = source.load(PagingSource.LoadParams.Refresh(null, 2, false))
        assertTrue(first is PagingSource.LoadResult.Page)
        val firstPage = first as PagingSource.LoadResult.Page
        assertEquals(listOf("E", "D"), firstPage.data.map { it.id })
        assertEquals(0, firstPage.itemsBefore)
        assertEquals(3, firstPage.itemsAfter)
        assertEquals(2, firstPage.nextKey)
        assertEquals(LmsLibrarySort.ALBUM, gateway.requestedSort)
        assertEquals(3, gateway.requestedOffset)

        val second = source.load(PagingSource.LoadParams.Append(2, 2, false))
        assertTrue(second is PagingSource.LoadResult.Page)
        val secondPage = second as PagingSource.LoadResult.Page
        assertEquals(listOf("C", "B"), secondPage.data.map { it.id })
        assertEquals(2, secondPage.itemsBefore)
        assertEquals(1, secondPage.itemsAfter)
        assertEquals(4, secondPage.nextKey)
    }

    @Test
    fun ascendingNewAlbumPagingReversesNaturallyDescendingLmsResults() = runTest {
        val gateway = FakeLibraryGateway().apply {
            pageHandler = { offset, limit ->
                val items = if (offset == 3 && limit == 2) {
                    listOf(
                        LmsLibraryItem("D", LmsLibraryItemType.ALBUM, "D"),
                        LmsLibraryItem("E", LmsLibraryItemType.ALBUM, "E")
                    )
                } else if (offset == 0 && limit == 1) {
                    listOf(LmsLibraryItem("A", LmsLibraryItemType.ALBUM, "A"))
                } else {
                    emptyList()
                }
                LmsLibraryPage(items, offset, 5)
            }
        }
        val source = LmsLibraryPagingSource(
            gateway,
            activePlayer(),
            LmsLibraryQuery.ALBUMS,
            LmsLibraryFilter(),
            LibrarySortPreference(LmsLibrarySort.NEW, LmsLibrarySortOrder.ASCENDING)
        )

        val result = source.load(PagingSource.LoadParams.Refresh(null, 2, false))

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(listOf("E", "D"), page.data.map { it.id })
        assertEquals(2, page.nextKey)
        assertEquals(3, gateway.requestedOffset)
        assertEquals(LmsLibrarySort.NEW, gateway.requestedSort)
    }

    @Test
    fun reversedPagingStopsForAnEmptySortedCollection() = runTest {
        val gateway = FakeLibraryGateway().apply {
            pageHandler = { offset, limit ->
                LmsLibraryPage(emptyList(), offset, 0)
            }
        }
        val source = LmsLibraryPagingSource(
            gateway,
            activePlayer(),
            LmsLibraryQuery.ALBUMS,
            LmsLibraryFilter(),
            LibrarySortPreference(LmsLibrarySort.NEW, LmsLibrarySortOrder.ASCENDING)
        )

        val result = source.load(PagingSource.LoadParams.Refresh(null, 30, false))

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertTrue(page.data.isEmpty())
        assertEquals(null, page.nextKey)
        assertEquals(0, page.itemsBefore)
        assertEquals(0, page.itemsAfter)
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
        assertEquals(0, source.getRefreshKey(pagingState(prevKey = 10, nextKey = 30)))
        assertEquals(0, source.getRefreshKey(pagingState(prevKey = null, nextKey = 30)))

        val searchSource = LmsLibrarySearchPagingSource(gateway, activePlayer(), "query")
        assertEquals(0, searchSource.getRefreshKey(pagingState(prevKey = 10, nextKey = 30)))
        assertEquals(0, searchSource.getRefreshKey(pagingState(prevKey = null, nextKey = 30)))
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
    fun resolvesPinnedAlbumArtworkFromTheAlbumPage() {
        runLibraryViewModelTest {
            val gateway = FakeLibraryGateway().apply {
                page = LmsLibraryPage(
                    listOf(
                        LmsLibraryItem(
                            id = "42",
                            type = LmsLibraryItemType.ALBUM,
                            title = "Blue Train",
                            artworkUrl = "/music/42/cover.jpg"
                        )
                    ),
                    0,
                    1
                )
            }
            val viewModel = LibraryViewModel(gateway, LmsActivePlayerStore())

            assertEquals(
                "/music/42/cover.jpg",
                viewModel.resolvePinnedArtwork(
                    LmsServerConnection("lms.local"),
                    LmsLibraryItem(
                        id = "42",
                        type = LmsLibraryItemType.ALBUM,
                        title = "Blue Train"
                    )
                )
            )
            advanceUntilIdle()
        }
    }

    @Test
    fun cachesResolvedArtistArtworkForRowsRevisitedAfterScrolling() {
        runLibraryViewModelTest {
            val gateway = FakeLibraryGateway().apply {
                artistArtworkUrl = "/music/cover-42/cover.jpg"
            }
            val viewModel = LibraryViewModel(gateway, LmsActivePlayerStore())
            val connection = LmsServerConnection("lms.local")
            val nativeArtist = LmsLibraryItem(
                "42",
                LmsLibraryItemType.ARTIST,
                "Artist",
                artworkUrl = "/contributor/portrait-42/image"
            )
            val noArtArtist = LmsLibraryItem("43", LmsLibraryItemType.ARTIST, "Artist 43")

            assertEquals(
                "/contributor/portrait-42/image",
                viewModel.resolveArtistArtwork(connection, nativeArtist)
            )
            assertEquals(
                "/contributor/portrait-42/image",
                viewModel.resolveArtistArtwork(connection, nativeArtist.copy(artworkUrl = null))
            )
            assertEquals(0, gateway.artistArtworkRequestCount)

            assertEquals("/music/cover-42/cover.jpg", viewModel.resolveArtistArtwork(connection, noArtArtist))
            assertEquals("/music/cover-42/cover.jpg", viewModel.resolveArtistArtwork(connection, noArtArtist))
            assertEquals(1, gateway.artistArtworkRequestCount)

            viewModel.resolveArtistArtwork(connection.copy(host = "other.local"), noArtArtist)
            assertEquals(2, gateway.artistArtworkRequestCount)
            advanceUntilIdle()
        }
    }

    @Test
    fun libraryViewModelObservesSavedSortPreferences() {
        runLibraryViewModelTest {
            val repository = FakeDisplayPreferencesRepository()
            val viewModel = LibraryViewModel(
                FakeLibraryGateway(),
                LmsActivePlayerStore(),
                repository
            )

            repository.setSort(
                LibraryViewType.ALBUMS,
                LibrarySortPreference(LmsLibrarySort.YEAR_ALBUM, LmsLibrarySortOrder.DESCENDING)
            )
            advanceUntilIdle()
            assertEquals(
                LibrarySortPreference(LmsLibrarySort.YEAR_ALBUM, LmsLibrarySortOrder.DESCENDING),
                repository.observe().first().sortFor(LibraryViewType.ALBUMS)
            )

            repository.setSort(
                LibraryViewType.SONGS,
                LibrarySortPreference(LmsLibrarySort.TRACK_NUMBER)
            )
            advanceUntilIdle()
            assertEquals(
                LibrarySortPreference(LmsLibrarySort.TRACK_NUMBER),
                repository.observe().first().sortFor(LibraryViewType.SONGS)
            )
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

    private class FakeDisplayPreferencesRepository : DisplayPreferencesRepository {
        private val value = MutableStateFlow(DisplayPreferences())

        override fun observe() = value

        override suspend fun setAppearance(appearance: Appearance) {
            value.update { it.copy(appearance = appearance) }
        }

        override suspend fun setLayout(type: LibraryViewType, layout: LibraryLayout) {
            value.update { it.copy(layouts = it.layouts + (type to layout)) }
        }

        override suspend fun setSort(type: LibraryViewType, preference: LibrarySortPreference) {
            value.update { it.copy(sorts = it.sorts + (type to preference)) }
        }
    }

    private class FakeLibraryGateway : LmsLibraryGateway {
        var page = LmsLibraryPage(emptyList(), 0, 0)
        var pageHandler: (suspend (Int, Int) -> LmsLibraryPage)? = null
        var searchResults = LmsLibrarySearchResults(page, page, page)
        var searchHandler: suspend (Int, Int) -> LmsLibrarySearchResults = { _, _ -> searchResults }
        val searchRequests = mutableListOf<Pair<Int, Int>>()
        var requestedOffset: Int? = null
        var requestedLimit: Int? = null
        var playedItem: LmsLibraryItem? = null
        var playedPlayerId: String? = null
        var artistArtworkUrl: String? = null
        var artistArtworkRequestCount = 0
        var requestedQuery: LmsLibraryQuery? = null
        var requestedSearch: String? = null
        var requestedSort: LmsLibrarySort? = null
        var pageError: Exception? = null
        var playNowError: Exception? = null

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
            requestedQuery = query
            requestedSearch = search
            requestedSort = sort
            requestedOffset = offset
            requestedLimit = limit
            return pageHandler?.invoke(offset, limit) ?: page.copy(offset = offset)
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

        override suspend fun getArtistArtworkUrl(
            connection: LmsServerConnection,
            artistId: String
        ): String? {
            artistArtworkRequestCount++
            return artistArtworkUrl
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