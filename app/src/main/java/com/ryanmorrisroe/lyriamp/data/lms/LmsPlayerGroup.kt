package com.ryanmorrisroe.lyriamp.data.lms

import java.util.Locale

data class SavedLmsPlayerGroup(
    val id: String,
    val endpointKey: String,
    val name: String,
    val playerIds: List<String>
) {
    init {
        require(endpointKey.isNotBlank()) { "Server endpoint cannot be blank." }
        require(name.isNotBlank()) { "A player group name cannot be blank." }
        require(playerIds.size >= 2) { "A player group must contain at least two players." }
        require(playerIds.all(String::isNotBlank)) { "Player IDs cannot be blank." }
        require(playerIds.distinct().size == playerIds.size) {
            "A player group cannot contain duplicate players."
        }
    }
}

fun LmsServerConnection.endpointKey(): String {
    jsonRpcUrl()
    val normalizedHost = host.trim()
        .removePrefix("[")
        .removeSuffix("]")
        .lowercase(Locale.ROOT)
    val formattedHost = if (':' in normalizedHost) "[$normalizedHost]" else normalizedHost
    return "${if (useHttps) "https" else "http"}://$formattedHost:$port"
}
