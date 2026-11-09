package com.ryanmorrisroe.lyriamp.media

import android.os.Looper
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayerStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlaybackMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerCommand
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerRemoteGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@UnstableApi
class LmsRemotePlayer(
    activePlayerStore: LmsActivePlayerStore,
    private val remoteGateway: LmsPlayerRemoteGateway
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activePlayer: LmsActivePlayer? = null
    private var playbackStatus: LmsPlayerStatus? = null
    private var pendingPlayWhenReady: Boolean? = null

    init {
        scope.launch {
            activePlayerStore.activePlayer.collectLatest { player ->
                activePlayer = player
                playbackStatus = null
                pendingPlayWhenReady = null
                invalidateState()
                while (player != null) {
                    refreshStatus(player)
                    delay(5_000)
                }
            }
        }
    }

    override fun getState(): State {
        val player = activePlayer
        val status = playbackStatus
        val mediaItems = if (player != null && status != null) {
            listOf(status.toMediaItemData(player))
        } else {
            emptyList()
        }
        val playWhenReady = pendingPlayWhenReady ?: (status?.mode == LmsPlaybackMode.PLAYING)
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, status?.canSeek == true)
            .build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(
                if (status == null || status.mode == LmsPlaybackMode.STOPPED) {
                    Player.STATE_IDLE
                } else {
                    Player.STATE_READY
                }
            )
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaylist(mediaItems)
            .setContentPositionMs(((status?.positionSeconds ?: 0.0) * 1_000).toLong().coerceAtLeast(0))
            .apply {
                if (mediaItems.isNotEmpty()) setCurrentMediaItemIndex(0)
            }
            .build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val player = activePlayer ?: return completedFuture()
        return createFuture("lms-prepare") { refreshStatus(player) }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        pendingPlayWhenReady = playWhenReady
        invalidateState()
        return sendCommand(
            if (playWhenReady) LmsPlayerCommand.Play else LmsPlayerCommand.Pause,
            clearsPendingPlayWhenReady = true
        )
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        val command = when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> LmsPlayerCommand.Previous
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> LmsPlayerCommand.Next
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> {
                LmsPlayerCommand.SeekTo((positionMs.coerceAtLeast(0) / 1_000).toInt())
            }
            else -> return completedFuture()
        }
        return sendCommand(command)
    }

    override fun handleStop(): ListenableFuture<*> = sendCommand(LmsPlayerCommand.Stop)

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return completedFuture()
    }

    private fun sendCommand(
        command: LmsPlayerCommand,
        clearsPendingPlayWhenReady: Boolean = false
    ): ListenableFuture<*> {
        val player = activePlayer
        if (player == null) {
            pendingPlayWhenReady = null
            invalidateState()
            return completedFuture()
        }
        return createFuture("lms-command-${command.javaClass.simpleName}") {
            try {
                remoteGateway.sendCommand(player.connection, player.playerId, command)
                refreshStatus(player)
            } finally {
                if (clearsPendingPlayWhenReady) {
                    pendingPlayWhenReady = null
                    invalidateState()
                }
            }
        }
    }

    private suspend fun refreshStatus(player: LmsActivePlayer) {
        try {
            val status = remoteGateway.getPlayerStatus(player.connection, player.playerId)
            if (activePlayer == player) {
                playbackStatus = status
                invalidateState()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            if (activePlayer == player && playbackStatus == null) invalidateState()
        }
    }

    private fun createFuture(
        tag: String,
        block: suspend () -> Unit
    ): ListenableFuture<*> = CallbackToFutureAdapter.getFuture { completer ->
        scope.launch {
            try {
                block()
                completer.set(Unit)
            } catch (exception: CancellationException) {
                completer.setException(exception)
                throw exception
            } catch (exception: Exception) {
                completer.setException(exception)
            }
        }
        tag
    }

    private fun completedFuture(): ListenableFuture<*> =
        CallbackToFutureAdapter.getFuture<Unit> { completer ->
            completer.set(Unit)
            "lms-no-op"
        }

    private fun LmsPlayerStatus.toMediaItemData(player: LmsActivePlayer): MediaItemData {
        val metadata = MediaMetadata.Builder()
            .setTitle(title ?: player.playerName)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(true)
            .build()
        val mediaItem = MediaItem.Builder()
            .setMediaId(player.playerId)
            .setMediaMetadata(metadata)
            .build()
        return MediaItemData.Builder(player.playerId)
            .setMediaItem(mediaItem)
            .setDurationUs(durationSeconds?.let { (it * 1_000_000).toLong() } ?: C.TIME_UNSET)
            .setIsSeekable(canSeek)
            .build()
    }
}