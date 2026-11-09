package com.ryanmorrisroe.lyriamp.feature.connection

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlaybackMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerCommand
import com.ryanmorrisroe.lyriamp.data.lms.LmsRepeatMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleMode

@Composable
@SuppressLint("InlinedApi")
fun ConnectionScreen(viewModel: ConnectionViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showLocalNetworkRationale by remember { mutableStateOf(false) }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.testConnection()
        else viewModel.onLocalNetworkPermissionDenied()
    }

    fun testConnection() {
        if (
            Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showLocalNetworkRationale = true
        } else {
            viewModel.testConnection()
        }
    }

    if (showLocalNetworkRationale) {
        AlertDialog(
            onDismissRequest = { showLocalNetworkRationale = false },
            title = { Text("Connect to your LMS") },
            text = {
                Text("Lyriamp needs local-network access to contact the server address you provide. It does not scan for servers.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLocalNetworkRationale = false
                        localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocalNetworkRationale = false }) {
                    Text("Not now")
                }
            }
        )
    }

    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "LYRIAMP",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Connect to your server",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Lyrion Music Server",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.savedServers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Saved servers", style = MaterialTheme.typography.titleSmall)
                    state.savedServers.forEach { profile ->
                        ListItem(
                            modifier = Modifier.clickable(
                                enabled = !state.isLoadingSavedServer &&
                                    state.removingSavedServerId != profile.id
                            ) { viewModel.loadSavedServer(profile.id) },
                            headlineContent = { Text(profile.host) },
                            supportingContent = {
                                Text(
                                    "${if (profile.useHttps) "HTTPS" else "HTTP"} · Port ${profile.port} · " +
                                        if (profile.hasCredentials) "Credentials saved" else "No credentials"
                                )
                            },
                            trailingContent = {
                                TextButton(
                                    onClick = { viewModel.removeSavedServer(profile.id) },
                                    enabled = state.removingSavedServerId == null
                                ) {
                                    Text(
                                        if (state.removingSavedServerId == profile.id) {
                                            "Removing..."
                                        } else {
                                            "Remove"
                                        }
                                    )
                                }
                            }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Transport", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !state.useHttps,
                        onClick = { viewModel.onHttpsChanged(false) },
                        label = { Text("HTTP") }
                    )
                    FilterChip(
                        selected = state.useHttps,
                        onClick = { viewModel.onHttpsChanged(true) },
                        label = { Text("HTTPS") }
                    )
                }
                if (!state.useHttps) {
                    Text(
                        "HTTP is unencrypted. Use it only on a trusted local network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = viewModel::onHostChanged,
                    modifier = Modifier.weight(1f),
                    label = { Text("Host or IP address") },
                    placeholder = { Text("lms.local") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::onPortChanged,
                    modifier = Modifier.width(112.dp),
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Authentication", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username (optional)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
            }

            Button(
                onClick = ::testConnection,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isConnecting
            ) {
                Text(if (state.isConnecting) "Connecting..." else "Test connection")
            }

            OutlinedButton(
                onClick = viewModel::saveServer,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSavingServer && state.host.isNotBlank()
            ) {
                Text(if (state.isSavingServer) "Saving..." else "Save server")
            }

            state.savedServerMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
            state.savedServerErrorMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            state.serverInfo?.let { serverInfo ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Connected to LMS ${serverInfo.version}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${serverInfo.playerCount} players available",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Text("Players", style = MaterialTheme.typography.titleSmall)
                    if (state.players.isEmpty()) {
                        Text(
                            "No players are currently listed by this server.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.players.forEach { player ->
                            ListItem(
                                modifier = Modifier.clickable(enabled = player.connected) {
                                    viewModel.selectPlayer(player.id)
                                },
                                headlineContent = { Text(player.name) },
                                supportingContent = {
                                    Text("${player.modelName} · ${if (player.connected) "Connected" else "Offline"}")
                                },
                                trailingContent = {
                                    Switch(
                                        checked = player.power,
                                        onCheckedChange = { viewModel.setPlayerPower(player.id, it) },
                                        enabled = (player.connected || !player.power) &&
                                            state.powerUpdatingPlayerId == null
                                    )
                                }
                            )
                        }
                    }
                    state.playerPowerErrorMessage?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    state.selectedPlayerId?.let { selectedPlayerId ->
                        val player = state.players.firstOrNull { it.id == selectedPlayerId }
                        if (player != null) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(player.name, style = MaterialTheme.typography.titleMedium)
                                    if (state.isLoadingPlayerStatus) {
                                        CircularProgressIndicator()
                                    }
                                    state.playerStatus?.let { status ->
                                        Text(
                                            status.title ?: "Nothing playing",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        val subtitle = listOfNotNull(status.artist, status.album)
                                            .joinToString(" · ")
                                        if (subtitle.isNotEmpty()) {
                                            Text(
                                                subtitle,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (status.canSeek && status.durationSeconds != null && status.durationSeconds > 0) {
                                            var seekPosition by remember(status) {
                                                mutableFloatStateOf(status.positionSeconds.toFloat())
                                            }
                                            Slider(
                                                value = seekPosition.coerceIn(0f, status.durationSeconds.toFloat()),
                                                onValueChange = { seekPosition = it },
                                                onValueChangeFinished = {
                                                    viewModel.sendPlayerCommand(
                                                        LmsPlayerCommand.SeekTo(seekPosition.toInt())
                                                    )
                                                },
                                                valueRange = 0f..status.durationSeconds.toFloat(),
                                                enabled = player.connected && !state.isSendingPlayerCommand
                                            )
                                            Text(
                                                "${formatTime(seekPosition.toInt())} / ${formatTime(status.durationSeconds.toInt())}",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                        val controlsEnabled = player.connected &&
                                            !state.isSendingPlayerCommand && !state.isLoadingPlayerStatus
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    viewModel.sendPlayerCommand(LmsPlayerCommand.Previous)
                                                },
                                                enabled = controlsEnabled
                                            ) {
                                                Text("Previous")
                                            }
                                            Button(
                                                onClick = {
                                                    val command = if (status.mode == LmsPlaybackMode.PLAYING) {
                                                        LmsPlayerCommand.Pause
                                                    } else {
                                                        LmsPlayerCommand.Play
                                                    }
                                                    viewModel.sendPlayerCommand(command)
                                                },
                                                enabled = controlsEnabled
                                            ) {
                                                Text(if (status.mode == LmsPlaybackMode.PLAYING) "Pause" else "Play")
                                            }
                                            TextButton(
                                                onClick = {
                                                    viewModel.sendPlayerCommand(LmsPlayerCommand.Next)
                                                },
                                                enabled = controlsEnabled
                                            ) {
                                                Text("Next")
                                            }
                                        }
                                        TextButton(
                                            onClick = {
                                                viewModel.sendPlayerCommand(LmsPlayerCommand.Stop)
                                            },
                                            enabled = controlsEnabled
                                        ) {
                                            Text("Stop")
                                        }
                                    }
                                    state.playerMixerState?.let { mixerState ->
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "Volume",
                                                    modifier = Modifier.weight(1f),
                                                    style = MaterialTheme.typography.titleSmall
                                                )
                                                Text(
                                                    "${mixerState.volumePercent}%",
                                                    style = MaterialTheme.typography.labelLarge
                                                )
                                                TextButton(
                                                    onClick = {
                                                        viewModel.sendPlayerCommand(
                                                            LmsPlayerCommand.SetMuted(!mixerState.isMuted)
                                                        )
                                                    },
                                                    enabled = player.connected &&
                                                        !state.isSendingPlayerCommand &&
                                                        !state.isLoadingMixerState
                                                ) {
                                                    Text(if (mixerState.isMuted) "Unmute" else "Mute")
                                                }
                                            }
                                            var volume by remember(mixerState.volumePercent) {
                                                mutableFloatStateOf(mixerState.volumePercent.toFloat())
                                            }
                                            Slider(
                                                value = volume,
                                                onValueChange = { volume = it },
                                                onValueChangeFinished = {
                                                    viewModel.sendPlayerCommand(
                                                        LmsPlayerCommand.SetVolume(volume.roundToInt())
                                                    )
                                                },
                                                valueRange = 0f..100f,
                                                enabled = player.connected &&
                                                    !state.isSendingPlayerCommand &&
                                                    !state.isLoadingMixerState
                                            )
                                        }
                                    }
                                    if (state.isLoadingMixerState && state.playerMixerState == null) {
                                        Text("Reading volume...", style = MaterialTheme.typography.bodySmall)
                                    }
                                    state.playerMixerErrorMessage?.let { message ->
                                        Text(
                                            message,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    state.playerPlaybackOptions?.let { options ->
                                        val optionsEnabled = player.connected &&
                                            !state.isSendingPlayerCommand &&
                                            !state.isLoadingPlaybackOptions
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("Shuffle", style = MaterialTheme.typography.titleSmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                LmsShuffleMode.entries.forEach { mode ->
                                                    FilterChip(
                                                        selected = options.shuffleMode == mode,
                                                        onClick = {
                                                            viewModel.sendPlayerCommand(
                                                                LmsPlayerCommand.SetShuffleMode(mode)
                                                            )
                                                        },
                                                        enabled = optionsEnabled,
                                                        label = {
                                                            Text(
                                                                when (mode) {
                                                                    LmsShuffleMode.OFF -> "Off"
                                                                    LmsShuffleMode.SONGS -> "Songs"
                                                                    LmsShuffleMode.ALBUMS -> "Albums"
                                                                }
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                            Text("Repeat", style = MaterialTheme.typography.titleSmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                LmsRepeatMode.entries.forEach { mode ->
                                                    FilterChip(
                                                        selected = options.repeatMode == mode,
                                                        onClick = {
                                                            viewModel.sendPlayerCommand(
                                                                LmsPlayerCommand.SetRepeatMode(mode)
                                                            )
                                                        },
                                                        enabled = optionsEnabled,
                                                        label = {
                                                            Text(
                                                                when (mode) {
                                                                    LmsRepeatMode.OFF -> "Off"
                                                                    LmsRepeatMode.TRACK -> "Track"
                                                                    LmsRepeatMode.PLAYLIST -> "Playlist"
                                                                }
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (state.isLoadingPlaybackOptions && state.playerPlaybackOptions == null) {
                                        Text(
                                            "Reading playback options...",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    state.playerPlaybackOptionsErrorMessage?.let { message ->
                                        Text(
                                            message,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    state.playerErrorMessage?.let { message ->
                                        Text(
                                            message,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(seconds: Int): String =
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"