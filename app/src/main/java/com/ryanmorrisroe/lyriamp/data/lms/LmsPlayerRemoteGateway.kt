package com.ryanmorrisroe.lyriamp.data.lms

data class LmsPlayerStatus(
    val mode: LmsPlaybackMode,
    val title: String?,
    val artist: String?,
    val album: String?,
    val positionSeconds: Double,
    val durationSeconds: Double?,
    val canSeek: Boolean
)

data class LmsPlayerMixerState(
    val volumePercent: Int,
    val isMuted: Boolean
)

data class LmsPlayerPlaybackOptions(
    val shuffleMode: LmsShuffleMode,
    val repeatMode: LmsRepeatMode
)

enum class LmsShuffleMode(val cliValue: Int) {
    OFF(0),
    SONGS(1),
    ALBUMS(2);

    companion object {
        fun fromCliValue(value: Int): LmsShuffleMode? = entries.firstOrNull { it.cliValue == value }
    }
}

enum class LmsRepeatMode(val cliValue: Int) {
    OFF(0),
    TRACK(1),
    PLAYLIST(2);

    companion object {
        fun fromCliValue(value: Int): LmsRepeatMode? = entries.firstOrNull { it.cliValue == value }
    }
}

enum class LmsPlaybackMode {
    PLAYING,
    PAUSED,
    STOPPED,
    UNKNOWN
}

sealed interface LmsPlayerCommand {
    data object Play : LmsPlayerCommand
    data object Pause : LmsPlayerCommand
    data object Stop : LmsPlayerCommand
    data object Next : LmsPlayerCommand
    data object Previous : LmsPlayerCommand
    data class SeekTo(val positionSeconds: Int) : LmsPlayerCommand {
        init {
            require(positionSeconds >= 0) { "Seek position cannot be negative." }
        }
    }
    data class SetVolume(val percent: Int) : LmsPlayerCommand {
        init {
            require(percent in 0..100) { "Volume must be between 0 and 100." }
        }
    }
    data class SetMuted(val muted: Boolean) : LmsPlayerCommand
    data class SetPower(val poweredOn: Boolean) : LmsPlayerCommand
    data class SetShuffleMode(val mode: LmsShuffleMode) : LmsPlayerCommand
    data class SetRepeatMode(val mode: LmsRepeatMode) : LmsPlayerCommand

    fun toCliTokens(): List<String> = when (this) {
        Play -> listOf("play")
        Pause -> listOf("pause")
        Stop -> listOf("stop")
        Next -> listOf("playlist", "index", "+1")
        Previous -> listOf("playlist", "index", "-1")
        is SeekTo -> listOf("time", positionSeconds.toString())
        is SetVolume -> listOf("mixer", "volume", percent.toString())
        is SetMuted -> listOf("mixer", "muting", if (muted) "1" else "0")
        is SetPower -> listOf("power", if (poweredOn) "1" else "0")
        is SetShuffleMode -> listOf("playlist", "shuffle", mode.cliValue.toString())
        is SetRepeatMode -> listOf("playlist", "repeat", mode.cliValue.toString())
    }
}

interface LmsPlayerRemoteGateway {
    suspend fun getPlayerStatus(connection: LmsServerConnection, playerId: String): LmsPlayerStatus

    suspend fun getPlayerMixerState(
        connection: LmsServerConnection,
        playerId: String
    ): LmsPlayerMixerState

    suspend fun getPlayerPlaybackOptions(
        connection: LmsServerConnection,
        playerId: String
    ): LmsPlayerPlaybackOptions

    suspend fun sendCommand(
        connection: LmsServerConnection,
        playerId: String,
        command: LmsPlayerCommand
    )
}