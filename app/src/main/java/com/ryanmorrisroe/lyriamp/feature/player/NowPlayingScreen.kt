@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ryanmorrisroe.lyriamp.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlaybackMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerCommand
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerStatus
import com.ryanmorrisroe.lyriamp.data.lms.LmsQueueItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsRepeatMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleMode
import com.ryanmorrisroe.lyriamp.feature.connection.ConnectionViewModel
import com.ryanmorrisroe.lyriamp.ui.artwork.LmsArtworkImage
import com.ryanmorrisroe.lyriamp.ui.glyph.BackArrowIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NowPlayingScreen(
    player: Player?,
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onChoosePlayer: () -> Unit,
    onAddToQueue: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit
) {
    val connectionState by connectionViewModel.uiState.collectAsStateWithLifecycle()
    val status = connectionState.playerStatus
    LaunchedEffect(connectionState.activeConnection, connectionState.selectedPlayerId) {
        if (connectionState.activeConnection != null && connectionState.selectedPlayerId != null) {
            while (true) {
                connectionViewModel.refreshPlayerStatus()
                delay(1_000)
            }
        }
    }

    val mediaItem = player?.currentMediaItem
    val metadata = mediaItem?.mediaMetadata
    val currentQueueIndex = status?.playlistIndex ?: connectionState.playerQueueCurrentIndex
    val currentQueueItem = connectionState.playerQueue.firstOrNull {
        it.index == currentQueueIndex
    }
    val durationMs = player?.duration
        ?.takeIf { it != C.TIME_UNSET && it > 0 }
        ?: status?.durationSeconds?.takeIf { it > 0 }?.times(1_000)?.toLong()
    val positionMs = status?.positionSeconds?.times(1_000)?.toLong()
        ?: player?.currentPosition
        ?: 0L
    val isPlaying = player?.isPlaying == true ||
        (mediaItem == null && status?.mode == LmsPlaybackMode.PLAYING)
    val title = metadata?.title?.toString()?.takeIf(String::isNotBlank)
        ?: status?.title?.takeIf(String::isNotBlank)
        ?: currentQueueItem?.title
        ?: "Nothing playing"
    val artist = metadata?.artist?.toString()?.takeIf(String::isNotBlank)
        ?: status?.artist?.takeIf(String::isNotBlank)
        ?: currentQueueItem?.artist?.takeIf(String::isNotBlank)
    val album = metadata?.albumTitle?.toString()?.takeIf(String::isNotBlank)
        ?: status?.album?.takeIf(String::isNotBlank)
        ?: currentQueueItem?.album?.takeIf(String::isNotBlank)
    val artworkUri = metadata?.artworkUri?.toString()
    val currentPlayerName = connectionState.players
        .firstOrNull { it.id == connectionState.selectedPlayerId }
        ?.name
        ?.takeIf(String::isNotBlank)
    val scrollState = rememberScrollState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Now Playing") },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.semantics { contentDescription = "Back" }
                        ) {
                            BackArrowIcon(
                                MaterialTheme.colorScheme.onSurface,
                                Modifier.size(24.dp)
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = onChoosePlayer,
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "Change player, ${currentPlayerName ?: "Players"}"
                            }
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PlayerGlyphIcon(
                                    PlayerGlyph.SPEAKER,
                                    MaterialTheme.colorScheme.onSurface,
                                    Modifier.size(24.dp)
                                )
                                Text(currentPlayerName ?: "Players", maxLines = 1)
                            }
                        }
                    }
                )
                NowPlayingDismissHandle(onDismiss = onBack)
            }
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .testTag("now-playing-screen")
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().size(300.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                LmsArtworkImage(
                    connection = connectionState.activeConnection,
                    artworkId = if (artworkUri == null) status?.artworkId else null,
                    artworkUrl = artworkUri ?: status?.artworkUrl,
                    contentDescription = "Album artwork",
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = title.take(1).uppercase(),
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                artist?.takeIf(String::isNotBlank)?.let {
                    TextButton(onClick = { onOpenArtist(it) }) {
                        Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                }
                album?.takeIf(String::isNotBlank)?.let {
                    TextButton(onClick = { onOpenAlbum(it) }) {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            if (durationMs != null) {
                var seekPosition by remember(player, mediaItem?.mediaId, durationMs) {
                    mutableFloatStateOf(positionMs / 1_000f)
                }
                var isSeeking by remember(player, mediaItem?.mediaId, durationMs) {
                    mutableStateOf(false)
                }
                LaunchedEffect(positionMs, durationMs) {
                    if (!isSeeking) seekPosition = positionMs / 1_000f
                }
                Slider(
                    value = seekPosition.coerceIn(0f, durationMs / 1_000f),
                    onValueChange = {
                        isSeeking = true
                        seekPosition = it
                    },
                    onValueChangeFinished = {
                        if (player == null) {
                            connectionViewModel.sendPlayerCommand(LmsPlayerCommand.SeekTo(seekPosition.toInt()))
                        } else {
                            player.seekTo((seekPosition * 1_000).toLong())
                        }
                        isSeeking = false
                    },
                    valueRange = 0f..(durationMs / 1_000f),
                    enabled = !connectionState.isSendingPlayerCommand
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime((positionMs / 1_000).toInt()), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime((durationMs / 1_000).toInt()), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (player == null) connectionViewModel.sendPlayerCommand(LmsPlayerCommand.Previous)
                        else player.seekToPreviousMediaItem()
                    },
                    modifier = Modifier.semantics { contentDescription = "Previous" }
                ) {
                    PlayerGlyphIcon(
                        PlayerGlyph.PREVIOUS,
                        MaterialTheme.colorScheme.onSurface,
                        Modifier.size(28.dp)
                    )
                }
                FilledTonalButton(
                    onClick = {
                        if (player == null) {
                            connectionViewModel.sendPlayerCommand(
                                if (isPlaying) LmsPlayerCommand.Pause else LmsPlayerCommand.Play
                            )
                        } else if (isPlaying) player.pause() else player.play()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    }
                ) {
                    PlayerGlyphIcon(
                        if (isPlaying) PlayerGlyph.PAUSE else PlayerGlyph.PLAY,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                        Modifier.size(28.dp)
                    )
                }
                IconButton(
                    onClick = {
                        if (player == null) connectionViewModel.sendPlayerCommand(LmsPlayerCommand.Next)
                        else player.seekToNextMediaItem()
                    },
                    modifier = Modifier.semantics { contentDescription = "Next" }
                ) {
                    PlayerGlyphIcon(
                        PlayerGlyph.NEXT,
                        MaterialTheme.colorScheme.onSurface,
                        Modifier.size(28.dp)
                    )
                }
            }
            connectionState.playerPlaybackOptions?.let { options ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = options.shuffleMode != LmsShuffleMode.OFF,
                        onClick = {
                            val next = when (options.shuffleMode) {
                                LmsShuffleMode.OFF -> LmsShuffleMode.SONGS
                                LmsShuffleMode.SONGS -> LmsShuffleMode.ALBUMS
                                LmsShuffleMode.ALBUMS -> LmsShuffleMode.OFF
                            }
                            connectionViewModel.sendPlayerCommand(LmsPlayerCommand.SetShuffleMode(next))
                        },
                        enabled = !connectionState.isSendingPlayerCommand,
                        leadingIcon = {
                            PlayerGlyphIcon(
                                PlayerGlyph.SHUFFLE,
                                MaterialTheme.colorScheme.onSurface,
                                Modifier.size(18.dp)
                            )
                        },
                        label = { Text("Shuffle · ${options.shuffleMode.displayName()}") }
                    )
                    FilterChip(
                        selected = options.repeatMode != LmsRepeatMode.OFF,
                        onClick = {
                            val next = when (options.repeatMode) {
                                LmsRepeatMode.OFF -> LmsRepeatMode.TRACK
                                LmsRepeatMode.TRACK -> LmsRepeatMode.PLAYLIST
                                LmsRepeatMode.PLAYLIST -> LmsRepeatMode.OFF
                            }
                            connectionViewModel.sendPlayerCommand(LmsPlayerCommand.SetRepeatMode(next))
                        },
                        enabled = !connectionState.isSendingPlayerCommand,
                        leadingIcon = {
                            PlayerGlyphIcon(
                                if (options.repeatMode == LmsRepeatMode.TRACK) {
                                    PlayerGlyph.REPEAT_ONE
                                } else {
                                    PlayerGlyph.REPEAT
                                },
                                MaterialTheme.colorScheme.onSurface,
                                Modifier.size(18.dp)
                            )
                        },
                        label = { Text("Repeat · ${options.repeatMode.displayName()}") }
                    )
                }
            }
            QueuePreview(
                items = connectionState.playerQueue,
                totalCount = connectionState.playerQueueTotalCount,
                currentIndex = connectionState.playerQueueCurrentIndex ?: status?.playlistIndex,
                connection = connectionState.activeConnection,
                isLoading = connectionState.isLoadingPlayerQueue,
                isMutating = connectionState.isMutatingPlayerQueue,
                errorMessage = connectionState.playerQueueErrorMessage,
                onAddToQueue = onAddToQueue,
                onOpenQueue = onOpenQueue,
                onRemove = connectionViewModel::removePlayerQueueItem
            )
            connectionState.playerMixerState?.let { mixer ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Volume · ${mixer.volumePercent}%", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = {
                                connectionViewModel.sendPlayerCommand(LmsPlayerCommand.SetMuted(!mixer.isMuted))
                            }, enabled = !connectionState.isSendingPlayerCommand) {
                                Text(if (mixer.isMuted) "Unmute" else "Mute")
                            }
                        }
                        var volume by remember(mixer.volumePercent) {
                            mutableFloatStateOf(mixer.volumePercent.toFloat())
                        }
                        Slider(
                            value = volume,
                            onValueChange = { volume = it },
                            onValueChangeFinished = {
                                connectionViewModel.sendPlayerCommand(LmsPlayerCommand.SetVolume(volume.toInt()))
                            },
                            valueRange = 0f..100f,
                            enabled = !connectionState.isSendingPlayerCommand
                        )
                    }
                }
            }
            connectionState.playerErrorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            connectionState.playerMixerErrorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun NowPlayingDismissHandle(onDismiss: () -> Unit) {
    val dismissalThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(dismissalThreshold) {
                var cumulativeDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = { cumulativeDrag = 0f },
                    onDragEnd = {
                        val shouldDismiss = cumulativeDrag >= dismissalThreshold
                        cumulativeDrag = 0f
                        if (shouldDismiss) latestOnDismiss()
                    },
                    onDragCancel = { cumulativeDrag = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        cumulativeDrag += dragAmount
                        change.consume()
                    }
                )
            }
            .testTag("now-playing-dismiss-handle")
            .semantics {
                contentDescription = "Swipe down here to collapse Now Playing"
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(width = 36.dp, height = 4.dp),
            shape = RoundedCornerShape(2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        ) {}
    }
}

@Composable
private fun QueuePreview(
    items: List<LmsQueueItem>,
    totalCount: Int,
    currentIndex: Int?,
    connection: com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection?,
    isLoading: Boolean,
    isMutating: Boolean,
    errorMessage: String?,
    onAddToQueue: () -> Unit,
    onOpenQueue: () -> Unit,
    onRemove: (Int) -> Unit
) {
    val upcoming = items.filter { currentIndex == null || it.index > currentIndex }
    val upcomingCount = (totalCount - (currentIndex?.plus(1) ?: 0)).coerceAtLeast(0)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Up next", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$upcomingCount tracks in queue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onAddToQueue) { Text("+ Add") }
            }
            if (isLoading && items.isEmpty()) {
                Text("Loading queue...", style = MaterialTheme.typography.bodySmall)
            } else {
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (upcoming.isEmpty()) {
                    Text("Nothing queued up next.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    upcoming.take(2).forEach { item ->
                        QueueItemRow(
                            item = item,
                            connection = connection,
                            isMutating = isMutating,
                            onRemove = { onRemove(item.index) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onOpenQueue) { Text("Full queue >") }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerQueueScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onAddToQueue: () -> Unit
) {
    val state by connectionViewModel.uiState.collectAsStateWithLifecycle()
    val status = state.playerStatus
    val currentIndex = state.playerQueueCurrentIndex ?: status?.playlistIndex
    val upcoming = state.playerQueue.filter {
        currentIndex == null || it.index > currentIndex
    }
    val upcomingCount = (state.playerQueueTotalCount -
        (currentIndex?.plus(1) ?: 0)).coerceAtLeast(0)
    val hasMore = state.playerQueueOffset + state.playerQueue.size < state.playerQueueTotalCount
    val scope = rememberCoroutineScope()
    val queueListState = rememberLazyListState()
    val dragHandleHeightPx = with(LocalDensity.current) { 32.dp.roundToPx() }
    var draggedItemIndex by remember { mutableIntStateOf(-1) }
    var dropTargetIndex by remember { mutableIntStateOf(-1) }
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isDragMoving by remember { mutableStateOf(false) }

    fun clearDrag() {
        draggedItemIndex = -1
        dropTargetIndex = -1
        dragOffsetY = 0f
        isDragMoving = false
    }

    fun itemIndexAt(y: Float): Int = queueListState.layoutInfo.visibleItemsInfo
        .filter { it.key is Int }
        .let { visibleItems ->
            visibleItems.firstOrNull { y >= it.offset && y < it.offset + it.size }
                ?: visibleItems.minByOrNull { kotlin.math.abs(y - (it.offset + it.size / 2f)) }
        }
        ?.key as? Int ?: -1

    LaunchedEffect(draggedItemIndex, isDragMoving) {
        if (draggedItemIndex < 0 || !isDragMoving) return@LaunchedEffect
        while (true) {
            val layoutInfo = queueListState.layoutInfo
            val scrollAmount = when {
                dragPointerY < layoutInfo.viewportStartOffset + 48f -> -20f
                dragPointerY > layoutInfo.viewportEndOffset - 48f -> 20f
                else -> 0f
            }
            if (scrollAmount != 0f) {
                queueListState.scrollBy(scrollAmount)
                dropTargetIndex = itemIndexAt(dragPointerY)
            }
            delay(16)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queue") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back" }
                    ) {
                        BackArrowIcon(
                            MaterialTheme.colorScheme.onSurface,
                            Modifier.size(24.dp)
                        )
                    }
                },
                actions = { TextButton(onClick = onAddToQueue) { Text("+ Add") } }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val currentQueueItem = state.playerQueue.firstOrNull {
                it.index == currentIndex
            }
            val currentTitle = status?.title?.takeIf(String::isNotBlank) ?: currentQueueItem?.title
            if (currentTitle != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Now playing", style = MaterialTheme.typography.labelMedium)
                        Text(currentTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        (status?.artist?.takeIf(String::isNotBlank) ?: currentQueueItem?.artist)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$upcomingCount tracks up next", style = MaterialTheme.typography.titleSmall)
                if (state.isMutatingPlayerQueue) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            state.playerQueueErrorMessage?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        message,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = {
                        scope.launch { connectionViewModel.refreshPlayerQueue() }
                    }) { Text("Retry") }
                }
            }
            if (state.isLoadingPlayerQueue && state.playerQueue.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (upcoming.isEmpty()) {
                Text(
                    "Nothing queued up next.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = queueListState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    itemsIndexed(upcoming, key = { _, item -> item.index }) { _, item ->
                        QueueItemRow(
                            item = item,
                            connection = state.activeConnection,
                            isMutating = state.isMutatingPlayerQueue || state.isLoadingPlayerQueue,
                            showDragHandle = true,
                            dragEnabled = !state.isMutatingPlayerQueue && !state.isLoadingPlayerQueue,
                            onDragStart = { index, localY ->
                                draggedItemIndex = index
                                dropTargetIndex = index
                                val itemInfo = queueListState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == index }
                                val handleOffset = (
                                    (itemInfo?.size ?: dragHandleHeightPx) - dragHandleHeightPx
                                    ) / 2f
                                dragPointerY = (itemInfo?.offset ?: 0) + handleOffset + localY
                                dragOffsetY = 0f
                                isDragMoving = false
                            },
                            onDrag = { dragAmount ->
                                dragOffsetY += dragAmount
                                dragPointerY += dragAmount
                                dropTargetIndex = itemIndexAt(dragPointerY)
                                isDragMoving = true
                            },
                            onDragEnd = {
                                val fromIndex = draggedItemIndex
                                val toIndex = dropTargetIndex
                                if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                                    connectionViewModel.movePlayerQueueItem(fromIndex, toIndex)
                                }
                                clearDrag()
                            },
                            isDragging = draggedItemIndex == item.index,
                            dragOffsetY = dragOffsetY,
                            isDropTarget = dropTargetIndex == item.index &&
                                draggedItemIndex != item.index,
                            onRemove = { connectionViewModel.removePlayerQueueItem(item.index) }
                        )
                    }
                    if (hasMore) {
                        item(key = "queue-load-more") {
                            TextButton(
                                onClick = connectionViewModel::loadMorePlayerQueue,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.isLoadingPlayerQueue && !state.isMutatingPlayerQueue
                            ) {
                                Text(if (state.isLoadingPlayerQueue) "Loading..." else "Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemRow(
    item: LmsQueueItem,
    connection: LmsServerConnection?,
    isMutating: Boolean,
    showDragHandle: Boolean = false,
    dragEnabled: Boolean = false,
    onDragStart: (Int, Float) -> Unit = { _, _ -> },
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    isDragging: Boolean = false,
    dragOffsetY: Float = 0f,
    isDropTarget: Boolean = false,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .graphicsLayer {
                translationY = if (isDragging) dragOffsetY else 0f
                alpha = if (isDragging) 0.9f else 1f
            }
            .zIndex(if (isDragging) 1f else 0f)
            .background(
                color = if (isDragging) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
                shape = shape
            )
            .border(
                width = if (isDropTarget) 1.dp else 0.dp,
                color = if (isDropTarget) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDragHandle) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .padding(end = 4.dp)
                    .pointerInput(item.index, isMutating, dragEnabled) {
                        if (dragEnabled) {
                            detectDragGestures(
                                onDragStart = { currentOnDragStart(item.index, it.y) },
                                onDragEnd = currentOnDragEnd,
                                onDragCancel = currentOnDragEnd,
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    currentOnDrag(dragAmount.y)
                                }
                            )
                        }
                    }
                    .semantics { contentDescription = "Drag ${item.title} to reorder" },
                contentAlignment = Alignment.Center
            ) {
                PlayerGlyphIcon(
                    PlayerGlyph.DRAG_HANDLE,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.size(20.dp)
                )
            }
        }
        Surface(
            modifier = Modifier
                .size(42.dp)
                .testTag("queue-artwork-${item.index}"),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            LmsArtworkImage(
                connection = connection,
                artworkId = item.artworkId,
                artworkUrl = item.artworkUrl,
                contentDescription = "${item.title} artwork",
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    item.title.take(1).uppercase(),
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            val subtitle = listOfNotNull(
                item.artist,
                item.album,
                item.durationSeconds?.let { formatTime(it.toInt()) }
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        IconButton(
            onClick = onRemove,
            enabled = !isMutating,
            modifier = Modifier.semantics {
                contentDescription = "Remove ${item.title} from queue"
            }
        ) {
            PlayerGlyphIcon(
                PlayerGlyph.CLOSE,
                MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.size(24.dp)
            )
        }
    }
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

private enum class PlayerGlyph {
    CLOSE,
    NEXT,
    PAUSE,
    PLAY,
    PREVIOUS,
    REPEAT,
    REPEAT_ONE,
    SHUFFLE,
    SPEAKER,
    DRAG_HANDLE
}

@Composable
private fun PlayerGlyphIcon(
    glyph: PlayerGlyph,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(
                color = color,
                start = Offset(x1 * unit, y1 * unit),
                end = Offset(x2 * unit, y2 * unit),
                strokeWidth = 2f * unit,
                cap = StrokeCap.Round
            )
        }

        fun rect(left: Float, top: Float, width: Float, height: Float) {
            drawRect(
                color = color,
                topLeft = Offset(left * unit, top * unit),
                size = androidx.compose.ui.geometry.Size(width * unit, height * unit)
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

        fun polyline(points: List<Offset>) {
            val path = Path().apply {
                moveTo(points.first().x * unit, points.first().y * unit)
                points.drop(1).forEach { point ->
                    lineTo(point.x * unit, point.y * unit)
                }
            }
            drawPath(
                path,
                color,
                style = Stroke(width = 2f * unit, cap = StrokeCap.Round)
            )
        }

        when (glyph) {
            PlayerGlyph.CLOSE -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
            PlayerGlyph.NEXT -> {
                triangle(Offset(5f, 5f), Offset(15f, 12f), Offset(5f, 19f))
                rect(18f, 5f, 2f, 14f)
            }
            PlayerGlyph.PAUSE -> {
                rect(6f, 5f, 4f, 14f)
                rect(14f, 5f, 4f, 14f)
            }
            PlayerGlyph.PLAY -> triangle(Offset(7f, 5f), Offset(19f, 12f), Offset(7f, 19f))
            PlayerGlyph.PREVIOUS -> {
                rect(4f, 5f, 2f, 14f)
                triangle(Offset(19f, 5f), Offset(9f, 12f), Offset(19f, 19f))
            }
            PlayerGlyph.REPEAT,
            PlayerGlyph.REPEAT_ONE -> {
                polyline(listOf(
                    Offset(5f, 9f),
                    Offset(5f, 7f),
                    Offset(7f, 5f),
                    Offset(17f, 5f),
                    Offset(19f, 7f)
                ))
                line(16f, 7f, 19f, 7f)
                line(19f, 7f, 19f, 10f)
                polyline(listOf(
                    Offset(19f, 15f),
                    Offset(19f, 17f),
                    Offset(17f, 19f),
                    Offset(7f, 19f),
                    Offset(5f, 17f)
                ))
                line(8f, 17f, 5f, 17f)
                line(5f, 17f, 5f, 14f)
                if (glyph == PlayerGlyph.REPEAT_ONE) {
                    line(12f, 10f, 12f, 15f)
                    line(10.5f, 15f, 13.5f, 15f)
                    line(11f, 11f, 12f, 10f)
                }
            }
            PlayerGlyph.SHUFFLE -> {
                polyline(listOf(
                    Offset(3f, 7f),
                    Offset(7f, 7f),
                    Offset(17f, 17f),
                    Offset(21f, 17f)
                ))
                line(18f, 14f, 21f, 17f)
                line(18f, 20f, 21f, 17f)
                polyline(listOf(
                    Offset(3f, 17f),
                    Offset(7f, 17f),
                    Offset(17f, 7f),
                    Offset(21f, 7f)
                ))
                line(18f, 4f, 21f, 7f)
                line(18f, 10f, 21f, 7f)
            }
            PlayerGlyph.SPEAKER -> {
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
            PlayerGlyph.DRAG_HANDLE -> {
                for (x in listOf(8f, 16f)) {
                    for (y in listOf(6f, 12f, 18f)) {
                        drawCircle(color, 1.5f * unit, Offset(x * unit, y * unit))
                    }
                }
            }
        }
    }
}

private fun formatTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
