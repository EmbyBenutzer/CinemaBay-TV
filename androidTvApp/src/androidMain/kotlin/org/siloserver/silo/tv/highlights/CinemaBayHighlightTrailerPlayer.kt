package org.siloserver.silo.tv.highlights

import android.view.ViewGroup
import android.view.LayoutInflater
import org.siloserver.silo.tv.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.io.IOException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun CinemaBayHighlightTrailerDialog(title:String,trailer:HighlightTrailer?,loading:Boolean,error:String?,onClose:()->Unit) {
    val context=LocalContext.current
    val close by rememberUpdatedState(onClose)
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val closeFocus=remember{FocusRequester()}
    var playbackError by remember(trailer){mutableStateOf<String?>(null)}
    var playing by remember(trailer){mutableStateOf(false)}
    var fallbackActive by remember(trailer){mutableStateOf(false)}
    val player=remember(trailer){trailer?.let{
        val source=it
        require(approvedHighlightVideo(source.url))
        val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor{chain->
                if(!approvedHighlightVideo(chain.request().url.toString()))throw IOException("Nicht freigegebene Video-Adresse")
                chain.proceed(chain.request())
            }.build()
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client))).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
            setHandleAudioBecomingNoisy(true)
            trackSelectionParameters=trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
            repeatMode=Player.REPEAT_MODE_OFF
            val playback=this
            android.util.Log.d("CinemaBayHighlights","selected-source-height-${source.preferredHeight ?: 0}")
            addListener(object:Player.Listener {
                override fun onPlaybackStateChanged(state:Int){
                    android.util.Log.d("CinemaBayHighlights","trailer-state-$state")
                    if(state==Player.STATE_ENDED)close()
                }
                override fun onIsPlayingChanged(value:Boolean){playing=value;android.util.Log.d("CinemaBayHighlights","trailer-playing-$value")}
                override fun onRenderedFirstFrame(){android.util.Log.d("CinemaBayHighlights","trailer-first-video-frame")}
                override fun onVideoSizeChanged(size:VideoSize){android.util.Log.d("CinemaBayHighlights","trailer-video-${size.width}x${size.height}")}
                override fun onPlayerError(error:PlaybackException){
                    if(tryCinemaBayTrailerFallback(playback,source))fallbackActive=true
                    else playbackError="Der Trailer kann gerade nicht abgespielt werden."
                }
            })
            setMediaItem(MediaItem.fromUri(it.url));prepare();playWhenReady=true
        }
    }}
    DisposableEffect(player,lifecycle){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)player?.pause()}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);player?.release()}
    }
    LaunchedEffect(player,playing,fallbackActive){
        if(player!=null && !playing){delay(30_000);if(player.playbackState==Player.STATE_BUFFERING){
            if(trailer!=null && tryCinemaBayTrailerFallback(player,trailer))fallbackActive=true
            else {player.pause();playbackError="Der Trailer lädt zu lange. Bitte später erneut versuchen."}
        }}
    }
    LaunchedEffect(loading,error,playbackError) {
        if(loading || error!=null || playbackError!=null){withFrameNanos{};runCatching{closeFocus.requestFocus()}}
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if(player!=null && playbackError==null)AndroidView(factory={ctx->(LayoutInflater.from(ctx).inflate(R.layout.cinemabay_highlight_player,null,false) as PlayerView).apply {
                this.player=player;useController=true;controllerShowTimeoutMs=2500
                layoutParams=ViewGroup.LayoutParams(-1,-1);setShowSubtitleButton(false);post{requestFocus()}
            }},modifier=Modifier.fillMaxSize(),onRelease={it.player=null})
            if(loading || error!=null || playbackError!=null)Column(Modifier.align(Alignment.Center).widthIn(max=650.dp).padding(32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                Text(title,color=Color.White,fontSize=23.sp)
                Text(error ?: playbackError ?: "Deutscher Trailer wird geladen …",color=Color.White,fontSize=17.sp)
                Button(onClick=onClose,modifier=Modifier.focusRequester(closeFocus)){Text("Zur Übersicht")}
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun tryCinemaBayTrailerFallback(player:Player,source:HighlightTrailer):Boolean {
    val url=highlightVideoFallback(source,player.currentMediaItem?.localConfiguration?.uri?.toString()) ?: return false
    val resume=player.playWhenReady
    val position=player.currentPosition.coerceAtLeast(0L)
    android.util.Log.d("CinemaBayHighlights","fallback-to-original-720-source")
    player.setMediaItem(MediaItem.fromUri(url),position)
    player.prepare()
    player.playWhenReady=resume
    return true
}
