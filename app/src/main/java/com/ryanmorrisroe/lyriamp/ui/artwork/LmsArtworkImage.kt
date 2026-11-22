package com.ryanmorrisroe.lyriamp.ui.artwork

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ryanmorrisroe.lyriamp.LyriampApplication
import com.ryanmorrisroe.lyriamp.data.lms.LmsArtworkSource
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import com.ryanmorrisroe.lyriamp.data.lms.artworkSource
import io.ktor.http.HttpHeaders

@Composable
fun LmsArtworkImage(
    connection: LmsServerConnection?,
    artworkId: String?,
    artworkUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit
) {
    val context = LocalContext.current
    val source = remember(connection, artworkId, artworkUrl) {
        connection?.artworkSource(artworkId, artworkUrl)
    }
    LaunchedEffect(source, artworkId, artworkUrl) {
        if (Log.isLoggable(ARTWORK_LOG_TAG, Log.DEBUG) && source == null) {
            val reason = when {
                artworkId == null && artworkUrl == null ->
                    "No artwork fields (connection=${connection != null})"
                connection == null -> "No active connection for artwork"
                else -> "LMS artwork reference could not be resolved"
            }
            Log.d(ARTWORK_LOG_TAG, reason)
        }
    }
    val request = remember(context, source) {
        source?.let { lmsArtworkImageRequest(context, it) }
    }
    val imageLoader = remember(context) {
        (context.applicationContext as? LyriampApplication)?.artworkImageLoader
            ?: Coil.imageLoader(context)
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        placeholder()
        request?.let {
            AsyncImage(
                model = it,
                imageLoader = imageLoader,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale
            )
        }
    }
}

internal fun lmsArtworkImageRequest(
    context: Context,
    source: LmsArtworkSource,
    sizePx: Int? = null
): ImageRequest = ImageRequest.Builder(context)
    .data(source.url)
    .memoryCachePolicy(CachePolicy.ENABLED)
    .diskCachePolicy(CachePolicy.ENABLED)
    .apply {
        source.authorizationHeader?.let { addHeader(HttpHeaders.Authorization, it) }
        sizePx?.let {
            size(it, it)
            allowHardware(false)
        }
    }
    .listener(ArtworkRequestLogger)
    .build()

private object ArtworkRequestLogger : ImageRequest.Listener {
    override fun onError(request: ImageRequest, result: ErrorResult) {
        if (Log.isLoggable(ARTWORK_LOG_TAG, Log.WARN)) {
            Log.w(
                ARTWORK_LOG_TAG,
                "Artwork image load failed (${result.throwable.javaClass.simpleName})"
            )
        }
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        if (Log.isLoggable(ARTWORK_LOG_TAG, Log.DEBUG)) Log.d(ARTWORK_LOG_TAG, "Artwork image loaded")
    }
}

private const val ARTWORK_LOG_TAG = "LmsArtwork"
