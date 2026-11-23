package com.ryanmorrisroe.lyriamp.data.lms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LmsActivePlayer(
    val connection: LmsServerConnection,
    val playerId: String,
    val playerName: String,
    val selectedPlayerIds: List<String> = listOf(playerId)
) {
    init {
        require(playerId.isNotBlank()) { "Player ID cannot be blank." }
        require(selectedPlayerIds.isNotEmpty()) { "A player selection cannot be empty." }
        require(playerId in selectedPlayerIds) { "The active player must be part of the selection." }
        require(selectedPlayerIds.distinct().size == selectedPlayerIds.size) {
            "A player selection cannot contain duplicate players."
        }
    }
}

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