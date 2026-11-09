package com.ryanmorrisroe.lyriamp.data.lms

data class LmsPlayer(
    val id: String,
    val name: String,
    val modelName: String,
    val connected: Boolean,
    val power: Boolean
)

fun interface LmsPlayerDirectory {
    suspend fun getPlayers(connection: LmsServerConnection): List<LmsPlayer>
}