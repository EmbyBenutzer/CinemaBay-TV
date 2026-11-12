package org.siloserver.silo.tv.highlights

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

internal sealed interface HighlightTrailerStatus {
    data object Checking:HighlightTrailerStatus
    data class Available(val trailer:HighlightTrailer):HighlightTrailerStatus
    data object Missing:HighlightTrailerStatus
    data object Failed:HighlightTrailerStatus
}
internal data class HighlightTrailerProbe(val revision:Int,val attempt:Int,val status:HighlightTrailerStatus)

/** All cards stay D-pad reachable, but unavailable trailers have no click action. */
@Composable
internal fun CinemaBayHighlightTrailerLink(status:HighlightTrailerStatus,onActivate:(()->Unit)?,modifier:Modifier=Modifier) {
    var focused by remember{mutableStateOf(false)}
    val action by rememberUpdatedState(onActivate)
    val label=when(status) {
        HighlightTrailerStatus.Checking->"Trailer wird geprüft …"
        is HighlightTrailerStatus.Available->"Hier Trailer anschauen"
        HighlightTrailerStatus.Missing->"Kein deutscher Trailer verfügbar"
        HighlightTrailerStatus.Failed->"Prüfung fehlgeschlagen – erneut prüfen"
    }
    val foreground=if(focused)Color(0xFF151B28) else when(status) {
        HighlightTrailerStatus.Missing,HighlightTrailerStatus.Checking->Color(0xFFB5BED1)
        HighlightTrailerStatus.Failed->Color(0xFFFFD296)
        is HighlightTrailerStatus.Available->Color(0xFFF4BBC5)
    }
    Box(modifier=modifier
        .onFocusChanged{focused=it.isFocused}
        .onKeyEvent{event->
            val activate=action
            val center=event.key in listOf(Key.DirectionCenter,Key.Enter,Key.NumPadEnter)
            if(center && activate!=null) {
                if(event.type==KeyEventType.KeyUp)activate()
                true
            } else false
        }
        .semantics(mergeDescendants=true){
            if(onActivate!=null){role=Role.Button;onClick(label){action?.invoke();true}}
        }
        .focusable()
        .background(if(focused)Color(0xFFEDEEF1)else Color.Transparent,RoundedCornerShape(24.dp))
        .heightIn(min=40.dp).padding(horizontal=16.dp,vertical=8.dp),
        contentAlignment=Alignment.CenterStart,
    ) {
        Text(label,fontSize=12.sp,lineHeight=16.sp,color=foreground,
            textDecoration=if(onActivate!=null)TextDecoration.Underline else TextDecoration.None)
    }
}
