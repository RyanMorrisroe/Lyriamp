package com.ryanmorrisroe.lyriamp.data.lms

import java.net.URI
import java.net.URISyntaxException

data class LmsArtworkSource(
    val url: String,
    val authorizationHeader: String?
)

fun LmsServerConnection.artworkSource(
    artworkId: String?,
    artworkUrl: String?
): LmsArtworkSource? {
    val serverUri = URI(jsonRpcUrl())
    val candidateUri = artworkUrl
        ?.takeIf(String::isNotBlank)
        ?.let { resolveArtworkUri(serverUri, it) }
        ?: artworkId
            ?.takeIf(String::isLmsArtworkId)
            ?.let { serverUri.resolve("/music/$it/cover.jpg") }
        ?: return null

    if ((!candidateUri.scheme.equals("http", ignoreCase = true) &&
            !candidateUri.scheme.equals("https", ignoreCase = true)) ||
        candidateUri.host.isNullOrBlank() ||
        candidateUri.rawUserInfo != null
    ) {
        return null
    }

    val targetUri = candidateUri.normalizeAtRoot() ?: return null
    val sameOrigin = serverUri.scheme.equals(targetUri.scheme, ignoreCase = true) &&
        serverUri.host.equals(targetUri.host, ignoreCase = true) &&
        serverUri.effectivePort() == targetUri.effectivePort()
    return LmsArtworkSource(
        url = targetUri.normalize().toASCIIString(),
        authorizationHeader = if (sameOrigin) basicAuthorizationHeader() else null
    )
}

internal fun String.isLmsArtworkId(): Boolean =
    isNotEmpty() && all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

private fun resolveArtworkUri(serverUri: URI, value: String): URI? {
    val artworkUri = try {
        URI(value.trim())
    } catch (_: URISyntaxException) {
        return null
    }
    return if (artworkUri.isAbsolute) artworkUri else serverUri.resolve(artworkUri)
}

private fun URI.normalizeAtRoot(): URI? {
    val pathSegments = mutableListOf<String>()
    rawPath.orEmpty().split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (pathSegments.isNotEmpty()) pathSegments.removeAt(pathSegments.lastIndex)
            else -> pathSegments += segment
        }
    }
    val path = buildString {
        append('/')
        append(pathSegments.joinToString("/"))
        if (rawPath?.endsWith('/') == true && pathSegments.isNotEmpty()) append('/')
    }
    val normalized = buildString {
        append(scheme)
        append("://")
        append(rawAuthority)
        append(path)
        rawQuery?.let { append('?').append(it) }
        rawFragment?.let { append('#').append(it) }
    }
    return try {
        URI(normalized)
    } catch (_: URISyntaxException) {
        null
    }
}

private fun URI.effectivePort(): Int = if (port != -1) port else if (scheme.equals("https", true)) 443 else 80
