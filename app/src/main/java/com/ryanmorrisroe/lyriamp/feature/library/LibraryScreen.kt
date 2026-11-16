package com.ryanmorrisroe.lyriamp.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayer
import com.ryanmorrisroe.lyriamp.data.lms.basicAuthorizationHeader
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItemType
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlaybackMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerCommand
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerStatus
import com.ryanmorrisroe.lyriamp.data.lms.LmsRepeatMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleMode
import com.ryanmorrisroe.lyriamp.data.persistence.LastSessionScrollAnchor
import com.ryanmorrisroe.lyriamp.feature.connection.ConnectionViewModel
import io.ktor.http.HttpHeaders
import java.net.URI
import kotlinx.coroutines.delay
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
    mediaPlayer: Player?,
    onChangePlayer: () -> Unit,
    onChangeServer: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onLibraryStateChanged: (List<LibraryLocation>, String, LastSessionScrollAnchor?) -> Unit = { _, _, _ -> }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val connectionState by connectionViewModel.uiState.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    val restoredAnchor = state.scrollAnchor?.takeIf {
        it.locationKey == libraryLocationKey(state.locationStack) &&
            it.searchText == state.searchText
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = restoredAnchor?.index ?: 0,
        initialFirstVisibleItemScrollOffset = restoredAnchor?.offsetPx ?: 0
    )
    var isRestoringScroll by remember(state.locationStack, state.searchText) {
        mutableStateOf(restoredAnchor != null)
    }
    var hasObservedRefresh by remember(state.locationStack, state.searchText) {
        mutableStateOf(false)
    }
    val persistState by rememberUpdatedState(onLibraryStateChanged)

    LaunchedEffect(state.playbackRevision) {
        if (state.playbackRevision > 0) {
            connectionViewModel.refreshSelectedPlayer()
            mediaPlayer?.prepare()
        }
    }
    BackHandler(enabled = !state.isAtRoot || state.searchText.isNotBlank()) {
        if (state.searchText.isNotBlank()) viewModel.setSearchText("")
        else viewModel.navigateBack()
    }
    LaunchedEffect(items.loadState.refresh) {
        if (items.loadState.refresh is LoadState.Loading) hasObservedRefresh = true
    }
    LaunchedEffect(state.locationStack, state.searchText, items.loadState.refresh, items.itemCount) {
        if (restoredAnchor == null) {
            if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
        } else if (isRestoringScroll && state.isAtRoot && state.searchText.isBlank()) {
            val index = restoredAnchor.index.coerceIn(0, libraryCategories.lastIndex)
            listState.scrollToItem(index, restoredAnchor.offsetPx)
            isRestoringScroll = false
        } else if (
            isRestoringScroll &&
            (items.itemCount > 0 || hasObservedRefresh && items.loadState.refresh is LoadState.NotLoading)
        ) {
            val index = restoredAnchor.index.coerceAtMost((items.itemCount - 1).coerceAtLeast(0))
            if (items.itemCount > 0) listState.scrollToItem(index, restoredAnchor.offsetPx)
            isRestoringScroll = false
        }
    }
    LaunchedEffect(state.locationStack, state.searchText, items, listState) {
        snapshotFlow {
            if (isRestoringScroll) {
                null
            } else {
                val index = listState.firstVisibleItemIndex
                val itemId = if (state.isAtRoot && state.searchText.isBlank()) {
                    libraryCategories.getOrNull(index)?.query?.name
                } else if (index in 0 until items.itemCount) {
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
                persistState(state.locationStack, state.searchText, anchor)
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.searchText.isBlank()) state.location.title else "Search")
                },
                navigationIcon = {
                    if (!state.isAtRoot && state.searchText.isBlank()) {
                        TextButton(onClick = viewModel::navigateBack) { Text("Back") }
                    }
                },
                actions = {
                    TextButton(onClick = onChangePlayer) { Text("Players") }
                    TextButton(onClick = onChangeServer) { Text("Servers") }
                }
            )
        },
        bottomBar = {
            PlayerStrip(
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
            OutlinedTextField(
                value = state.searchText,
                onValueChange = viewModel::setSearchText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Search artists, albums, and tracks") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = {
                    if (state.searchText.isNotEmpty()) {
                        TextButton(onClick = { viewModel.setSearchText("") }) { Text("Clear") }
                    }
                }
            )
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
            if (state.isAtRoot && state.searchText.isBlank()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState
                ) {
                    items(libraryCategories, key = { it.query.name }) { category ->
                        ListItem(
                            modifier = Modifier.clickable { viewModel.openCategory(category) },
                            headlineContent = {
                                Text(category.title, fontWeight = FontWeight.Medium)
                            },
                            trailingContent = { Text("Browse") }
                        )
                    }
                }
            } else {
                LibraryResults(
                    items = items,
                    listState = listState,
                    activePlayer = state.activePlayer,
                    isPlayingItem = state.isPlayingItem,
                    onOpenItem = viewModel::openItem,
                    onPlayNow = viewModel::playNow,
                    onRetry = items::retry
                )
            }
        }
    }

}

@Composable
private fun LibraryResults(
    items: LazyPagingItems<LmsLibraryItem>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    activePlayer: LmsActivePlayer?,
    isPlayingItem: Boolean,
    onOpenItem: (LmsLibraryItem) -> Unit,
    onPlayNow: (LmsLibraryItem) -> Unit,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)
    ) {
        when (val refresh = items.loadState.refresh) {
            LoadState.Loading -> if (items.itemCount == 0) item { LoadingRow() }
            is LoadState.Error -> if (items.itemCount == 0) item {
                ErrorRow(refresh.error.message ?: "Could not load the library.", onRetry)
            }
            is LoadState.NotLoading -> if (items.itemCount == 0) {
                item {
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
            key = items.itemKey { "${it.type}:${it.id}" }
        ) { index ->
            val item = items[index]
            if (item != null) {
                LibraryItemRow(
                    item = item,
                    connection = activePlayer?.connection,
                    isPlayingItem = isPlayingItem,
                    onOpen = { onOpenItem(item) },
                    onPlayNow = { onPlayNow(item) }
                )
            } else {
                LoadingRow()
            }
        }
        when (val append = items.loadState.append) {
            LoadState.Loading -> item { LoadingRow() }
            is LoadState.Error -> item {
                ErrorRow(append.error.message ?: "Could not load more results.", onRetry)
            }
            is LoadState.NotLoading -> Unit
        }
    }
}

@Composable
private fun LibraryItemRow(
    item: LmsLibraryItem,
    connection: LmsServerConnection?,
    isPlayingItem: Boolean,
    onOpen: () -> Unit,
    onPlayNow: () -> Unit
) {
    val context = LocalContext.current
    val imageRequest = remember(item, connection) {
        connection?.artworkRequest(context, item)
    }
    val canPlay = !item.isContainer && item.type in setOf(
        LmsLibraryItemType.ALBUM,
        LmsLibraryItemType.TRACK,
        LmsLibraryItemType.PLAYLIST
    )
    val subtitle = listOfNotNull(
        item.type.displayName(),
        item.artist,
        item.album,
        item.durationSeconds?.let(::formatDuration)
    ).joinToString(" · ")

    ListItem(
        modifier = Modifier.clickable {
            if (item.type == LmsLibraryItemType.TRACK) onPlayNow() else onOpen()
        },
        leadingContent = {
            if (imageRequest != null) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = "${item.title} artwork",
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Text("LP", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        headlineContent = { Text(item.title, maxLines = 1) },
        supportingContent = {
            Text(subtitle, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            if (canPlay) {
                TextButton(onClick = onPlayNow, enabled = !isPlayingItem) { Text("Play") }
            } else {
                TextButton(onClick = onOpen) { Text(if (item.isContainer) "Open" else "Browse") }
            }
        }
    )
}

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
    if (activePlayer == null) {
        Surface(modifier = modifier, tonalElevation = 2.dp) {
            TextButton(onClick = onChangePlayer, modifier = Modifier.fillMaxWidth()) {
                Text("Select a player")
            }
        }
        return
    }
    val controlsEnabled = !isSendingCommand
    val playback = rememberPlayerStripState(mediaPlayer, status, activePlayer.playerName)
    Surface(modifier = modifier, tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f).clickable(onClick = onExpand),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(activePlayer.playerName, style = MaterialTheme.typography.labelSmall)
                    Text(
                        playback.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1
                    )
                    playback.artist?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
                TextButton(
                    onClick = {
                        if (mediaPlayer == null) onCommand(LmsPlayerCommand.Previous)
                        else mediaPlayer.seekToPreviousMediaItem()
                    },
                    enabled = controlsEnabled
                ) { Text("Prev") }
                TextButton(
                    onClick = {
                        if (mediaPlayer == null) {
                            onCommand(
                                if (playback.isPlaying) LmsPlayerCommand.Pause
                                else LmsPlayerCommand.Play
                            )
                        } else if (playback.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
                    },
                    enabled = controlsEnabled
                ) { Text(if (playback.isPlaying) "Pause" else "Play") }
                TextButton(
                    onClick = {
                        if (mediaPlayer == null) onCommand(LmsPlayerCommand.Next)
                        else mediaPlayer.seekToNextMediaItem()
                    },
                    enabled = controlsEnabled
                ) { Text("Next") }
            }
            if (playback.canSeek && playback.durationMs != null) {
                var position by remember(playback.durationMs) {
                    mutableFloatStateOf(playback.positionMs / 1_000f)
                }
                var isSeeking by remember(playback.durationMs) { mutableStateOf(false) }
                LaunchedEffect(playback.positionMs, playback.durationMs) {
                    if (!isSeeking) position = playback.positionMs / 1_000f
                }
                Slider(
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
                    enabled = controlsEnabled
                )
            }
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
        positionMs = this?.currentPosition ?: ((status?.positionSeconds ?: 0.0) * 1_000).toLong(),
        durationMs = mediaDuration ?: status?.durationSeconds?.let { (it * 1_000).toLong() },
        canSeek = if (hasCurrentItem) {
            isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        } else {
            status?.canSeek == true
        }
    )
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
                TextButton(
                    onClick = {
                        onCommand(
                            if (status?.mode == LmsPlaybackMode.PLAYING) {
                                LmsPlayerCommand.Pause
                            } else {
                                LmsPlayerCommand.Play
                            }
                        )
                    }
                ) { Text(if (status?.mode == LmsPlaybackMode.PLAYING) "Pause" else "Play") }
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

private fun LmsServerConnection.artworkRequest(
    context: android.content.Context,
    item: LmsLibraryItem
): ImageRequest? {
    val serverUri = URI(jsonRpcUrl())
    val targetUri = item.artworkUrl?.let { runCatching { URI(it) }.getOrNull() }
        ?: item.artworkId
            ?.takeIf { it.toLongOrNull() != null }
            ?.let { serverUri.resolve("/music/$it/cover") }
        ?: return null
    val sameOrigin = serverUri.scheme.equals(targetUri.scheme, ignoreCase = true) &&
        serverUri.host.equals(targetUri.host, ignoreCase = true) &&
        serverUri.effectivePort() == targetUri.effectivePort()
    val authorization = if (sameOrigin) basicAuthorizationHeader() else null
    return ImageRequest.Builder(context)
        .data(targetUri.toASCIIString())
        .crossfade(true)
        .apply {
            authorization?.let { addHeader(HttpHeaders.Authorization, it) }
        }
        .build()
}

private fun URI.effectivePort(): Int = if (port != -1) port else if (scheme == "https") 443 else 80

private fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds.toInt().coerceAtLeast(0)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}