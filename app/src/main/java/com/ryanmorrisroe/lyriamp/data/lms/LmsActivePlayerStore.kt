package com.ryanmorrisroe.lyriamp.data.lms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LmsActivePlayer(
    val connection: LmsServerConnection,
    val playerId: String,
    val playerName: String
)

class LmsActivePlayerStore {
    private val mutableActivePlayer = MutableStateFlow<LmsActivePlayer?>(null)
    val activePlayer = mutableActivePlayer.asStateFlow()

    fun select(player: LmsActivePlayer) {
        mutableActivePlayer.value = player
    }

    fun clear() {
        mutableActivePlayer.value = null
    }
}