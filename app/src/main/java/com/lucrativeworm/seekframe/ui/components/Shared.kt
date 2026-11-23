package com.lucrativeworm.seekframe.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.videoFrameMillis
import com.lucrativeworm.seekframe.data.Photo

/** Scopes for the grid ↔ viewer shared-element hop, provided once at the root. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Marks this node as "the same print" as its twin on the other screen. */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.sharedPhoto(photoId: Long): Modifier = composed {
    val shared = LocalSharedScope.current
    val nav = LocalNavScope.current
    if (shared == null || nav == null) return@composed this
    with(shared) {
        this@composed.sharedElement(
            sharedContentState = rememberSharedContentState("photo-$photoId"),
            animatedVisibilityScope = nav,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.86f, stiffness = 420f) },
        )
    }
}

fun thumbKey(photo: Photo) = "thumb:${photo.id}"

/**
 * @param frameMs for videos: show the frame at this time (e.g. where a search matched) instead of
 * the poster frame.
 */
fun thumbRequest(context: android.content.Context, photo: Photo, px: Int = 384, frameMs: Long? = null): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.parse(photo.uri))
        .size(px)
        .memoryCacheKey(if (frameMs != null) "${thumbKey(photo)}@$frameMs" else thumbKey(photo))
        .apply { if (photo.isVideo && frameMs != null) videoFrameMillis(frameMs) }
        .crossfade(180)
        .build()

/** Full-size request that shows the grid thumbnail instantly while the big decode runs. */
fun fullRequest(context: android.content.Context, photo: Photo): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.parse(photo.uri))
        .size(2048)
        .placeholderMemoryCacheKey(thumbKey(photo))
        .crossfade(false)
        .build()

