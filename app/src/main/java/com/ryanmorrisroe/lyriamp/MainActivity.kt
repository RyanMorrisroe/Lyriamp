package com.ryanmorrisroe.lyriamp

import android.os.Bundle
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.core.view.WindowCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ryanmorrisroe.lyriamp.feature.connection.ConnectionViewModel
import com.ryanmorrisroe.lyriamp.feature.library.LibraryViewModel
import com.ryanmorrisroe.lyriamp.feature.settings.DisplayPreferencesViewModel
import com.ryanmorrisroe.lyriamp.data.persistence.Appearance
import com.ryanmorrisroe.lyriamp.media.LmsRemotePlaybackService
import com.ryanmorrisroe.lyriamp.ui.theme.LyriampTheme
import kotlinx.coroutines.flow.MutableStateFlow

@UnstableApi
class MainActivity : ComponentActivity() {
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private val mediaController = MutableStateFlow<MediaController?>(null)

    private val connectionViewModel: ConnectionViewModel by viewModels {
        val app = application as LyriampApplication
        ConnectionViewModel.Factory(
            app.connectionGateway,
            app.playerDirectory,
            app.playerRemoteGateway,
            app.activePlayerStore,
            app.savedServerRepository,
            app.playerGroupRepository
        )
    }

    private val libraryViewModel: LibraryViewModel by viewModels {
        val app = application as LyriampApplication
        LibraryViewModel.Factory(app.libraryGateway, app.activePlayerStore)
    }
    private val displayPreferencesViewModel: DisplayPreferencesViewModel by viewModels {
        DisplayPreferencesViewModel.Factory(
            (application as LyriampApplication).displayPreferencesRepository
        )
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(
            this,
            ComponentName(this, LmsRemotePlaybackService::class.java)
        )
        val future = MediaController.Builder(this, token).buildAsync()
        mediaControllerFuture = future
        future.addListener({
            if (mediaControllerFuture == future) {
                mediaController.value = runCatching { future.get() }.getOrNull()
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        mediaController.value = null
        mediaControllerFuture?.let { MediaController.releaseFuture(it) }
        mediaControllerFuture = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val displayState by displayPreferencesViewModel.state.collectAsStateWithLifecycle()
            val darkTheme = when (displayState.preferences.appearance) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.LIGHT -> false
                Appearance.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            LyriampTheme(darkTheme = darkTheme) {
                val player by mediaController.collectAsStateWithLifecycle()
                LyriampAppNavigation(
                    connectionViewModel = connectionViewModel,
                    libraryViewModel = libraryViewModel,
                    displayPreferencesViewModel = displayPreferencesViewModel,
                    mediaPlayer = player,
                    lastSessionRepository = (application as LyriampApplication).lastSessionRepository
                )
            }
        }
    }
}