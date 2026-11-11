package org.siloserver.silo.tv.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.koin.compose.koinInject
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

internal val CinemaBayTrailerHeight = 216.dp
internal val LocalCinemaBayTrailerGeometry = staticCompositionLocalOf<(Any, Boolean) -> Unit> { { _, _ -> } }

internal class CinemaBayCardTrailerState {
    var videoId by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var failed by mutableStateOf(false)
}

@Composable
internal fun rememberCinemaBayCardTrailer(contentId: String?, active: Boolean): CinemaBayCardTrailerState {
    // Disabled previews create neither a repository request nor a WebView.
    val state = remember(contentId, active) { CinemaBayCardTrailerState() }
    if (!active || contentId.isNullOrBlank()) return state
    val repository: CatalogRepository = koinInject()
    LaunchedEffect(contentId, state, repository) {
        delay(1500)
        val detail = try {
            (repository.getItemDetailForPrefetch(contentId) as? ApiResult.Success)?.data
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { null }
        coroutineContext.ensureActive()
        state.videoId = cinemaBayGermanTrailer(detail?.videos)
        android.util.Log.d("CinemaBayTrailer", if (state.videoId == null) "no-german-trailer" else "german-trailer-selected")
    }
    return state
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun CinemaBayCardTrailerPlayer(
    videoId: String,
    posterUrl: String?,
    onPlaying: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playingCallback by rememberUpdatedState(onPlaying)
    val unavailableCallback by rememberUpdatedState(onUnavailable)
    var playing by remember(videoId) { mutableStateOf(false) }
    val alive = remember(videoId) { AtomicBoolean(true) }
    val poster = remember(videoId, context) {
        ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    }
    val web = remember(videoId, context) {
        WebView(context).apply {
            if (org.siloserver.silo.tv.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (!alive.get()) return true
                    when (message.message()) {
                        "CinemaBayCard:playing" -> {
                            playing = true
                            poster.visibility = View.GONE
                            playingCallback()
                            android.util.Log.d("CinemaBayTrailer", "playing")
                        }
                        "CinemaBayCard:unavailable" -> {
                            unavailableCallback()
                            android.util.Log.d("CinemaBayTrailer", "unavailable")
                        }
                    }
                    return true
                }
            }
            loadDataWithBaseURL("https://de.cinemabay.tv/", cinemaBayCardTrailerHtml(videoId), "text/html", "UTF-8", null)
        }
    }
    val frame = remember(web) {
        FrameLayout(context).apply {
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(poster, FrameLayout.LayoutParams(-1, -1))
        }
    }
    DisposableEffect(poster, posterUrl) {
        val request = SingletonImageLoader.get(context).enqueue(
            ImageRequest.Builder(context).data(posterUrl)
                .target(onSuccess = { poster.setImageDrawable(it.asDrawable(context.resources)) }).build(),
        )
        onDispose { request.dispose() }
    }
    DisposableEffect(web) {
        onDispose {
            alive.set(false)
            frame.removeAllViews()
            web.stopLoading()
            web.loadUrl("about:blank")
            web.onPause()
            web.removeAllViews()
            web.destroy()
            android.util.Log.d("CinemaBayTrailer", "released")
        }
    }
    LaunchedEffect(web) {
        delay(15000)
        if (!playing && alive.get()) unavailableCallback()
    }
    // No alpha layer or reparenting: the native video surface stays attached and sized.
    AndroidView(factory = { frame }, modifier = modifier.focusProperties { canFocus = false })
}

private fun cinemaBayCardTrailerHtml(id: String): String {
    require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
    return """<!doctype html><html lang="de"><head>
        <meta name="viewport" content="width=640">
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <style>html,body{width:100%;height:100%;margin:0;overflow:hidden;background:transparent}iframe{display:block;border:0;width:100%;height:100%}</style>
        </head><body><div id="player"></div><script>
        var player,announced=false;
        function captionsOff(){if(player&&player.getOptions&&player.getOptions('captions').indexOf('track')>=0)player.setOption('captions','track',{});}
        function sizePlayer(){if(player&&player.setSize)player.setSize(innerWidth,innerHeight);}
        window.addEventListener('resize',sizePlayer);
        function onYouTubeIframeAPIReady(){player=new YT.Player('player',{
          width:innerWidth,height:innerHeight,videoId:'$id',
          playerVars:{playsinline:1,autoplay:1,controls:0,disablekb:1,cc_load_policy:0,cc_lang_pref:'de',hl:'de',origin:'https://de.cinemabay.tv'},
          events:{onReady:function(e){sizePlayer();e.target.unMute();e.target.setVolume(70);e.target.playVideo();},
          onApiChange:captionsOff,onStateChange:function(e){if(e.data===1)captionsOff();if(e.data===0)console.log('CinemaBayCard:unavailable');},
          onError:function(){console.log('CinemaBayCard:unavailable');},onAutoplayBlocked:function(){console.log('CinemaBayCard:unavailable');}}});}
        setInterval(function(){if(!announced&&player&&player.getPlayerState&&player.getPlayerState()===1&&player.getCurrentTime()>.25){announced=true;console.log('CinemaBayCard:playing');}},250);
        </script><script src="https://www.youtube.com/iframe_api"></script></body></html>""".trimIndent()
}
