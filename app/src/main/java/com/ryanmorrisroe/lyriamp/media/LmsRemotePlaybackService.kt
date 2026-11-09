package com.ryanmorrisroe.lyriamp.media

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ryanmorrisroe.lyriamp.LyriampApplication

@UnstableApi
class LmsRemotePlaybackService : MediaSessionService() {
    private lateinit var mediaSession: MediaSession
    private lateinit var remotePlayer: LmsRemotePlayer

    override fun onCreate() {
        super.onCreate()
        val app = application as LyriampApplication
        remotePlayer = LmsRemotePlayer(app.activePlayerStore, app.playerRemoteGateway)
        mediaSession = MediaSession.Builder(this, remotePlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        mediaSession.release()
        remotePlayer.release()
        super.onDestroy()
    }
}