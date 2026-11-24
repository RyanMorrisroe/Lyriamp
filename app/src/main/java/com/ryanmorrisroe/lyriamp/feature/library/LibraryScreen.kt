package com.ryanmorrisroe.lyriamp.feature.library

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsHttpException
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItemType
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryQuery
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlaybackMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerCommand
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerStatus
import com.ryanmorrisroe.lyriamp.data.lms.LmsQueueAddPosition
import com.ryanmorrisroe.lyriamp.data.lms.LmsProtocolException
import com.ryanmorrisroe.lyriamp.data.lms.LmsRepeatMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleMode
import com.ryanmorrisroe.lyriamp.data.lms.artworkSource
import com.ryanmorrisroe.lyriamp.data.persistence.LastSessionScrollAnchor
import com.ryanmorrisroe.lyriamp.data.persistence.LibraryLayout
import com.ryanmorrisroe.lyriamp.data.persistence.LibraryViewType
import com.ryanmorrisroe.lyriamp.feature.connection.ConnectionViewModel
import com.ryanmorrisroe.lyriamp.feature.settings.DisplayPreferencesViewModel
import com.ryanmorrisroe.lyriamp.ui.artwork.LmsArtworkImage
import com.ryanmorrisroe.lyriamp.ui.glyph.BackArrowIcon
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    connectionViewModel: ConnectionViewModel,
    displayPreferencesViewModel: DisplayPreferencesViewModel,
    mediaPlayer: Player?,
    onChangePlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    sessionError: String? = null,
    onRetrySessionSave: () -> Unit = {},
    onLibraryStateChanged: (List<LibraryLocation>, String, LastSessionScrollAnchor?) -> Unit = { _, _, _ -> },
    queuePickerMode: Boolean = false,
    onBackFromQueuePicker: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val connectionState by connectionViewModel.uiState.collectAsStateWithLifecycle()
    val displayState by displayPreferencesViewModel.state.collectAsStateWithLifecycle()
    val visibleTabs = remember {
        LibraryTab.entries.filterNot { it == LibraryTab.PLAYLISTS }
    }
    val visibleCategories = remember {
        libraryCategories.filterNot { it.query == LmsLibraryQuery.PLAYLISTS }
    }
    var searchVisible by remember { mutableStateOf(false) }
    val items = viewModel.items.collectAsLazyPagingItems()
    val viewType = if (state.searchText.isNotBlank()) LibraryViewType.SEARCH else when (state.location.query) {
        LmsLibraryQuery.ARTISTS -> LibraryViewType.ARTISTS
        LmsLibraryQuery.ALBUMS -> LibraryViewType.ALBUMS
        LmsLibraryQuery.GENRES -> LibraryViewType.GENRES
        LmsLibraryQuery.TRACKS, LmsLibraryQuery.PLAYLIST_TRACKS -> LibraryViewType.SONGS
        LmsLibraryQuery.PLAYLISTS -> LibraryViewType.PLAYLISTS
        null -> null
    }
    val layout = if (viewType == LibraryViewType.GENRES) {
        LibraryLayout.COMPACT
    } else {
        viewType?.let(displayState.preferences::layoutFor) ?: LibraryLayout.COMFORTABLE
    }
    val artistArtworkResolver: (suspend (LmsServerConnection, LmsLibraryItem) -> String?)? =
        if (state.searchText.isNotBlank() || state.location.query == LmsLibraryQuery.ARTISTS) {
            viewModel::resolveArtistArtwork
        } else {
            null
        }
    val restoredAnchor = state.scrollAnchor?.takeIf {
        it.locationKey == libraryLocationKey(state.locationStack) &&
            it.searchText == state.searchText
    }
    val listState = key(state.locationStack, state.searchText) {
        rememberLazyGridState(
            initialFirstVisibleItemIndex = restoredAnchor?.index ?: 0,
            initialFirstVisibleItemScrollOffset = restoredAnchor?.offsetPx ?: 0
        )
    }
    var isRestoringScroll by remember(state.locationStack, state.searchText) {
        mutableStateOf(restoredAnchor != null)
    }
    var needsPositioning by remember(state.locationStack, state.searchText) {
        mutableStateOf(true)
    }
    var hasObservedRefresh by remember(state.locationStack, state.searchText) {
        mutableStateOf(false)
    }
    val persistState by rememberUpdatedState(onLibraryStateChanged)

    fun rememberVisiblePosition() {
        if (!isRestoringScroll && state.searchText.isBlank() && !state.isAtRoot) {
            val index = listState.firstVisibleItemIndex
            viewModel.updateScrollAnchor(
                LastSessionScrollAnchor(
                    libraryLocationKey(state.locationStack),
                    state.searchText,
                    if (index in 0 until items.itemCount) items.peek(index)?.id else null,
                    index,
                    listState.firstVisibleItemScrollOffset
                )
            )
        }
    }

    fun switchTab(tab: LibraryTab) {
        rememberVisiblePosition()
        viewModel.selectTab(tab)
    }

    LaunchedEffect(connectionState.activeConnection, connectionState.selectedPlayerId) {
        if (connectionState.activeConnection != null && connectionState.selectedPlayerId != null) {
            while (true) {
                connectionViewModel.refreshPlayerStatus()
                delay(1_000)
            }
        }
    }
    LaunchedEffect(state.playbackRevision) {
        if (state.playbackRevision > 0) {
            connectionViewModel.refreshSelectedPlayer()
            mediaPlayer?.prepare()
        }
    }
    BackHandler(enabled = queuePickerMode || !state.isAtRoot || state.searchText.isNotBlank()) {
        if (state.searchText.isNotBlank()) viewModel.setSearchText("")
        else if (queuePickerMode && state.isAtRoot) onBackFromQueuePicker()
        else viewModel.navigateBack()
    }
    LaunchedEffect(state.locationStack, state.searchText, items.loadState.refresh) {
        if (items.loadState.refresh is LoadState.Loading) hasObservedRefresh = true
    }
    LaunchedEffect(state.locationStack, state.searchText, items.loadState.refresh, items.itemCount) {
        if (needsPositioning && restoredAnchor == null) {
            if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
            needsPositioning = false
        } else if (isRestoringScroll && state.isAtRoot && state.searchText.isBlank()) {
            isRestoringScroll = false
            needsPositioning = false
        } else if (
            restoredAnchor != null && isRestoringScroll && hasObservedRefresh &&
            items.loadState.refresh is LoadState.NotLoading
        ) {
            val index = restoredAnchor.index.coerceAtMost((items.itemCount - 1).coerceAtLeast(0))
            if (items.itemCount > 0) listState.scrollToItem(index, restoredAnchor.offsetPx)
            isRestoringScroll = false
            needsPositioning = false
        }
    }
    LaunchedEffect(state.locationStack, state.searchText, items, listState) {
        snapshotFlow {
            if (isRestoringScroll) {
                null
            } else {
                val index = listState.firstVisibleItemIndex
                val itemId = if (index in 0 until items.itemCount && (!state.isAtRoot || state.searchText.isNotBlank())) {
                    items.peek(index)?.id
                } else {
                    null
                }
                LastSessionScrollAnchor(
                    locationKey = libraryLocationKey(state.locationStack),
                    searchText = state.searchText,
                    itemId = itemId,
                    index = index,
                    offsetPx = listState.firstVisibleItemScrollOffset
                )
            }
        }.distinctUntilChanged()
            .filterNotNull()
            .debounce(350)
            .collect { anchor ->
                viewModel.updateScrollAnchor(anchor)
                persistState(state.locationStack, state.searchText, anchor)
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            queuePickerMode -> "Add to queue"
                            state.searchText.isNotBlank() -> "Search"
                            else -> state.location.title
                        }
                    )
                },
                navigationIcon = {
                    if (queuePickerMode || !state.isAtRoot && state.searchText.isBlank()) {
                        IconButton(
                            onClick = {
                                when {
                                    state.searchText.isNotBlank() -> viewModel.setSearchText("")
                                    queuePickerMode && state.isAtRoot -> onBackFromQueuePicker()
                                    else -> viewModel.navigateBack()
                                }
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Back"
                            }
                        ) {
                            BackArrowIcon(
                                MaterialTheme.colorScheme.onSurface,
                                Modifier.size(24.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (!queuePickerMode && state.selectedTab != LibraryTab.HOME) {
                        IconButton(
                            onClick = {
                                if (searchVisible) viewModel.setSearchText("")
                                searchVisible = !searchVisible
                            },
                            modifier = Modifier.semantics {
                                contentDescription = if (searchVisible) "Close search" else "Search"
                            }
                        ) {
                            LibraryGlyphIcon(
                                if (searchVisible) LibraryGlyph.CLOSE else LibraryGlyph.SEARCH,
                                MaterialTheme.colorScheme.onSurface,
                                Modifier.size(24.dp)
                            )
                        }
                    }
                    if (!queuePickerMode) {
                        IconButton(
                            onClick = onChangePlayer,
                            modifier = Modifier.semantics {
                                contentDescription = "Change players"
                            }
                        ) {
                            LibraryGlyphIcon(
                                LibraryGlyph.SPEAKER,
                                MaterialTheme.colorScheme.onSurface,
                                Modifier.size(24.dp)
                            )
                        }
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.semantics {
                                contentDescription = "Settings"
                            }
                        ) {
                            LibraryGlyphIcon(
                                LibraryGlyph.SETTINGS,
                                MaterialTheme.colorScheme.onSurface,
                                Modifier.size(24.dp)
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!queuePickerMode) PlayerStrip(
                    modifier = Modifier.navigationBarsPadding(),
                activePlayer = state.activePlayer,
                mediaPlayer = mediaPlayer,
                status = connectionState.playerStatus,
                isSendingCommand = connectionState.isSendingPlayerCommand,
                onCommand = connectionViewModel::sendPlayerCommand,
                onExpand = onOpenNowPlaying,
                onChangePlayer = onChangePlayer
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = visibleTabs.indexOf(state.selectedTab).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth().testTag("library-tabs"),
                edgePadding = 16.dp,
                divider = {}
            ) {
                visibleTabs.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { switchTab(tab) },
                        text = { Text(tab.title, maxLines = 1) }
                    )
                }
            }
            if (
                state.selectedTab == LibraryTab.HOME && state.searchText.isBlank() ||
                searchVisible || state.searchText.isNotBlank()
            ) {
                OutlinedTextField(
                    value = state.searchText,
                    onValueChange = {
                        if (state.searchText.isBlank() && it.isNotBlank()) rememberVisiblePosition()
                        viewModel.setSearchText(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    placeholder = {
                        Text(
                            "Search artists, albums, and tracks",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    leadingIcon = {
                        LibraryGlyphIcon(
                            LibraryGlyph.SEARCH,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (state.searchText.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.setSearchText("") },
                                modifier = Modifier.semantics {
                                    contentDescription = "Clear search"
                                }
                            ) {
                                LibraryGlyphIcon(
                                    LibraryGlyph.CLOSE,
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                    Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )
            }
            if (!state.isAtRoot && state.searchText.isBlank() && state.locationStack.size > 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.locationStack.forEachIndexed { index, location ->
                        TextButton(onClick = { viewModel.navigateToBreadcrumb(index) }) {
                            Text(location.title, maxLines = 1)
                        }
                        if (index < state.locationStack.lastIndex) {
                            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            state.message?.let { message ->
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = if (message.startsWith("Could not")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            connectionState.playerQueueErrorMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            displayState.error?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = displayPreferencesViewModel::retry) { Text("Retry reading settings") }
            }
            sessionError?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetrySessionSave) { Text("Retry saving position") }
            }
            if (state.isAtRoot && state.searchText.isBlank()) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Column(
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Your collection",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text("What sounds good?", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Browse your music, your way.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    visibleCategories.chunked(2).forEach { categories ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            categories.forEach { category ->
                                Surface(
                                    onClick = {
                                        switchTab(LibraryTab.entries.first { it.query == category.query })
                                    },
                                    modifier = Modifier.weight(1f).height(92.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow
                                ) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Text(category.title, style = MaterialTheme.typography.titleSmall)
                                        Text("Browse", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            if (categories.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    state.lastVisitedTab
                        ?.takeUnless { it == LibraryTab.PLAYLISTS }
                        ?.let { tab ->
                            ListItem(
                                modifier = Modifier.clickable { switchTab(tab) },
                                headlineContent = { Text("Last visited") },
                                supportingContent = {
                                    Text(state.tabs[tab]?.locations?.lastOrNull()?.title ?: tab.title)
                                }
                            )
                        }
                }
            } else {
                if (viewType != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.searchText.isBlank()) {
                                "Browse ${state.location.title}"
                            } else {
                                "Search results"
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        if (viewType != LibraryViewType.GENRES) {
                            LibraryLayoutSelector(
                                viewType = viewType,
                                selectedLayout = layout,
                                onSelect = { displayPreferencesViewModel.setLayout(viewType, it) }
                            )
                        }
                    }
                }
                LibraryResults(
                    items = items,
                    listState = listState,
                    layout = layout,
                    isSearchResults = state.searchText.isNotBlank(),
                    activePlayer = state.activePlayer,
                    isPlayingItem = state.isPlayingItem,
                    albumTrackListing = state.location.query == LmsLibraryQuery.TRACKS &&
                        state.location.filter.albumId != null,
                    onOpenItem = viewModel::openItem,
                    onPlayNow = viewModel::playNow,
                    onAddToQueue = { item ->
                        connectionViewModel.addPlayerQueueItem(item, LmsQueueAddPosition.END)
                    },
                    onPlayNext = { item ->
                        connectionViewModel.addPlayerQueueItem(item, LmsQueueAddPosition.NEXT)
                    },
                    isQueueBusy = connectionState.isMutatingPlayerQueue ||
                        connectionState.isLoadingPlayerQueue,
                    queuePickerMode = queuePickerMode,
                    onResolveArtistArtwork = artistArtworkResolver,
                    onRetry = items::retry
                )
            }
        }
    }

}

internal fun libraryItemKey(
    item: LmsLibraryItem,
    index: Int,
    loadedItems: List<LmsLibraryItem>
): String {
    val identity = "${item.type}:${item.id}"
    return if (loadedItems.count { it.type == item.type && it.id == item.id } > 1) {
        "$identity:$index"
    } else {
        identity
    }
}

@Composable
private fun LibraryResults(
    items: LazyPagingItems<LmsLibraryItem>,
    listState: androidx.compose.foundation.lazy.grid.LazyGridState,
    layout: LibraryLayout,
    isSearchResults: Boolean,
    activePlayer: LmsActivePlayer?,
    isPlayingItem: Boolean,
    albumTrackListing: Boolean,
    onOpenItem: (LmsLibraryItem) -> Unit,
    onPlayNow: (LmsLibraryItem) -> Unit,
    onAddToQueue: (LmsLibraryItem) -> Unit,
    onPlayNext: (LmsLibraryItem) -> Unit,
    isQueueBusy: Boolean,
    queuePickerMode: Boolean,
    onResolveArtistArtwork: (suspend (LmsServerConnection, LmsLibraryItem) -> String?)?,
    onRetry: () -> Unit
) {
    LazyVerticalGrid(
        columns = if (layout == LibraryLayout.COVER_WALL) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize().testTag("library-results"),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)
    ) {
        when (val refresh = items.loadState.refresh) {
            LoadState.Loading -> if (items.itemCount == 0) item(span = { GridItemSpan(maxLineSpan) }) { LoadingRow() }
            is LoadState.Error -> if (items.itemCount == 0) item(span = { GridItemSpan(maxLineSpan) }) {
                ErrorRow(refresh.error.message ?: "Could not load the library.", onRetry)
            }
            is LoadState.NotLoading -> if (items.itemCount == 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "No results.",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(
            count = items.itemCount,
            key = { index ->
                items.peek(index)?.let {
                    libraryItemKey(it, index, items.itemSnapshotList.items)
                } ?: "library-placeholder:$index"
            }
        ) { index ->
            val item = items[index]
            if (item != null) {
                val loadedDiscNumbers = items.itemSnapshotList.items
                    .mapNotNull { loadedItem -> loadedItem.discNumber?.takeIf { it > 0 } }
                    .distinct()
                val isMultiDiscAlbum = albumTrackListing && loadedDiscNumbers.size > 1
                val previousDiscNumber = if (index > 0) items.peek(index - 1)?.discNumber else null
                val discHeaderNumber = item.discNumber?.takeIf { discNumber ->
                    isMultiDiscAlbum &&
                        discNumber > 0 &&
                        (index == 0 || previousDiscNumber != discNumber)
                }
                val discTrackCount = discHeaderNumber?.let { discNumber ->
                    items.itemSnapshotList.items.count { loadedItem ->
                        loadedItem.discNumber?.takeIf { it > 0 } == discNumber
                    }
                } ?: 0

                if (layout == LibraryLayout.COVER_WALL) {
                    Column {
                        if (discHeaderNumber != null) {
                            AlbumDiscHeader(discHeaderNumber, discTrackCount)
                        }
                        LibraryItemCard(
                            item = item,
                            connection = activePlayer?.connection,
                            isSearchResults = isSearchResults,
                            isPlayingItem = isPlayingItem,
                            onResolveArtistArtwork = onResolveArtistArtwork,
                            trackNumber = item.trackNumber,
                            onOpen = {
                                if (queuePickerMode && item.type == LmsLibraryItemType.TRACK) {
                                    onAddToQueue(item)
                                } else {
                                    onOpenItem(item)
                                }
                            },
                            onPlayNow = { if (queuePickerMode) onAddToQueue(item) else onPlayNow(item) },
                            onAddToQueue = { onAddToQueue(item) },
                            onPlayNext = { onPlayNext(item) },
                            isQueueBusy = isQueueBusy,
                            queuePickerMode = queuePickerMode
                        )
                    }
                } else {
                    Column {
                        if (discHeaderNumber != null) {
                            AlbumDiscHeader(discHeaderNumber, discTrackCount)
                        }
                        LibraryItemRow(
                            item = item,
                            connection = activePlayer?.connection,
                            showArtwork = layout == LibraryLayout.COMFORTABLE,
                            isSearchResults = isSearchResults,
                            isPlayingItem = isPlayingItem,
                            onResolveArtistArtwork = onResolveArtistArtwork,
                            trackNumber = item.trackNumber,
                            showTrackNumber = albumTrackListing,
                            onOpen = { onOpenItem(item) },
                            onPlayNow = { if (queuePickerMode) onAddToQueue(item) else onPlayNow(item) },
                            onAddToQueue = { onAddToQueue(item) },
                            onPlayNext = { onPlayNext(item) },
                            isQueueBusy = isQueueBusy,
                            queuePickerMode = queuePickerMode
                        )
                    }
                }
            } else {
                LoadingRow()
            }
        }
        when (val append = items.loadState.append) {
            LoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingRow() }
            is LoadState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                ErrorRow(append.error.message ?: "Could not load more results.", onRetry)
            }
            is LoadState.NotLoading -> Unit
        }
    }
}

@Composable
private fun AlbumDiscHeader(discNumber: Int, trackCount: Int) {
    val shape = RoundedCornerShape(9.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .padding(horizontal = 13.dp)
            .padding(top = 2.dp, bottom = 2.dp)
            .testTag("album-disc-header-$discNumber"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DISC $discNumber",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (trackCount > 0) {
                Text(
                    text = "$trackCount ${if (trackCount == 1) "track" else "tracks"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LibraryItemRow(
    item: LmsLibraryItem,
    connection: LmsServerConnection?,
    showArtwork: Boolean,
    isSearchResults: Boolean,
    isPlayingItem: Boolean,
    onResolveArtistArtwork: (suspend (LmsServerConnection, LmsLibraryItem) -> String?)?,
    trackNumber: Int?,
    showTrackNumber: Boolean,
    onOpen: () -> Unit,
    onPlayNow: () -> Unit,
    onAddToQueue: () -> Unit,
    onPlayNext: () -> Unit,
    isQueueBusy: Boolean,
    queuePickerMode: Boolean
) {
    val canPlay = item.canPlay()
    val subtitle = item.displaySubtitle(isSearchResults)

    ListItem(
        modifier = Modifier.clickable {
            if (item.type == LmsLibraryItemType.TRACK) {
                if (queuePickerMode) onAddToQueue() else onPlayNow()
            } else {
                onOpen()
            }
        },
        leadingContent = if (showArtwork || (showTrackNumber && trackNumber != null)) {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showTrackNumber && trackNumber != null) {
                        Text(
                            text = trackNumber.toString(),
                            modifier = Modifier.width(28.dp),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (showArtwork) {
                        LibraryArtwork(
                            item = item,
                            connection = connection,
                            showTypeLabel = isSearchResults,
                            onResolveArtistArtwork = onResolveArtistArtwork,
                            modifier = Modifier.size(52.dp)
                        )
                    }
                }
            }
        } else null,
        headlineContent = { Text(item.title, maxLines = 1) },
        supportingContent = if (subtitle.isNotBlank()) {
            {
                Text(subtitle, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else null,
        trailingContent = {
            if (canPlay) {
                LibraryPlayButton(
                    itemTitle = item.title,
                    isBusy = isPlayingItem || isQueueBusy,
                    queuePickerMode = queuePickerMode,
                    onPlayNow = onPlayNow,
                    onAddToQueue = onAddToQueue,
                    onPlayNext = onPlayNext
                )
            } else {
                TextButton(onClick = onOpen) { Text(if (item.isContainer) "Open" else "Browse") }
            }
        }
    )
}

@Composable
private fun LibraryItemCard(
    item: LmsLibraryItem,
    connection: LmsServerConnection?,
    isSearchResults: Boolean,
    isPlayingItem: Boolean,
    onResolveArtistArtwork: (suspend (LmsServerConnection, LmsLibraryItem) -> String?)?,
    trackNumber: Int?,
    onOpen: () -> Unit,
    onPlayNow: () -> Unit,
    onAddToQueue: () -> Unit,
    onPlayNext: () -> Unit,
    isQueueBusy: Boolean,
    queuePickerMode: Boolean
) {
    Column(Modifier.padding(8.dp).clickable(onClick = onOpen)) {
        val subtitle = item.displaySubtitle(isSearchResults)
        LibraryArtwork(
            item,
            connection,
            isSearchResults,
            onResolveArtistArtwork,
            Modifier.fillMaxWidth().aspectRatio(1f)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (trackNumber != null) {
                Text(
                    text = trackNumber.toString(),
                    modifier = Modifier.width(24.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = item.title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                style = MaterialTheme.typography.titleSmall
            )
        }
        if (subtitle.isNotBlank()) {
            Text(subtitle, maxLines = 1, style = MaterialTheme.typography.bodySmall)
        }
        if (item.canPlay()) {
            LibraryPlayButton(
                itemTitle = item.title,
                isBusy = isPlayingItem || isQueueBusy,
                queuePickerMode = queuePickerMode,
                onPlayNow = onPlayNow,
                onAddToQueue = onAddToQueue,
                onPlayNext = onPlayNext
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryPlayButton(
    itemTitle: String,
    isBusy: Boolean,
    queuePickerMode: Boolean,
    onPlayNow: () -> Unit,
    onAddToQueue: () -> Unit,
    onPlayNext: () -> Unit
) {
    var menuExpanded by remember(itemTitle) { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    enabled = !isBusy,
                    onClick = if (queuePickerMode) onAddToQueue else onPlayNow,
                    onLongClick = if (queuePickerMode) null else ({ menuExpanded = true }),
                    role = Role.Button
                )
                .semantics {
                    contentDescription = if (queuePickerMode) {
                        "Add $itemTitle to queue"
                    } else {
                        "Play $itemTitle"
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (queuePickerMode) {
                Text("+", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall)
            } else {
                LibraryGlyphIcon(
                    LibraryGlyph.PLAY,
                    MaterialTheme.colorScheme.primary,
                    Modifier.size(24.dp)
                )
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Add to end") },
                onClick = {
                    menuExpanded = false
                    onAddToQueue()
                }
            )
            DropdownMenuItem(
                text = { Text("Play next") },
                onClick = {
                    menuExpanded = false
                    onPlayNext()
                }
            )
        }
    }
}

@Composable
private fun LibraryArtwork(
    item: LmsLibraryItem,
    connection: LmsServerConnection?,
    showTypeLabel: Boolean,
    onResolveArtistArtwork: (suspend (LmsServerConnection, LmsLibraryItem) -> String?)?,
    modifier: Modifier
) {
    var resolvedArtistArtworkUrl by remember(
        item.type,
        item.id,
        item.artworkId,
        item.artworkUrl,
        connection
    ) {
        mutableStateOf(item.artworkUrl)
    }
    LaunchedEffect(
        item.type,
        item.id,
        item.artworkId,
        item.artworkUrl,
        connection,
        onResolveArtistArtwork != null
    ) {
        val resolver = onResolveArtistArtwork
        if (item.type == LmsLibraryItemType.ARTIST &&
            connection != null &&
            resolver != null
        ) {
            try {
                resolvedArtistArtworkUrl = resolver(connection, item)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: LmsProtocolException) {
                logArtistArtworkLookupFailure(exception)
            } catch (exception: LmsHttpException) {
                logArtistArtworkLookupFailure(exception)
            } catch (exception: HttpRequestTimeoutException) {
                logArtistArtworkLookupFailure(exception)
            } catch (exception: IOException) {
                logArtistArtworkLookupFailure(exception)
            }
        }
    }
    Surface(
        modifier = modifier.clip(RoundedCornerShape(4.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        LmsArtworkImage(
            connection = connection,
            artworkId = if (item.type == LmsLibraryItemType.ARTIST &&
                resolvedArtistArtworkUrl != null
            ) {
                null
            } else {
                item.artworkId
            },
            artworkUrl = resolvedArtistArtworkUrl ?: item.artworkUrl,
            contentDescription = "${item.title} artwork",
            modifier = Modifier.fillMaxSize()
        ) {
            if (showTypeLabel) {
                Text(item.type.displayName(), style = MaterialTheme.typography.labelSmall)
            } else {
                LibraryGlyphIcon(
                    LibraryGlyph.MUSIC,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.size(24.dp)
                )
            }
        }
    }
}

private fun logArtistArtworkLookupFailure(exception: Exception) {
    if (Log.isLoggable(LIBRARY_ARTWORK_LOG_TAG, Log.DEBUG)) {
        Log.d(
            LIBRARY_ARTWORK_LOG_TAG,
            "Artist artwork lookup failed (${exception.javaClass.simpleName})"
        )
    }
}

private const val LIBRARY_ARTWORK_LOG_TAG = "LmsArtwork"

private fun LmsLibraryItem.canPlay(): Boolean = !isContainer && type in setOf(
    LmsLibraryItemType.ARTIST,
    LmsLibraryItemType.ALBUM,
    LmsLibraryItemType.GENRE,
    LmsLibraryItemType.TRACK,
    LmsLibraryItemType.PLAYLIST
)

private fun LmsLibraryItem.displaySubtitle(showTypeLabel: Boolean): String = listOfNotNull(
    type.displayName().takeIf { showTypeLabel },
    artist?.takeUnless { it == title },
    album,
    year?.takeIf { type == LmsLibraryItemType.ALBUM },
    durationSeconds?.let(::formatDuration)
).joinToString(" · ")

private fun LibraryLayout.displayName(): String = when (this) {
    LibraryLayout.COVER_WALL -> "Cover wall"
    LibraryLayout.COMPACT -> "Compact"
    LibraryLayout.COMFORTABLE -> "Comfortable"
}

@Composable
private fun LibraryLayoutSelector(
    viewType: LibraryViewType,
    selectedLayout: LibraryLayout,
    onSelect: (LibraryLayout) -> Unit
) {
    val layouts = if (viewType.allowsCoverWall) {
        listOf(
            LibraryLayout.COVER_WALL,
            LibraryLayout.COMFORTABLE,
            LibraryLayout.COMPACT
        )
    } else {
        listOf(LibraryLayout.COMFORTABLE, LibraryLayout.COMPACT)
    }
    val shape = RoundedCornerShape(12.dp)

    Surface(
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(Modifier.padding(3.dp)) {
            layouts.forEach { option ->
                val selected = selectedLayout == option
                val segmentShape = RoundedCornerShape(9.dp)
                IconToggleButton(
                    checked = selected,
                    onCheckedChange = { if (it) onSelect(option) },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("library-layout-option-${option.name}")
                        .clip(segmentShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent
                        )
                        .semantics {
                            contentDescription = option.displayName()
                        }
                ) {
                    LibraryGlyphIcon(
                        glyph = when (option) {
                            LibraryLayout.COVER_WALL -> LibraryGlyph.COVER_WALL
                            LibraryLayout.COMPACT -> LibraryGlyph.COMPACT
                            LibraryLayout.COMFORTABLE -> LibraryGlyph.COMFORTABLE
                        },
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp)
                            .testTag("library-layout-glyph-${option.name}")
                    )
                }
            }
        }
    }
}

private enum class LibraryGlyph {
    CLOSE,
    COMPACT,
    COMFORTABLE,
    COVER_WALL,
    NEXT,
    PAUSE,
    PLAY,
    PREVIOUS,
    SEARCH,
    MUSIC,
    SPEAKER,
    SETTINGS
}

@Composable
private fun LibraryGlyphIcon(
    glyph: LibraryGlyph,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f

        fun rect(left: Float, top: Float, width: Float, height: Float) {
            drawRect(
                color = color,
                topLeft = Offset(left * unit, top * unit),
                size = Size(width * unit, height * unit)
            )
        }

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(
                color = color,
                start = Offset(x1 * unit, y1 * unit),
                end = Offset(x2 * unit, y2 * unit),
                strokeWidth = 2f * unit,
                cap = StrokeCap.Round
            )
        }

        fun triangle(first: Offset, second: Offset, third: Offset) {
            val path = Path().apply {
                moveTo(first.x * unit, first.y * unit)
                lineTo(second.x * unit, second.y * unit)
                lineTo(third.x * unit, third.y * unit)
                close()
            }
            drawPath(path, color)
        }

        when (glyph) {
            LibraryGlyph.CLOSE -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
            LibraryGlyph.COMPACT -> for (row in 0..3) {
                val top = 2f + row * 5f
                rect(2f, top + 0.5f, 20f, 2f)
            }
            LibraryGlyph.COMFORTABLE -> for (row in 0..2) {
                val top = 2f + row * 7f
                rect(2f, top, 3f, 3f)
                rect(8f, top + 0.5f, 14f, 2f)
            }
            LibraryGlyph.COVER_WALL -> {
                for (row in 0..1) {
                    for (column in 0..1) {
                        rect(2f + column * 11f, 2f + row * 11f, 9f, 9f)
                    }
                }
            }
            LibraryGlyph.NEXT -> {
                triangle(Offset(5f, 5f), Offset(15f, 12f), Offset(5f, 19f))
                rect(18f, 5f, 2f, 14f)
            }
            LibraryGlyph.PAUSE -> {
                rect(6f, 5f, 4f, 14f)
                rect(14f, 5f, 4f, 14f)
            }
            LibraryGlyph.PLAY -> triangle(Offset(7f, 5f), Offset(19f, 12f), Offset(7f, 19f))
            LibraryGlyph.PREVIOUS -> {
                rect(4f, 5f, 2f, 14f)
                triangle(Offset(19f, 5f), Offset(9f, 12f), Offset(19f, 19f))
            }
            LibraryGlyph.SEARCH -> {
                drawCircle(
                    color = color,
                    radius = 7f * unit,
                    center = Offset(10f * unit, 10f * unit),
                    style = Stroke(width = 2f * unit)
                )
                line(15f, 15f, 21f, 21f)
            }
            LibraryGlyph.MUSIC -> {
                line(14f, 4f, 14f, 18f)
                line(14f, 4f, 21f, 3f)
                drawCircle(color, 3.5f * unit, Offset(10f * unit, 18f * unit))
                drawCircle(color, 3.5f * unit, Offset(17f * unit, 16f * unit))
            }
            LibraryGlyph.SPEAKER -> {
                line(5f, 2f, 19f, 2f)
                line(19f, 2f, 19f, 22f)
                line(19f, 22f, 5f, 22f)
                line(5f, 22f, 5f, 2f)
                drawCircle(
                    color = color,
                    radius = 4f * unit,
                    center = Offset(12f * unit, 16f * unit),
                    style = Stroke(width = 2f * unit)
                )
                drawCircle(
                    color = color,
                    radius = 2f * unit,
                    center = Offset(12f * unit, 7f * unit)
                )
            }
            LibraryGlyph.SETTINGS -> {
                rect(10f, 2f, 4f, 4f)
                rect(10f, 18f, 4f, 4f)
                rect(2f, 10f, 4f, 4f)
                rect(18f, 10f, 4f, 4f)
                rect(4f, 4f, 4f, 4f)
                rect(16f, 4f, 4f, 4f)
                rect(4f, 16f, 4f, 4f)
                rect(16f, 16f, 4f, 4f)
                drawCircle(
                    color = color,
                    radius = 7f * unit,
                    center = Offset(12f * unit, 12f * unit),
                    style = Stroke(width = 2f * unit)
                )
                drawCircle(
                    color = color,
                    radius = 3f * unit,
                    center = Offset(12f * unit, 12f * unit),
                    style = Stroke(width = 2f * unit)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerStrip(
    modifier: Modifier = Modifier,
    activePlayer: LmsActivePlayer?,
    mediaPlayer: Player?,
    status: LmsPlayerStatus?,
    isSendingCommand: Boolean,
    onCommand: (LmsPlayerCommand) -> Unit,
    onExpand: () -> Unit,
    onChangePlayer: () -> Unit
) {
    val cardShape = RoundedCornerShape(18.dp)
    val cardModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp)

    if (activePlayer == null) {
        Surface(
            modifier = cardModifier,
            shape = cardShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            TextButton(
                onClick = onChangePlayer,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Select a player")
            }
        }
        return
    }
    val controlsEnabled = !isSendingCommand
    val playback = rememberPlayerStripState(mediaPlayer, status, activePlayer.playerName)
    val dragThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    val latestOnExpand by rememberUpdatedState(onExpand)
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var showExpandHint by remember { mutableStateOf(false) }
    Surface(
        modifier = cardModifier
            .testTag("player-strip")
            .graphicsLayer { translationY = dragOffset }
            .pointerInput(dragThreshold) {
                var cumulativeDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        cumulativeDrag = 0f
                        dragOffset = 0f
                        showExpandHint = false
                    },
                    onDragEnd = {
                        val shouldExpand = cumulativeDrag <= -dragThreshold
                        cumulativeDrag = 0f
                        dragOffset = 0f
                        showExpandHint = false
                        if (shouldExpand) latestOnExpand()
                    },
                    onDragCancel = {
                        cumulativeDrag = 0f
                        dragOffset = 0f
                        showExpandHint = false
                    },
                    onVerticalDrag = { change, dragAmount ->
                        cumulativeDrag += dragAmount
                        dragOffset = cumulativeDrag.coerceIn(-dragThreshold * 1.5f, 0f)
                        showExpandHint = cumulativeDrag <= -dragThreshold
                        change.consume()
                    }
                )
            },
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 8.dp, end = 8.dp, bottom = 2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerArtwork(
                    mediaPlayer,
                    activePlayer.connection,
                    status,
                    Modifier.size(48.dp),
                    onExpand
                )
                Spacer(Modifier.width(10.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(onExpand) {
                            detectTapGestures(onTap = { onExpand() })
                        }
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        activePlayer.playerName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Text(
                        playback.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    playback.artist?.takeIf(String::isNotBlank)?.let { artist ->
                        Text(
                            artist,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = {
                        if (mediaPlayer == null) onCommand(LmsPlayerCommand.Previous)
                        else mediaPlayer.seekToPreviousMediaItem()
                    },
                    enabled = controlsEnabled,
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = "Previous track" }
                ) {
                    LibraryGlyphIcon(
                        LibraryGlyph.PREVIOUS,
                        MaterialTheme.colorScheme.onSurface,
                        Modifier.size(20.dp)
                    )
                }
                FilledIconButton(
                    onClick = {
                        if (mediaPlayer == null) {
                            onCommand(
                                if (playback.isPlaying) LmsPlayerCommand.Pause
                                else LmsPlayerCommand.Play
                            )
                        } else if (playback.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
                    },
                    enabled = controlsEnabled,
                    modifier = Modifier
                        .size(40.dp)
                        .semantics {
                            contentDescription = if (playback.isPlaying) {
                                "Pause playback"
                            } else {
                                "Play playback"
                            }
                        },
                    shape = CircleShape
                ) {
                    LibraryGlyphIcon(
                        if (playback.isPlaying) LibraryGlyph.PAUSE else LibraryGlyph.PLAY,
                        MaterialTheme.colorScheme.onPrimary,
                        Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = {
                        if (mediaPlayer == null) onCommand(LmsPlayerCommand.Next)
                        else mediaPlayer.seekToNextMediaItem()
                    },
                    enabled = controlsEnabled,
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = "Next track" }
                ) {
                    LibraryGlyphIcon(
                        LibraryGlyph.NEXT,
                        MaterialTheme.colorScheme.onSurface,
                        Modifier.size(20.dp)
                    )
                }
            }
            AnimatedVisibility(
                visible = showExpandHint,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    "Release to open Now Playing",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
            if (playback.canSeek && playback.durationMs != null) {
                var position by remember(playback.durationMs) {
                    mutableFloatStateOf(playback.positionMs / 1_000f)
                }
                var isSeeking by remember(playback.durationMs) { mutableStateOf(false) }
                val seekColors = SliderDefaults.colors(
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                val seekInteractionSource = remember(playback.durationMs) {
                    MutableInteractionSource()
                }
                LaunchedEffect(playback.positionMs, playback.durationMs) {
                    if (!isSeeking) position = playback.positionMs / 1_000f
                }
                Slider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .semantics { contentDescription = "Playback position" }
                        .testTag("player-strip-progress"),
                    value = position.coerceIn(0f, playback.durationMs / 1_000f),
                    onValueChange = {
                        isSeeking = true
                        position = it
                    },
                    onValueChangeFinished = {
                        if (mediaPlayer == null) onCommand(LmsPlayerCommand.SeekTo(position.toInt()))
                        else mediaPlayer.seekTo((position * 1_000).toLong())
                        isSeeking = false
                    },
                    valueRange = 0f..(playback.durationMs / 1_000f),
                    enabled = controlsEnabled,
                    colors = seekColors,
                    interactionSource = seekInteractionSource,
                    thumb = {
                        SliderDefaults.Thumb(
                            interactionSource = seekInteractionSource,
                            colors = seekColors,
                            enabled = controlsEnabled,
                            thumbSize = DpSize(12.dp, 12.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun PlayerArtwork(
    player: Player?,
    connection: LmsServerConnection,
    status: LmsPlayerStatus?,
    modifier: Modifier,
    onExpand: () -> Unit
) {
    val artworkUri = player?.currentMediaItem?.mediaMetadata?.artworkUri?.toString()
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onExpand)
            .testTag("player-strip-artwork")
            .semantics { contentDescription = "Open Now Playing" },
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        LmsArtworkImage(
            connection = connection,
            artworkId = if (artworkUri == null) status?.artworkId else null,
            artworkUrl = artworkUri ?: status?.artworkUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        ) {
            LibraryGlyphIcon(
                LibraryGlyph.MUSIC,
                MaterialTheme.colorScheme.onPrimaryContainer,
                Modifier.size(24.dp)
            )
        }
    }
}

private data class PlayerStripState(
    val title: String,
    val artist: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val canSeek: Boolean
)

@Composable
private fun rememberPlayerStripState(
    player: Player?,
    status: LmsPlayerStatus?,
    playerName: String
): PlayerStripState {
    var state by remember(player, status, playerName) {
        mutableStateOf(player.readPlayerStripState(status, playerName))
    }
    DisposableEffect(player, status, playerName) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state = player.readPlayerStripState(status, playerName)
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player, state.isPlaying) {
        if (player != null && state.isPlaying) {
            while (true) {
                delay(1_000)
                state = player.readPlayerStripState(status, playerName)
            }
        }
    }
    return state
}

private fun Player?.readPlayerStripState(
    status: LmsPlayerStatus?,
    playerName: String
): PlayerStripState {
    val metadata = this?.currentMediaItem?.mediaMetadata
    val hasCurrentItem = this?.currentMediaItem != null
    val mediaDuration = this?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
    return PlayerStripState(
        title = metadata?.title?.toString()?.takeIf(String::isNotBlank)
            ?: status?.title
            ?: if (hasCurrentItem) playerName else "Nothing playing",
        artist = metadata?.artist?.toString()?.takeIf(String::isNotBlank) ?: status?.artist,
        isPlaying = this?.isPlaying == true || (!hasCurrentItem && status?.mode == LmsPlaybackMode.PLAYING),
        positionMs = status?.positionSeconds?.times(1_000)?.toLong()
            ?: this?.currentPosition
            ?: 0L,
        durationMs = mediaDuration ?: status?.durationSeconds?.let { (it * 1_000).toLong() },
        canSeek = if (hasCurrentItem) {
            isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        } else {
            status?.canSeek == true
        }
    )
}

@Composable
internal fun PlayerControlsPlaybackButton(
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics {
            contentDescription = if (isPlaying) "Pause playback" else "Play playback"
        }
    ) {
        LibraryGlyphIcon(
            if (isPlaying) LibraryGlyph.PAUSE else LibraryGlyph.PLAY,
            MaterialTheme.colorScheme.onSurface,
            Modifier.size(24.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerControlsSheet(
    playerName: String?,
    connectionState: com.ryanmorrisroe.lyriamp.feature.connection.ConnectionUiState,
    onCommand: (LmsPlayerCommand) -> Unit,
    onChangePlayer: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(playerName ?: "Player", style = MaterialTheme.typography.titleMedium)
            Text(
                connectionState.playerStatus?.title ?: "Nothing playing",
                style = MaterialTheme.typography.titleLarge
            )
            val status = connectionState.playerStatus
            if (status?.canSeek == true && status.durationSeconds != null && status.durationSeconds > 0) {
                var position by remember(status) { mutableFloatStateOf(status.positionSeconds.toFloat()) }
                Slider(
                    value = position.coerceIn(0f, status.durationSeconds.toFloat()),
                    onValueChange = { position = it },
                    onValueChangeFinished = {
                        onCommand(LmsPlayerCommand.SeekTo(position.toInt()))
                    },
                    valueRange = 0f..status.durationSeconds.toFloat(),
                    enabled = !connectionState.isSendingPlayerCommand
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(onClick = { onCommand(LmsPlayerCommand.Previous) }) { Text("Previous") }
                PlayerControlsPlaybackButton(
                    isPlaying = status?.mode == LmsPlaybackMode.PLAYING,
                    onClick = {
                        onCommand(
                            if (status?.mode == LmsPlaybackMode.PLAYING) {
                                LmsPlayerCommand.Pause
                            } else {
                                LmsPlayerCommand.Play
                            }
                        )
                    }
                )
                TextButton(onClick = { onCommand(LmsPlayerCommand.Next) }) { Text("Next") }
            }
            connectionState.playerMixerState?.let { mixer ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", modifier = Modifier.weight(1f))
                    Text("${mixer.volumePercent}%")
                    TextButton(onClick = { onCommand(LmsPlayerCommand.SetMuted(!mixer.isMuted)) }) {
                        Text(if (mixer.isMuted) "Unmute" else "Mute")
                    }
                }
                var volume by remember(mixer.volumePercent) {
                    mutableFloatStateOf(mixer.volumePercent.toFloat())
                }
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    onValueChangeFinished = { onCommand(LmsPlayerCommand.SetVolume(volume.toInt())) },
                    valueRange = 0f..100f,
                    enabled = !connectionState.isSendingPlayerCommand
                )
            }
            connectionState.playerPlaybackOptions?.let { options ->
                Text("Shuffle", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LmsShuffleMode.entries.forEach { mode ->
                        FilterChip(
                            selected = mode == options.shuffleMode,
                            onClick = { onCommand(LmsPlayerCommand.SetShuffleMode(mode)) },
                            label = { Text(mode.displayName()) }
                        )
                    }
                }
                Text("Repeat", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LmsRepeatMode.entries.forEach { mode ->
                        FilterChip(
                            selected = mode == options.repeatMode,
                            onClick = { onCommand(LmsPlayerCommand.SetRepeatMode(mode)) },
                            label = { Text(mode.displayName()) }
                        )
                    }
                }
            }
            connectionState.playerErrorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onChangePlayer, modifier = Modifier.fillMaxWidth()) {
                Text("Change player")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("Retry") }
    }
}

private fun LmsLibraryItemType.displayName(): String = when (this) {
    LmsLibraryItemType.ARTIST -> "Artist"
    LmsLibraryItemType.ALBUM -> "Album"
    LmsLibraryItemType.GENRE -> "Genre"
    LmsLibraryItemType.TRACK -> "Track"
    LmsLibraryItemType.PLAYLIST -> "Playlist"
}

private fun LmsShuffleMode.displayName(): String = when (this) {
    LmsShuffleMode.OFF -> "Off"
    LmsShuffleMode.SONGS -> "Songs"
    LmsShuffleMode.ALBUMS -> "Albums"
}

private fun LmsRepeatMode.displayName(): String = when (this) {
    LmsRepeatMode.OFF -> "Off"
    LmsRepeatMode.TRACK -> "Track"
    LmsRepeatMode.PLAYLIST -> "Playlist"
}

private fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds.toInt().coerceAtLeast(0)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}