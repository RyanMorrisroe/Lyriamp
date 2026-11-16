package com.ryanmorrisroe.lyriamp.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryLocation(
    val title: String,
    val query: LmsLibraryQuery? = null,
    val filter: LmsLibraryFilter = LmsLibraryFilter()
)

data class LibraryCategory(
    val title: String,
    val query: LmsLibraryQuery
)

data class LibraryUiState(
    val locationStack: List<LibraryLocation> = listOf(LibraryLocation("Library")),
    val searchText: String = "",
    val activePlayer: LmsActivePlayer? = null,
    val isPlayingItem: Boolean = false,
    val message: String? = null,
    val playbackRevision: Int = 0,
    val scrollAnchor: LastSessionScrollAnchor? = null
) {
    val location: LibraryLocation
        get() = locationStack.last()

    val isAtRoot: Boolean
        get() = locationStack.size == 1
}

val libraryCategories = listOf(
    LibraryCategory("Artists", LmsLibraryQuery.ARTISTS),
    LibraryCategory("Albums", LmsLibraryQuery.ALBUMS),
    LibraryCategory("Genres", LmsLibraryQuery.GENRES),
    LibraryCategory("Playlists", LmsLibraryQuery.PLAYLISTS)
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(
    private val libraryGateway: LmsLibraryGateway,
    private val activePlayerStore: LmsActivePlayerStore
) : ViewModel() {
    private val mutableLocationStack = MutableStateFlow(listOf(LibraryLocation("Library")))
    private val mutableSearchText = MutableStateFlow("")
    private val mutableIsPlayingItem = MutableStateFlow(false)
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val mutablePlaybackRevision = MutableStateFlow(0)
    private val mutableRestoredScrollAnchor = MutableStateFlow<LastSessionScrollAnchor?>(null)
    private val debouncedSearch = mutableSearchText
        .map(String::trim)
        .debounce(SEARCH_DEBOUNCE_MILLIS)
        .distinctUntilChanged()
    private val locationState = combine(
        mutableLocationStack,
        mutableSearchText,
        activePlayerStore.activePlayer
    ) { locationStack, searchText, activePlayer ->
        Triple(locationStack, searchText, activePlayer)
    }
    private val playbackState = combine(
        mutableIsPlayingItem,
        mutableMessage,
        mutablePlaybackRevision
    ) { isPlayingItem, message, playbackRevision ->
        Triple(isPlayingItem, message, playbackRevision)
    }

    val uiState = combine(locationState, playbackState, mutableRestoredScrollAnchor) { location, playback, anchor ->
        LibraryUiState(
            locationStack = location.first,
            searchText = location.second,
            activePlayer = location.third,
            isPlayingItem = playback.first,
            message = playback.second,
            playbackRevision = playback.third,
            scrollAnchor = anchor
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LibraryUiState(activePlayer = activePlayerStore.activePlayer.value)
    )

    val items: Flow<PagingData<LmsLibraryItem>> = combine(
        mutableLocationStack,
        debouncedSearch,
        activePlayerStore.activePlayer,
        mutableRestoredScrollAnchor
    ) { locationStack, searchTerm, activePlayer, anchor ->
        val locationKey = libraryLocationKey(locationStack)
        val matchingAnchor = anchor?.takeIf {
            it.locationKey == locationKey && it.searchText == searchTerm
        }
        LibraryLoadRequest(
            locationStack.last(),
            searchTerm,
            activePlayer,
            matchingAnchor?.index
        )
    }.flatMapLatest { request ->
        val player = request.activePlayer
        when {
            player == null -> flowOf(PagingData.empty())
            request.searchTerm.isNotEmpty() -> Pager(pagingConfig, initialKey = request.initialKey) {
                LmsLibrarySearchPagingSource(libraryGateway, player, request.searchTerm)
            }.flow
            request.location.query != null -> {
                val query = request.location.query
                Pager(pagingConfig, initialKey = request.initialKey) {
                    LmsLibraryPagingSource(
                        libraryGateway,
                        player,
                        query,
                        request.location.filter
                    )
                }.flow
            }
            else -> flowOf(PagingData.empty())
        }
    }.cachedIn(viewModelScope)

    fun setSearchText(value: String) {
        mutableSearchText.value = value
    }

    fun restoreSession(
        locationStack: List<LibraryLocation>,
        searchText: String,
        scrollAnchor: LastSessionScrollAnchor?
    ) {
        mutableLocationStack.value = if (locationStack.firstOrNull()?.title == "Library") {
            locationStack
        } else {
            listOf(LibraryLocation("Library")) + locationStack
        }
        mutableSearchText.value = searchText
        mutableRestoredScrollAnchor.value = scrollAnchor
    }

    fun resetToRoot() {
        mutableLocationStack.value = listOf(LibraryLocation("Library"))
        mutableSearchText.value = ""
        mutableRestoredScrollAnchor.value = null
    }

    fun openCategory(category: LibraryCategory) {
        mutableSearchText.value = ""
        mutableLocationStack.update {
            it + LibraryLocation(category.title, category.query)
        }
    }

    fun openItem(item: LmsLibraryItem) {
        val nextLocation = when (item.type) {
            LmsLibraryItemType.ARTIST -> LibraryLocation(
                item.title,
                LmsLibraryQuery.ALBUMS,
                LmsLibraryFilter(artistId = item.id)
            )
            LmsLibraryItemType.ALBUM -> LibraryLocation(
                item.title,
                LmsLibraryQuery.TRACKS,
                LmsLibraryFilter(albumId = item.id)
            )
            LmsLibraryItemType.GENRE -> LibraryLocation(
                item.title,
                LmsLibraryQuery.ARTISTS,
                LmsLibraryFilter(genreId = item.id)
            )
            LmsLibraryItemType.PLAYLIST -> if (item.isContainer) {
                LibraryLocation(
                    item.title,
                    LmsLibraryQuery.PLAYLISTS,
                    LmsLibraryFilter(folderId = item.id)
                )
            } else {
                LibraryLocation(
                    item.title,
                    LmsLibraryQuery.PLAYLIST_TRACKS,
                    LmsLibraryFilter(playlistId = item.id)
                )
            }
            LmsLibraryItemType.TRACK -> null
        }
        if (nextLocation != null) {
            mutableSearchText.value = ""
            mutableLocationStack.update { it + nextLocation }
        }
    }

    fun navigateToBreadcrumb(index: Int) {
        mutableLocationStack.update { stack ->
            if (index in stack.indices) stack.take(index + 1) else stack
        }
    }

    fun navigateBack() {
        mutableLocationStack.update { stack ->
            if (stack.size > 1) stack.dropLast(1) else stack
        }
    }

    fun clearMessage() {
        mutableMessage.value = null
    }

    fun playNow(item: LmsLibraryItem) {
        val player = activePlayerStore.activePlayer.value
        if (player == null) {
            mutableMessage.value = "Select a connected player to play music."
            return
        }
        if (item.isContainer) return
        mutableIsPlayingItem.value = true
        mutableMessage.value = null
        viewModelScope.launch {
            try {
                libraryGateway.playNow(player.connection, player.playerId, item)
                mutablePlaybackRevision.update { it + 1 }
                mutableMessage.value = "Playing ${item.title}."
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableMessage.value = "Could not play ${item.title}."
            } finally {
                mutableIsPlayingItem.value = false
            }
        }
    }

    class Factory(
        private val libraryGateway: LmsLibraryGateway,
        private val activePlayerStore: LmsActivePlayerStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
            }
            return LibraryViewModel(libraryGateway, activePlayerStore) as T
        }
    }

    private data class LibraryLoadRequest(
        val location: LibraryLocation,
        val searchTerm: String,
        val activePlayer: LmsActivePlayer?,
        val initialKey: Int?
    )

    private companion object {
        const val PAGE_SIZE = 30
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        val pagingConfig = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = PAGE_SIZE,
            prefetchDistance = 8,
            enablePlaceholders = true,
            maxSize = PAGE_SIZE * 4
        )
    }
}

internal class LmsLibraryPagingSource(
    private val gateway: LmsLibraryGateway,
    private val activePlayer: LmsActivePlayer,
    private val query: LmsLibraryQuery,
    private val filter: LmsLibraryFilter
) : PagingSource<Int, LmsLibraryItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LmsLibraryItem> = try {
        val offset = params.key ?: 0
        val limit = params.loadSize.coerceIn(1, MAX_PAGE_SIZE)
        val page = gateway.getPage(
            activePlayer.connection,
            query,
            offset,
            limit,
            filter
        )
        LoadResult.Page(
            data = page.items,
            prevKey = if (offset == 0) null else (offset - limit).coerceAtLeast(0),
            nextKey = if (page.items.isEmpty() || offset + page.items.size >= page.totalCount) {
                null
            } else {
                offset + page.items.size
            },
            itemsBefore = offset,
            itemsAfter = (page.totalCount - offset - page.items.size).coerceAtLeast(0)
        )
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        LoadResult.Error(exception)
    }

    override fun getRefreshKey(state: PagingState<Int, LmsLibraryItem>): Int? =
        state.anchorPosition?.let { position ->
            state.closestPageToPosition(position)?.let { page ->
                page.prevKey?.plus(state.config.pageSize)
                    ?: page.nextKey?.minus(state.config.pageSize)
            }
        }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}

internal class LmsLibrarySearchPagingSource(
    private val gateway: LmsLibraryGateway,
    private val activePlayer: LmsActivePlayer,
    private val term: String
) : PagingSource<Int, LmsLibraryItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LmsLibraryItem> = try {
        val limit = params.loadSize.coerceIn(1, MAX_PAGE_SIZE)
        val firstPages = gateway.search(
            activePlayer.connection,
            term,
            0,
            limit
        )
        val totalCount = firstPages.artists.totalCount +
            firstPages.albums.totalCount +
            firstPages.tracks.totalCount
        val offset = (params.key ?: 0).coerceIn(0, totalCount)
        val requestedEnd = (offset.toLong() + limit).coerceAtMost(totalCount.toLong()).toInt()
        val categories = listOf(
            firstPages.artists to 0,
            firstPages.albums to firstPages.artists.totalCount,
            firstPages.tracks to firstPages.artists.totalCount + firstPages.albums.totalCount
        )
        val items = buildList {
            categories.forEachIndexed { index, (firstPage, categoryStart) ->
                val categoryEnd = categoryStart + firstPage.totalCount
                val rangeStart = maxOf(offset, categoryStart)
                val rangeEnd = minOf(requestedEnd, categoryEnd)
                if (rangeStart < rangeEnd) {
                    val categoryOffset = rangeStart - categoryStart
                    val categoryLimit = rangeEnd - rangeStart
                    val categoryPage = if (
                        categoryOffset >= firstPage.offset &&
                        categoryOffset + categoryLimit <= firstPage.offset + firstPage.items.size
                    ) {
                        firstPage
                    } else {
                        gateway.search(
                            activePlayer.connection,
                            term,
                            categoryOffset,
                            categoryLimit
                        ).pageAt(index)
                    }
                    val pageStart = categoryOffset - categoryPage.offset
                    addAll(categoryPage.items.subList(pageStart, pageStart + categoryLimit))
                }
            }
        }
        val nextOffset = offset + items.size
        LoadResult.Page(
            data = items,
            prevKey = if (offset == 0) null else (offset - limit).coerceAtLeast(0),
            nextKey = if (items.isEmpty() || nextOffset >= totalCount) null else nextOffset,
            itemsBefore = offset,
            itemsAfter = (totalCount - nextOffset).coerceAtLeast(0)
        )
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        LoadResult.Error(exception)
    }

    override fun getRefreshKey(state: PagingState<Int, LmsLibraryItem>): Int? =
        state.anchorPosition?.let { position ->
            state.closestPageToPosition(position)?.let { page ->
                page.prevKey?.plus(state.config.pageSize)
                    ?: page.nextKey?.minus(state.config.pageSize)
            }
        }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}

private fun LmsLibrarySearchResults.pageAt(index: Int): LmsLibraryPage = when (index) {
    0 -> artists
    1 -> albums
    else -> tracks
}

fun libraryLocationKey(locationStack: List<LibraryLocation>): String =
    locationStack.joinToString("/") { location ->
        listOf(
            location.title,
            location.query?.name.orEmpty(),
            location.filter.artistId.orEmpty(),
            location.filter.albumId.orEmpty(),
            location.filter.genreId.orEmpty(),
            location.filter.playlistId.orEmpty(),
            location.filter.folderId.orEmpty()
        ).joinToString("|")
    }