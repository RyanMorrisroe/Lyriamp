@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ryanmorrisroe.lyriamp.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlaybackMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerCommand
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerStatus
import com.ryanmorrisroe.lyriamp.data.lms.LmsRepeatMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleMode
import com.ryanmorrisroe.lyriamp.feature.connection.ConnectionViewModel
import com.ryanmorrisroe.lyriamp.ui.artwork.LmsArtworkImage
import kotlinx.coroutines.delay

@Composable
fun NowPlayingScreen(
    player: Player?,
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onChoosePlayer: () -> Unit
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
    val durationMs = player?.duration
        ?.takeIf { it != C.TIME_UNSET && it > 0 }
        ?: status?.durationSeconds?.takeIf { it > 0 }?.times(1_000)?.toLong()
    val positionMs = status?.positionSeconds?.times(1_000)?.toLong()
        ?: player?.currentPosition
        ?: 0L
    val isPlaying = player?.isPlaying == true ||
        (mediaItem == null && status?.mode == LmsPlaybackMode.PLAYING)
    val title = metadata?.title?.toString()?.takeIf(String::isNotBlank)
        ?: status?.title
        ?: "Nothing playing"
    val artist = metadata?.artist?.toString()?.takeIf(String::isNotBlank) ?: status?.artist
    val album = metadata?.albumTitle?.toString()?.takeIf(String::isNotBlank) ?: status?.album
    val artworkUri = metadata?.artworkUri?.toString()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Playing") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = onChoosePlayer) { Text("Players") } }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
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
                artist?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                album?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                TextButton(onClick = {
                    if (player == null) connectionViewModel.sendPlayerCommand(LmsPlayerCommand.Previous)
                    else player.seekToPreviousMediaItem()
                }) { Text("Previous") }
                Button(onClick = {
                    if (player == null) {
                        connectionViewModel.sendPlayerCommand(
                            if (isPlaying) LmsPlayerCommand.Pause else LmsPlayerCommand.Play
                        )
                    } else if (isPlaying) player.pause() else player.play()
                }) { Text(if (isPlaying) "Pause" else "Play") }
                TextButton(onClick = {
                    if (player == null) connectionViewModel.sendPlayerCommand(LmsPlayerCommand.Next)
                    else player.seekToNextMediaItem()
                }) { Text("Next") }
            }
            connectionState.playerMixerState?.let { mixer ->
                Text("Volume · ${mixer.volumePercent}%", style = MaterialTheme.typography.titleSmall)
                var volume by remember(mixer.volumePercent) { mutableFloatStateOf(mixer.volumePercent.toFloat()) }
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    onValueChangeFinished = {
                        connectionViewModel.sendPlayerCommand(LmsPlayerCommand.SetVolume(volume.toInt()))
                    },
                    valueRange = 0f..100f,
                    enabled = !connectionState.isSendingPlayerCommand
                )
                TextButton(onClick = {
                    connectionViewModel.sendPlayerCommand(LmsPlayerCommand.SetMuted(!mixer.isMuted))
                }) { Text(if (mixer.isMuted) "Unmute" else "Mute") }
            }
            connectionState.playerPlaybackOptions?.let { options ->
                Text("Shuffle", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LmsShuffleMode.entries.forEach { mode ->
                        FilterChip(
                            selected = mode == options.shuffleMode,
                            onClick = { connectionViewModel.sendPlayerCommand(LmsPlayerCommand.SetShuffleMode(mode)) },
                            label = { Text(mode.displayName()) }
                        )
                    }
                }
                Text("Repeat", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LmsRepeatMode.entries.forEach { mode ->
                        FilterChip(
                            selected = mode == options.repeatMode,
                            onClick = { connectionViewModel.sendPlayerCommand(LmsPlayerCommand.SetRepeatMode(mode)) },
                            label = { Text(mode.displayName()) }
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

private fun formatTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
