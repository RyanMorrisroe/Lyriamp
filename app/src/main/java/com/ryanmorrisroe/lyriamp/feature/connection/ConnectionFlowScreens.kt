@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.ryanmorrisroe.lyriamp.feature.connection

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayer
import com.ryanmorrisroe.lyriamp.data.lms.SavedLmsPlayerGroup
import com.ryanmorrisroe.lyriamp.ui.glyph.BackArrowIcon
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ConnectionsScreen(
    viewModel: ConnectionViewModel,
    onAddServer: () -> Unit,
    onConnectSavedServer: (String) -> Unit,
    onRemoveSavedServer: (String) -> Unit,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connections") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.semantics { contentDescription = "Back" }
                        ) {
                            BackArrowIcon(
                                MaterialTheme.colorScheme.onSurface,
                                Modifier.size(24.dp)
                            )
                        }
                    }
                }
            )
        }
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
                }
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
    var isBuildingGroup by remember { mutableStateOf(false) }
    var revealedDeleteGroupId by remember { mutableStateOf<String?>(null) }
    var groupToDelete by remember { mutableStateOf<SavedLmsPlayerGroup?>(null) }
    var editingGroupId by remember { mutableStateOf<String?>(null) }
    val currentSelectionBounds = remember { mutableStateOf<Rect?>(null) }
    val groupDropBounds = remember { mutableStateOf<Rect?>(null) }
    val availableDropBounds = remember { mutableStateOf<Rect?>(null) }
    val dragContainerBounds = remember { mutableStateOf<Rect?>(null) }
    var draggedPlayerId by remember { mutableStateOf<String?>(null) }
    var dragPositionInRoot by remember { mutableStateOf<Offset?>(null) }
    var isDraggingOverSelection by remember { mutableStateOf(false) }
    var isDraggingOverGroup by remember { mutableStateOf(false) }
    var isDraggingOverAvailable by remember { mutableStateOf(false) }
    val selectedPlayers = state.players.filter { it.id in state.selectedPlayerIds }
    val controlPlayer = state.players.firstOrNull { it.id == state.selectedPlayerId }

    fun navigateToSelectedPlayer() {
        val currentState = viewModel.uiState.value
        currentState.players.firstOrNull { it.id == currentState.selectedPlayerId }
            ?.let(onPlayerSelected)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            editingGroupId != null -> "Edit group"
                            isBuildingGroup -> "Build a group"
                            else -> "Players"
                        }
                    )
                },
                navigationIcon = {
                    if (isBuildingGroup) {
                        TextButton(
                            onClick = {
                                isBuildingGroup = false
                                editingGroupId = null
                            }
                        ) { Text("Cancel") }
                    } else {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.semantics { contentDescription = "Back" }
                        ) {
                            BackArrowIcon(
                                MaterialTheme.colorScheme.onSurface,
                                Modifier.size(24.dp)
                            )
                        }
                    }
                },
                actions = { TextButton(onClick = onChangeServer) { Text("Servers") } }
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { dragContainerBounds.value = it.boundsInRoot() }
        ) {
        if (isBuildingGroup) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.serverInfo?.let { info ->
                    Text("LMS ${info.version}", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    "Changes stay in this draft until you use these rooms. Offline players cannot be added.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = state.groupNameDraft,
                    onValueChange = viewModel::setGroupNameDraft,
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group-name-input")
                )
                Text(
                    "In this group (${state.groupBuilderPlayerIds.size})",
                    style = MaterialTheme.typography.titleSmall
                )
                val dropShape = RoundedCornerShape(12.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            if (isDraggingOverGroup) 2.dp else 1.dp,
                            if (isDraggingOverGroup) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            dropShape
                        )
                        .padding(8.dp)
                        .onGloballyPositioned { groupDropBounds.value = it.boundsInRoot() }
                ) {
                    val groupPlayers = state.players.filter { it.id in state.groupBuilderPlayerIds }
                    if (groupPlayers.isEmpty()) {
                        Text(
                            "Drop players here to add them.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    groupPlayers.forEach { player ->
                        GroupBuilderPlayerItem(
                            player = player,
                            subtitle = playerSubtitle(player, state.playerStatusesById[player.id], player.id in state.playerStatusErrorIds),
                            addToGroup = false,
                            onDragMoved = { id, position ->
                                draggedPlayerId = id
                                dragPositionInRoot = position
                                isDraggingOverGroup = groupDropBounds.value?.contains(position) == true
                                isDraggingOverAvailable = availableDropBounds.value?.contains(position) == true
                            },
                            onDragEnded = { id, position ->
                                when {
                                    position != null &&
                                        availableDropBounds.value?.contains(position) == true ->
                                        viewModel.setGroupBuilderPlayerSelected(id, false)
                                    position != null &&
                                        groupDropBounds.value?.contains(position) == true ->
                                        viewModel.setGroupBuilderPlayerSelected(id, true)
                                }
                                draggedPlayerId = null
                                dragPositionInRoot = null
                                isDraggingOverGroup = false
                                isDraggingOverAvailable = false
                            },
                            onMove = { id, add -> viewModel.setGroupBuilderPlayerSelected(id, add) }
                        )
                    }
                }
                Text(
                    "Available rooms (${state.players.count { it.connected && it.id !in state.groupBuilderPlayerIds }})",
                    style = MaterialTheme.typography.titleSmall
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            if (isDraggingOverAvailable) 2.dp else 1.dp,
                            if (isDraggingOverAvailable) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            dropShape
                        )
                        .padding(8.dp)
                        .onGloballyPositioned { availableDropBounds.value = it.boundsInRoot() }
                ) {
                    val availablePlayers = state.players.filter {
                        it.connected && it.id !in state.groupBuilderPlayerIds
                    }
                    if (availablePlayers.isEmpty()) {
                        Text(
                            "All connected rooms are in the group.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    availablePlayers.forEach { player ->
                        GroupBuilderPlayerItem(
                            player = player,
                            subtitle = playerSubtitle(player, state.playerStatusesById[player.id], player.id in state.playerStatusErrorIds),
                            addToGroup = true,
                            onDragMoved = { id, position ->
                                draggedPlayerId = id
                                dragPositionInRoot = position
                                isDraggingOverGroup = groupDropBounds.value?.contains(position) == true
                                isDraggingOverAvailable = availableDropBounds.value?.contains(position) == true
                            },
                            onDragEnded = { id, position ->
                                when {
                                    position != null &&
                                        groupDropBounds.value?.contains(position) == true ->
                                        viewModel.setGroupBuilderPlayerSelected(id, true)
                                    position != null &&
                                        availableDropBounds.value?.contains(position) == true ->
                                        viewModel.setGroupBuilderPlayerSelected(id, false)
                                }
                                draggedPlayerId = null
                                dragPositionInRoot = null
                                isDraggingOverGroup = false
                                isDraggingOverAvailable = false
                            },
                            onMove = { id, add -> viewModel.setGroupBuilderPlayerSelected(id, add) }
                        )
                    }
                }
                state.playerGroupErrorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                state.playerGroupMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.savePlayerGroup(
                                name = state.groupNameDraft,
                                groupId = editingGroupId
                            )
                        },
                        enabled = state.groupBuilderPlayerIds.size >= 2 && !state.isSavingPlayerGroup
                    ) {
                        Text(
                            when {
                                state.isSavingPlayerGroup -> "Saving..."
                                editingGroupId != null -> "Save changes"
                                else -> "Save group"
                            }
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.applyGroupBuilder {
                                isBuildingGroup = false
                                navigateToSelectedPlayer()
                            }
                        },
                        enabled = state.groupBuilderPlayerIds.isNotEmpty() &&
                            !state.isApplyingPlayerSelection
                    ) {
                        Text("Use these rooms")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("player-selection-list"),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                state.serverInfo?.let { info ->
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(1.dp)
                            ) {
                                Text(
                                    "Connected to LMS ${info.version}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "${info.playerCount} players available",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Saved groups", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Tap a group or room to open the library. Hold a group to edit or delete.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (state.playerGroups.isEmpty()) {
                    item {
                        Text(
                            "No saved groups yet. Build a group and save it as a preset.",
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(state.playerGroups, key = SavedLmsPlayerGroup::id) { group ->
                        val isRevealed = revealedDeleteGroupId == group.id
                        val shape = RoundedCornerShape(12.dp)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                            shape = shape,
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            enabled = !state.isApplyingPlayerSelection,
                                            onClick = {
                                                viewModel.selectPlayerGroup(
                                                    group.id,
                                                    ::navigateToSelectedPlayer
                                                )
                                                revealedDeleteGroupId = null
                                            },
                                            onLongClick = {
                                                revealedDeleteGroupId =
                                                    if (isRevealed) null else group.id
                                            }
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(group.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            group.playerIds.joinToString(" · ") { id ->
                                                state.players.firstOrNull { it.id == id }?.name ?: id
                                            },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        "${group.playerIds.size} rooms",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (isRevealed) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = {
                                                editingGroupId = group.id
                                                viewModel.beginGroupBuilder(group)
                                                isBuildingGroup = true
                                                revealedDeleteGroupId = null
                                            }
                                        ) {
                                            Text("Edit group")
                                        }
                                        TextButton(
                                            onClick = { groupToDelete = group },
                                            enabled = state.removingPlayerGroupId != group.id
                                        ) {
                                            Text(
                                                if (state.removingPlayerGroupId == group.id) {
                                                    "Deleting..."
                                                } else {
                                                    "Delete group"
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            editingGroupId = null
                            viewModel.beginGroupBuilder()
                            isBuildingGroup = true
                        },
                        enabled = !state.isApplyingPlayerSelection
                    ) {
                        Text("Build a room group")
                    }
                }
                stickyHeader {
                    val dropTargetModifier = if (isDraggingOverSelection) {
                        Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { currentSelectionBounds.value = it.boundsInRoot() }
                            .then(dropTargetModifier),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("Current selection", style = MaterialTheme.typography.titleSmall)
                            Text(
                                when {
                                    selectedPlayers.isEmpty() -> "No player selected"
                                    selectedPlayers.size == 1 -> selectedPlayers.single().name
                                    else -> "${selectedPlayers.size} rooms"
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (selectedPlayers.size > 1) {
                                Text(
                                    selectedPlayers.joinToString(" · ") { it.name },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                if (isDraggingOverSelection) {
                                    "Release to add ${state.players.firstOrNull {
                                        it.id == draggedPlayerId
                                    }?.name ?: "player"}"
                                } else {
                                    "Drag a connected player here."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (state.isApplyingPlayerSelection) {
                                Text("Applying selection...")
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Players",
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                if (state.players.isEmpty()) {
                    item {
                        Text(
                            if (state.isConnecting) "Loading players..." else "No players are currently listed by this server.",
                            modifier = Modifier.padding(8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(state.players, key = LmsPlayer::id) { player ->
                    val selected = player.id in state.selectedPlayerIds
                    val shape = RoundedCornerShape(12.dp)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape
                            )
                            .clickable(
                                enabled = player.connected && !state.isApplyingPlayerSelection
                            ) {
                                viewModel.selectPlayer(player.id) { onPlayerSelected(player) }
                            },
                        shape = shape,
                        color = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when {
                                selected -> Text(
                                    "Selected",
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                player.connected -> PlayerDragHandle(
                                    player = player,
                                    enabled = !state.isApplyingPlayerSelection,
                                    onDragMoved = { position ->
                                        draggedPlayerId = player.id
                                        dragPositionInRoot = position
                                        isDraggingOverSelection =
                                            currentSelectionBounds.value?.contains(position) == true
                                    },
                                    onDragEnded = { draggedId, position ->
                                        if (
                                            position != null &&
                                            currentSelectionBounds.value?.contains(position) == true
                                        ) {
                                            viewModel.addPlayerToCurrentSelection(draggedId)
                                        }
                                        draggedPlayerId = null
                                        dragPositionInRoot = null
                                        isDraggingOverSelection = false
                                    }
                                )
                                else -> Text(
                                    "Offline",
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    player.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    playerSubtitle(
                                        player,
                                        state.playerStatusesById[player.id],
                                        player.id in state.playerStatusErrorIds
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                state.playerGroupErrorMessage?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
                state.playerGroupMessage?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.primary) }
                }
                state.errorMessage?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
                item {
                    Button(
                        onClick = { controlPlayer?.let(onPlayerSelected) },
                        enabled = controlPlayer != null && !state.isApplyingPlayerSelection,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (state.selectedPlayerIds.size == 1) "Control this room"
                            else "Control ${state.selectedPlayerIds.size} rooms"
                        )
                    }
                }
            }
        }
            val draggedPlayer = state.players.firstOrNull { it.id == draggedPlayerId }
            val position = dragPositionInRoot
            val containerBounds = dragContainerBounds.value
            if (draggedPlayer != null && position != null && containerBounds != null) {
                val density = LocalDensity.current
                val previewShape = RoundedCornerShape(12.dp)
                Surface(
                    modifier = Modifier
                        .testTag("player-drag-preview")
                        .offset {
                            IntOffset(
                                x = (position.x - containerBounds.left - with(density) { 12.dp.toPx() })
                                    .roundToInt(),
                                y = (position.y - containerBounds.top - with(density) { 28.dp.toPx() })
                                    .roundToInt()
                            )
                        }
                        .widthIn(max = 260.dp)
                        .zIndex(1f)
                        .border(1.dp, MaterialTheme.colorScheme.primary, previewShape),
                    shape = previewShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DragHandleGlyph()
                        Column(
                            modifier = Modifier.padding(start = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                draggedPlayer.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                playerSubtitle(
                                    draggedPlayer,
                                    state.playerStatusesById[draggedPlayer.id],
                                    draggedPlayer.id in state.playerStatusErrorIds
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
    }
    }

    groupToDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = { Text("Delete saved group?") },
            text = {
                Text("Delete \"${group.name}\"? This removes the saved preset only; current playback and sync are unchanged.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removePlayerGroup(group.id)
                    groupToDelete = null
                    revealedDeleteGroupId = null
                }) { Text("Delete group") }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) { Text("Cancel") }
            }
        )
    }

}

@Composable
private fun GroupBuilderPlayerItem(
    player: LmsPlayer,
    subtitle: String,
    addToGroup: Boolean,
    onDragMoved: (String, Offset) -> Unit,
    onDragEnded: (String, Offset?) -> Unit,
    onMove: (String, Boolean) -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerDragHandle(
                player = player,
                enabled = player.connected,
                onDragMoved = { position -> onDragMoved(player.id, position) },
                onDragEnded = onDragEnded
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(player.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { onMove(player.id, addToGroup) }) {
                Text(if (addToGroup) "Add" else "Remove")
            }
        }
    }
}

@Composable
private fun PlayerDragHandle(
    player: LmsPlayer,
    enabled: Boolean,
    onDragMoved: (Offset) -> Unit,
    onDragEnded: (String, Offset?) -> Unit
) {
    val sourceCoordinates = remember(player.id) {
        mutableStateOf<LayoutCoordinates?>(null)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .testTag("player-drag-handle-${player.id}")
            .semantics { contentDescription = "Hold and drag ${player.name}" }
            .onGloballyPositioned { sourceCoordinates.value = it }
            .playerDragSource(
                playerId = player.id,
                sourceCoordinates = sourceCoordinates,
                enabled = enabled,
                onDragMoved = onDragMoved,
                onDragEnded = onDragEnded
            ),
        contentAlignment = Alignment.Center
    ) {
        DragHandleGlyph()
    }
}

@Composable
private fun DragHandleGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val gap = 5.dp.toPx()
        val radius = 1.25.dp.toPx()
        val startX = (size.width - gap) / 2f
        val startY = (size.height - 2f * gap) / 2f
        for (column in 0..1) {
            for (row in 0..2) {
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset(startX + column * gap, startY + row * gap)
                )
            }
        }
    }
}

private fun playerSubtitle(
    player: LmsPlayer,
    status: com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerStatus?,
    failed: Boolean
): String = when {
    !player.connected -> "Offline"
    failed -> "Could not read current track"
    status == null -> "Loading current track..."
    else -> listOfNotNull(
        status.title?.takeIf(String::isNotBlank),
        status.artist?.takeIf(String::isNotBlank)
    ).joinToString(" · ").ifBlank { "Nothing is playing" }
}

private fun Modifier.playerDragSource(
    playerId: String,
    sourceCoordinates: MutableState<LayoutCoordinates?>,
    enabled: Boolean,
    onDragMoved: (Offset) -> Unit,
    onDragEnded: (String, Offset?) -> Unit
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(playerId) {
        var positionInRoot: Offset? = null
        detectDragGestures(
            onDragStart = { position ->
                positionInRoot = sourceCoordinates.value?.localToRoot(position)
                positionInRoot?.let(onDragMoved)
            },
            onDrag = { change, _ ->
                change.consume()
                positionInRoot = sourceCoordinates.value?.localToRoot(change.position)
                positionInRoot?.let(onDragMoved)
            },
            onDragEnd = {
                onDragEnded(playerId, positionInRoot)
                positionInRoot = null
            },
            onDragCancel = {
                onDragEnded(playerId, null)
                positionInRoot = null
            }
        )
    }
}
