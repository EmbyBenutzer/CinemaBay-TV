package org.siloserver.silo.tv.ui.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.highlights.approvedHighlightVideo
import java.io.IOException

/** A single focused German clip. Disposal releases decoder, network and audio before another card starts. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun CinemaBayDirectCardTrailerPlayer(
    url: String,
    fallbackUrl: String?,
    onPlaying: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playingCallback by rememberUpdatedState(onPlaying)
    val unavailableCallback by rememberUpdatedState(onUnavailable)
    var rendered by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        require(approvedHighlightVideo(url))
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client))).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            volume = 0.7f
            repeatMode = Player.REPEAT_MODE_OFF
            val playback = this
            var fallbackUsed = false
            fun fallback(): Boolean {
                val next = fallbackUrl?.takeIf { !fallbackUsed && it != url && approvedHighlightVideo(it) } ?: return false
                fallbackUsed = true
                playback.setMediaItem(MediaItem.fromUri(next)); playback.prepare(); playback.playWhenReady = true
                return true
            }
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    rendered = true; playingCallback()
                    android.util.Log.d("CinemaBayTrailer", "filmstarts-first-video-frame")
                }
                override fun onVideoSizeChanged(size: VideoSize) {
                    android.util.Log.d("CinemaBayTrailer", "filmstarts-video-${size.width}x${size.height}")
                }
                override fun onPlayerError(error: PlaybackException) { if (!fallback()) unavailableCallback() }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) unavailableCallback() }
            })
            setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, rendered) { if (!rendered) { delay(15_000); if (!rendered) unavailableCallback() } }
    AndroidView(factory = { ctx ->
        (LayoutInflater.from(ctx).inflate(R.layout.cinemabay_highlight_player, null, false) as PlayerView).apply {
            this.player = player; useController = false
            isFocusable = false; isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
    }, modifier = modifier.focusProperties { canFocus = false }, onRelease = { it.player = null })
}

private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
    if (!approvedHighlightVideo(chain.request().url.toString())) throw IOException("Nicht freigegebene Trailer-Adresse")
    chain.proceed(chain.request())
}.build()
