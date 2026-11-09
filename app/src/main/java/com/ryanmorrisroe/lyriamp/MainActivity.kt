package com.ryanmorrisroe.lyriamp

import android.os.Bundle
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.ryanmorrisroe.lyriamp.feature.connection.ConnectionScreen
import com.ryanmorrisroe.lyriamp.feature.connection.ConnectionViewModel
import com.ryanmorrisroe.lyriamp.media.LmsRemotePlaybackService
import com.ryanmorrisroe.lyriamp.ui.theme.LyriampTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null

    private val connectionViewModel: ConnectionViewModel by viewModels {
        val app = application as LyriampApplication
        ConnectionViewModel.Factory(
            app.connectionGateway,
            app.playerDirectory,
            app.playerRemoteGateway,
            app.activePlayerStore,
            app.savedServerRepository
        )
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(
            this,
            ComponentName(this, LmsRemotePlaybackService::class.java)
        )
        mediaControllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        mediaControllerFuture?.let { MediaController.releaseFuture(it) }
        mediaControllerFuture = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LyriampTheme {
                ConnectionScreen(connectionViewModel)
            }
        }
    }
}