package org.siloserver.silo.tv.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.siloserver.silo.tv.LocalCinemaBayCardTrailers
import org.siloserver.silo.tv.LocalCinemaBayPreviewAllowed

@Composable
internal fun rememberCinemaBayWideTrailer(type: String?, contentId: String?, seriesId: String?, focused: Boolean): CinemaBayCardTrailerState {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val id = cinemaBayTrailerContentId(type, contentId, seriesId)
    return rememberCinemaBayCardTrailer(id, focused && id != null && LocalCinemaBayCardTrailers.current &&
        LocalCinemaBayPreviewAllowed.current && lifecycle == Lifecycle.State.RESUMED)
}

@Composable
internal fun CinemaBayWideTrailerOverlay(state: CinemaBayCardTrailerState, poster: String?, modifier: Modifier) {
    val id = state.videoId?.takeUnless { state.failed } ?: return
    val direct = state.directUrl
    if (direct != null) CinemaBayDirectCardTrailerPlayer(direct, state.fallbackUrl,
        onPlaying = { state.playing = true }, onUnavailable = { state.rejectCurrent() }, modifier = modifier)
    else CinemaBayCardTrailerPlayer(id, poster, germanSubtitles = state.original,
        onCaptionStatus = { state.captionStatus = it }, onPlaying = { state.playing = true },
        onUnavailable = { state.rejectCurrent() }, modifier = modifier)
}
