package org.siloserver.silo.tv.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun CinemaBayUpdateNotice(modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val state by CinemaBayUpdates.state.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycleState){
        if(lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            while(isActive){CinemaBayUpdates.check(context);delay(6*60*60*1000L)}
        }
    }
    val release=state.available ?: return
    Text("CinemaBay ${release.version} verfügbar\nEinstellungen → Allgemein → App-Updates",color=Color.White,fontSize=14.sp,
        modifier=modifier.widthIn(max=330.dp).background(Color(0xEE28151B),RoundedCornerShape(8.dp)).padding(12.dp))
}
