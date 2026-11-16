@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ryanmorrisroe.lyriamp.feature.connection

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayer
import kotlinx.coroutines.launch

@Composable
fun ConnectionsScreen(
    viewModel: ConnectionViewModel,
    onAddServer: () -> Unit,
    onConnectSavedServer: (String) -> Unit,
    onRemoveSavedServer: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Connections") }) }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Saved servers", style = MaterialTheme.typography.titleMedium)
            if (state.savedServers.isEmpty()) {
                Text(
                    "No saved servers yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.savedServers.forEach { profile ->
                    ListItem(
                        modifier = Modifier.clickable(
                            enabled = state.removingSavedServerId != profile.id &&
                                !state.isLoadingSavedServer
                        ) { onConnectSavedServer(profile.id) },
                        headlineContent = { Text(profile.host) },
                        supportingContent = {
                            Text(
                                "${if (profile.useHttps) "HTTPS" else "HTTP"} · Port ${profile.port} · " +
                                    if (profile.hasCredentials) "Credentials saved" else "No credentials"
                            )
                        },
                        trailingContent = {
                            TextButton(
                                onClick = { onRemoveSavedServer(profile.id) },
                                enabled = state.removingSavedServerId == null
                            ) {
                                Text(if (state.removingSavedServerId == profile.id) "Removing..." else "Remove")
                            }
                        }
                    )
                }
            }
            state.savedServerErrorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = onAddServer, modifier = Modifier.fillMaxWidth()) {
                Text("Add server")
            }
        }
    }
}

@Composable
@SuppressLint("InlinedApi")
fun ServerSetupScreen(
    viewModel: ConnectionViewModel,
    savedProfileId: String?,
    onBack: () -> Unit,
    onConnected: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLocalNetworkRationale by remember { mutableStateOf(false) }
    var connectionRequested by remember(savedProfileId) { mutableStateOf(false) }

    fun connect() {
        connectionRequested = true
        if (savedProfileId != null) {
            scope.launch { viewModel.connectSavedServer(savedProfileId) }
        } else {
            viewModel.testConnection()
        }
    }

    fun requestConnection() {
        if (
            Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showLocalNetworkRationale = true
        } else {
            connect()
        }
    }

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) connect()
        else viewModel.onLocalNetworkPermissionDenied()
    }

    LaunchedEffect(savedProfileId) {
        if (savedProfileId != null) requestConnection()
    }
    LaunchedEffect(connectionRequested, state.activeConnection, state.isConnecting) {
        if (connectionRequested && !state.isConnecting && state.activeConnection != null) {
            onConnected()
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
                TextButton(onClick = {
                        showLocalNetworkRationale = false
                        localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showLocalNetworkRationale = false }) { Text("Not now") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (savedProfileId == null) "Add server" else "Connect to server") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Lyrion Music Server", style = MaterialTheme.typography.titleMedium)
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
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
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
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.savedServerErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.isConnecting.takeIf { it }?.let { Text("Connecting...") }
            Button(
                onClick = {
                    connectionRequested = true
                    requestConnection()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isConnecting && !state.isLoadingSavedServer
            ) {
                Text(if (state.isConnecting) "Connecting..." else "Connect")
            }
            state.serverInfo?.let { info ->
                Text("Connected to LMS ${info.version}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

}

@Composable
fun PlayerSelectionScreen(
    viewModel: ConnectionViewModel,
    onPlayerSelected: (LmsPlayer) -> Unit,
    onBack: () -> Unit,
    onChangeServer: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose a player") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = onChangeServer) { Text("Servers") } }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.serverInfo?.let { info ->
                Text("LMS ${info.version}", style = MaterialTheme.typography.labelLarge)
            }
            if (state.players.isEmpty()) {
                Text(
                    if (state.isConnecting) "Loading players..." else "No players are currently listed by this server.",
                    modifier = Modifier.padding(8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.players.forEach { player ->
                ListItem(
                    modifier = Modifier.clickable(enabled = player.connected) {
                        viewModel.selectPlayer(player.id)
                        onPlayerSelected(player)
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
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.playerPowerErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
