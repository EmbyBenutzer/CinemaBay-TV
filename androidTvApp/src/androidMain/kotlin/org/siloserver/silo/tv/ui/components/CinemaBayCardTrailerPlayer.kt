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
    var directUrl by mutableStateOf<String?>(null)
    var fallbackUrl by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var original by mutableStateOf(false)
    var source by mutableStateOf("")
    var captionStatus by mutableStateOf("")
    var retry by mutableIntStateOf(0)
    val rejected = mutableSetOf<String>()
    fun rejectCurrent() {
        videoId?.let { rejected += it }
        videoId = null
        directUrl = null
        fallbackUrl = null
        playing = false
        if (rejected.size >= 8) failed = true else retry++
    }
}

@Composable
internal fun rememberCinemaBayCardTrailer(contentId: String?, active: Boolean): CinemaBayCardTrailerState {
    // Disabled previews create neither a repository request nor a WebView.
    val state = remember(contentId, active) { CinemaBayCardTrailerState() }
    if (!active || contentId.isNullOrBlank()) return state
    val repository: CatalogRepository = koinInject()
    val sourceContext = LocalContext.current
    val extraSource = remember(sourceContext) { CinemaBayKinoCheckSource(sourceContext) }
    val filmstarts = remember(sourceContext) { CinemaBayFilmstartsSource(sourceContext) }
    LaunchedEffect(contentId, state, repository, state.retry) {
        delay(1500)
        val detail = try {
            (repository.getItemDetailForPrefetch(contentId) as? ApiResult.Success)?.data
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { null }
        coroutineContext.ensureActive()
        if (state.failed) return@LaunchedEffect
        if (detail == null) { state.captionStatus = "Trailerdaten derzeit nicht verfügbar"; return@LaunchedEffect }
        val selected = try { resolveCinemaBayCardTrailer(detail, state.rejected.toSet(), germanAlternative = { filmstarts.trailer(detail, state.rejected.toSet()) }, subtitledOriginal = { filmstarts.trailer(detail, state.rejected.toSet(), originalWithSubtitles = true) }) { language -> extraSource.trailers(detail, language) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { state.captionStatus = "Trailerprüfung derzeit nicht möglich"; state.failed = true; null }
        coroutineContext.ensureActive()
        if (selected == null && !state.failed) state.captionStatus = "Kein passender Trailer verfügbar"
        state.original = selected?.original == true
        state.source = selected?.source.orEmpty()
        state.captionStatus = if (selected?.germanCaptionsEmbedded == true) "Originaltrailer · deutsche Untertitel" else if (state.original) "Originaltrailer · deutsche Untertitel, falls verfügbar" else state.captionStatus
        state.directUrl = selected?.directUrl
        state.fallbackUrl = selected?.fallbackUrl
        state.videoId = selected?.videoId
        android.util.Log.d("CinemaBayTrailer", when { selected == null -> "no-trailer-available"; selected.original -> "original-trailer-selected"; else -> "german-trailer-selected-${selected.source}" })
    }
    return state
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun CinemaBayCardTrailerPlayer(
    videoId: String,
    posterUrl: String?,
    germanSubtitles: Boolean = false,
    onCaptionStatus: (String) -> Unit = {},
    onPlaying: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val captionsCallback by rememberUpdatedState(onCaptionStatus)
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
                        "CinemaBayCard:captions-de" -> captionsCallback("Originaltrailer · deutsche Untertitel")
                        "CinemaBayCard:captions-missing" -> {
                            captionsCallback("Originaltrailer · keine deutschen Untertitel verfügbar")
                            if (germanSubtitles) unavailableCallback()
                        }
                        "CinemaBayCard:unavailable" -> {
                            unavailableCallback()
                            android.util.Log.d("CinemaBayTrailer", "unavailable")
                        }
                    }
                    return true
                }
            }
            loadDataWithBaseURL("https://de.cinemabay.tv/", cinemaBayCardTrailerHtml(videoId, germanSubtitles), "text/html", "UTF-8", null)
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

internal fun cinemaBayCardTrailerHtml(id: String, germanSubtitles: Boolean = false): String {
    require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
    return """<!doctype html><html lang="de"><head>
        <meta name="viewport" content="width=640">
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <style>html,body{width:100%;height:100%;margin:0;overflow:hidden;background:transparent}iframe{display:block;border:0;width:100%;height:100%}</style>
        </head><body><div id="player"></div><script>
        var player,announced=false,captionsReady=!$germanSubtitles;
        function captionsOff(){if(player&&player.getOptions&&player.getOptions('captions').indexOf('track')>=0)player.setOption('captions','track',{});}
        function applyCaptions(){
          if(!$germanSubtitles){captionsOff();return;}
          try{if(player&&player.getOptions&&player.getOptions().indexOf('captions')>=0){
            player.setOption('captions','track',{languageCode:'de'});
            var track=player.getOption('captions','track');
            if(track&&track.languageCode){if(track.languageCode.split('-')[0]==='de'){captionsReady=true;player.unMute();player.setVolume(70);console.log('CinemaBayCard:captions-de');}else{captionsOff();console.log('CinemaBayCard:captions-missing');}}
          }}catch(ignore){}
        }
        function sizePlayer(){if(player&&player.setSize)player.setSize(innerWidth,innerHeight);}
        window.addEventListener('resize',sizePlayer);
        function onYouTubeIframeAPIReady(){player=new YT.Player('player',{
          width:innerWidth,height:innerHeight,videoId:'$id',
          playerVars:{playsinline:1,autoplay:1,controls:0,disablekb:1,cc_load_policy:${if (germanSubtitles) 1 else 0},cc_lang_pref:'de',hl:'de',origin:'https://de.cinemabay.tv'},
          events:{onReady:function(e){sizePlayer();if($germanSubtitles){e.target.mute();}else{e.target.unMute();e.target.setVolume(70);}e.target.playVideo();},
          onApiChange:applyCaptions,onStateChange:function(e){if(e.data===1){applyCaptions();if($germanSubtitles)setTimeout(function(){try{var t=player.getOption('captions','track');if(!t||!t.languageCode){captionsOff();console.log('CinemaBayCard:captions-missing');}}catch(ignore){console.log('CinemaBayCard:captions-missing');}},8000);}if(e.data===0)console.log('CinemaBayCard:unavailable');},
          onError:function(){console.log('CinemaBayCard:unavailable');},onAutoplayBlocked:function(){console.log('CinemaBayCard:unavailable');}}});}
        setInterval(function(){if(!announced&&captionsReady&&player&&player.getPlayerState&&player.getPlayerState()===1&&player.getCurrentTime()>.25){announced=true;console.log('CinemaBayCard:playing');}},250);
        </script><script src="https://www.youtube.com/iframe_api"></script></body></html>""".trimIndent()
}
