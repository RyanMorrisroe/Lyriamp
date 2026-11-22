package com.ryanmorrisroe.lyriamp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.media3.common.Player
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ryanmorrisroe.lyriamp.data.persistence.LastSessionLocation
import com.ryanmorrisroe.lyriamp.data.persistence.LastSessionRepository
import com.ryanmorrisroe.lyriamp.data.persistence.LastSessionSnapshot
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayer
import com.ryanmorrisroe.lyriamp.feature.connection.ConnectionViewModel
import com.ryanmorrisroe.lyriamp.feature.connection.ConnectionsScreen
import com.ryanmorrisroe.lyriamp.feature.connection.PlayerSelectionScreen
import com.ryanmorrisroe.lyriamp.feature.connection.ServerSetupScreen
import com.ryanmorrisroe.lyriamp.feature.library.LibraryScreen
import com.ryanmorrisroe.lyriamp.feature.library.LibraryLocation
import com.ryanmorrisroe.lyriamp.feature.library.LibraryViewModel
import com.ryanmorrisroe.lyriamp.feature.library.libraryLocationKey
import com.ryanmorrisroe.lyriamp.feature.player.NowPlayingScreen
import com.ryanmorrisroe.lyriamp.feature.settings.DisplayPreferencesViewModel
import com.ryanmorrisroe.lyriamp.feature.settings.SettingsScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

@Composable
fun LyriampAppNavigation(
    connectionViewModel: ConnectionViewModel,
    libraryViewModel: LibraryViewModel,
    displayPreferencesViewModel: DisplayPreferencesViewModel,
    mediaPlayer: Player?,
    lastSessionRepository: LastSessionRepository
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    var pendingRestore by remember { mutableStateOf<LastSessionSnapshot?>(null) }
    val sessionSaves = remember { Channel<LastSessionSnapshot>(Channel.CONFLATED) }
    var sessionSaveError by remember { mutableStateOf<String?>(null) }
    var failedSnapshot by remember { mutableStateOf<LastSessionSnapshot?>(null) }

    LaunchedEffect(lastSessionRepository) {
        for (snapshot in sessionSaves) {
            try {
                lastSessionRepository.save(snapshot)
                sessionSaveError = null
                failedSnapshot = null
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failedSnapshot = snapshot
                sessionSaveError = "Could not save your library position: ${exception.message}"
            }
        }
    }

    fun openConnections() {
        navController.navigate(CONNECTIONS_ROUTE) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun openLibraryAsRoot() {
        navController.navigate(LIBRARY_ROUTE) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun restoreLibrary(snapshot: LastSessionSnapshot) {
        libraryViewModel.restoreSession(
            locationStack = snapshot.locationStack.map { location ->
                LibraryLocation(location.title, location.query, location.filter)
            },
            searchText = snapshot.searchText,
            scrollAnchor = snapshot.scrollAnchor,
            activeTab = snapshot.activeTab,
            lastVisitedTab = snapshot.lastVisitedTab,
            tabs = snapshot.tabs
        )
    }

    NavHost(navController = navController, startDestination = RESTORING_ROUTE) {
        composable(RESTORING_ROUTE) {
            LaunchedEffect(Unit) {
                val snapshot = runCatching { lastSessionRepository.load() }.getOrNull()
                if (snapshot == null) {
                    openConnections()
                } else {
                    pendingRestore = snapshot
                }
            }
            val snapshot = pendingRestore
            if (snapshot == null) {
                RestoringScreen()
            } else {
                RestoreLastSessionScreen(
                    snapshot = snapshot,
                    connectionViewModel = connectionViewModel,
                    onUnavailable = {
                        pendingRestore = null
                        openConnections()
                    },
                    onConnected = { player ->
                        if (player == null) {
                            navController.navigate("$PLAYER_SELECTION_ROUTE?$ORIGIN_ARGUMENT=restore") {
                                popUpTo(RESTORING_ROUTE) { inclusive = true }
                            }
                        } else {
                            restoreLibrary(snapshot)
                            pendingRestore = null
                            connectionViewModel.selectPlayer(player.id)
                            navController.navigate(LIBRARY_ROUTE) {
                                popUpTo(RESTORING_ROUTE) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                )
            }
        }
        composable(CONNECTIONS_ROUTE) {
            ConnectionsScreen(
                viewModel = connectionViewModel,
                onAddServer = { navController.navigate(SERVER_SETUP_ROUTE) },
                onConnectSavedServer = { profileId ->
                    navController.navigate(
                        "$SERVER_SETUP_ROUTE?profileId=${Uri.encode(profileId)}"
                    )
                },
                onRemoveSavedServer = { profileId ->
                    scope.launch {
                        if (lastSessionRepository.load()?.serverProfileId == profileId) {
                            lastSessionRepository.clear()
                        }
                        connectionViewModel.removeSavedServer(profileId)
                    }
                },
                onBack = if (navController.previousBackStackEntry != null) {
                    {
                        if (libraryViewModel.uiState.value.activePlayer == null) openConnections()
                        else navController.popBackStack()
                    }
                } else null
            )
        }
        composable(
            route = "$SERVER_SETUP_ROUTE?$PROFILE_ID_ARGUMENT={$PROFILE_ID_ARGUMENT}",
            arguments = listOf(
                navArgument(PROFILE_ID_ARGUMENT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            ServerSetupScreen(
                viewModel = connectionViewModel,
                savedProfileId = entry.arguments?.getString(PROFILE_ID_ARGUMENT),
                onBack = {
                    if (!navController.popBackStack()) openConnections()
                },
                onConnected = {
                    navController.navigate("$PLAYER_SELECTION_ROUTE?$ORIGIN_ARGUMENT=setup") {
                        popUpTo(entry.destination.id) { inclusive = true }
                    }
                }
            )
        }
        composable(
            route = "$PLAYER_SELECTION_ROUTE?origin={$ORIGIN_ARGUMENT}",
            arguments = listOf(
                navArgument(ORIGIN_ARGUMENT) {
                    type = NavType.StringType
                    defaultValue = "setup"
                }
            )
        ) { entry ->
            val origin = entry.arguments?.getString(ORIGIN_ARGUMENT)
            PlayerSelectionScreen(
                viewModel = connectionViewModel,
                onPlayerSelected = {
                    when (origin) {
                        "setup" -> {
                            libraryViewModel.resetToRoot()
                            openLibraryAsRoot()
                        }
                        "restore" -> {
                            pendingRestore?.let(::restoreLibrary)
                            pendingRestore = null
                            openLibraryAsRoot()
                        }
                        else -> navController.popBackStack()
                    }
                },
                onBack = {
                    if (!navController.popBackStack()) openConnections()
                },
                onChangeServer = ::openConnections
            )
        }
        composable(LIBRARY_ROUTE) {
            LibraryScreen(
                viewModel = libraryViewModel,
                connectionViewModel = connectionViewModel,
                mediaPlayer = mediaPlayer,
                onChangePlayer = {
                    navController.navigate("$PLAYER_SELECTION_ROUTE?origin=library")
                },
                displayPreferencesViewModel = displayPreferencesViewModel,
                onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                onOpenNowPlaying = { navController.navigate(NOW_PLAYING_ROUTE) },
                sessionError = sessionSaveError,
                onRetrySessionSave = { failedSnapshot?.let { sessionSaves.trySend(it) } },
                onLibraryStateChanged = { locations, searchText, anchor ->
                    val player = libraryViewModel.uiState.value.activePlayer
                    val profileId = connectionViewModel.uiState.value.serverProfileId
                    if (player != null && profileId != null &&
                        libraryViewModel.isCurrentLocation(locations, searchText)
                    ) {
                        val snapshot = LastSessionSnapshot(
                            serverProfileId = profileId,
                            playerId = player.playerId,
                            locationStack = locations.map { location ->
                                LastSessionLocation(location.title, location.query, location.filter)
                            },
                            searchText = searchText,
                            scrollAnchor = anchor?.takeIf {
                                it.locationKey == libraryLocationKey(locations) &&
                                    it.searchText == searchText
                            },
                            activeTab = libraryViewModel.currentTab().name,
                            lastVisitedTab = libraryViewModel.lastVisitedTab()?.name,
                            tabs = libraryViewModel.savedTabs()
                        )
                        if (sessionSaves.trySend(snapshot).isFailure) {
                            failedSnapshot = snapshot
                            sessionSaveError = "Could not queue your library position for saving."
                        }
                    }
                }
            )
        }
        composable(SETTINGS_ROUTE) {
            SettingsScreen(
                viewModel = displayPreferencesViewModel,
                onBack = { navController.popBackStack() },
                onConnections = { navController.navigate(CONNECTIONS_ROUTE) }
            )
        }
        composable(NOW_PLAYING_ROUTE) {
            NowPlayingScreen(
                player = mediaPlayer,
                connectionViewModel = connectionViewModel,
                onBack = { navController.popBackStack() },
                onChoosePlayer = {
                    navController.navigate("$PLAYER_SELECTION_ROUTE?origin=now-playing")
                }
            )
        }
    }
}

private const val CONNECTIONS_ROUTE = "connections"
private const val RESTORING_ROUTE = "restoring"
private const val SERVER_SETUP_ROUTE = "server-setup"
private const val PLAYER_SELECTION_ROUTE = "player-selection"
private const val LIBRARY_ROUTE = "library"
private const val NOW_PLAYING_ROUTE = "now-playing"
private const val SETTINGS_ROUTE = "settings"
private const val PROFILE_ID_ARGUMENT = "profileId"
private const val ORIGIN_ARGUMENT = "origin"

@Composable
private fun RestoreLastSessionScreen(
    snapshot: LastSessionSnapshot,
    connectionViewModel: ConnectionViewModel,
    onUnavailable: () -> Unit,
    onConnected: (LmsPlayer?) -> Unit
) {
    val context = LocalContext.current
    val permissionRequired = Build.VERSION.SDK_INT >= 37
    var hasNetworkPermission by remember(snapshot.serverProfileId) {
        mutableStateOf(
            !permissionRequired || context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var showPermissionRationale by remember(snapshot.serverProfileId) {
        mutableStateOf(permissionRequired && !hasNetworkPermission)
    }
    var hasStartedRestore by remember(snapshot.serverProfileId) { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasNetworkPermission = true
        } else {
            connectionViewModel.onLocalNetworkPermissionDenied()
            onUnavailable()
        }
    }

    LaunchedEffect(hasNetworkPermission, snapshot.serverProfileId) {
        if (hasNetworkPermission && !hasStartedRestore) {
            hasStartedRestore = true
            if (connectionViewModel.connectSavedServer(snapshot.serverProfileId)) {
                val player = connectionViewModel.uiState.value.players.firstOrNull {
                    it.id == snapshot.playerId && it.connected
                }
                onConnected(player)
            } else {
                onUnavailable()
            }
        }
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = {
                showPermissionRationale = false
                connectionViewModel.onLocalNetworkPermissionDenied()
                onUnavailable()
            },
            title = { Text("Reconnect to your LMS") },
            text = {
                Text("Lyriamp needs local-network access to reconnect to your saved server. It does not scan for servers.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    connectionViewModel.onLocalNetworkPermissionDenied()
                    onUnavailable()
                }) { Text("Not now") }
            }
        )
    }
    RestoringScreen()
}

@Composable
private fun RestoringScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Text("Restoring your last session...", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
