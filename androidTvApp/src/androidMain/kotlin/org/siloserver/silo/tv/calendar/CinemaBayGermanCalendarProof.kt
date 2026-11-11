package org.siloserver.silo.tv.calendar

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.tv.material3.ExperimentalTvMaterial3Api
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CinemaBayGermanCalendarProof(release:GermanCalendarRelease,onDismiss:()->Unit) {
    val context=LocalContext.current
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.widthIn(max=640.dp).fillMaxWidth().heightIn(max=480.dp).background(Color(0xFF19191F),RoundedCornerShape(16.dp))
            .padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(release.series.title,color=Color.White,fontSize=22.sp,lineHeight=27.sp,fontWeight=FontWeight.Bold,maxLines=2)
            Text("Staffel ${release.season} · Folge ${release.episode}",color=Color.LightGray,fontSize=14.sp,lineHeight=18.sp)
            Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                ProofLine("Deutsche Synchro-Premiere: ${release.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}\n${release.channel} · Deutsch synchronisiert")
                ProofLine("Redaktionell bestätigt · ${release.source.publisher}")
                ProofLine(release.source.evidence)
                ProofLine("Beleg geprüft am ${release.source.checkedAt.atZone(ZoneId.of("Europe/Berlin")).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}")
                ProofLine(release.source.url)
                ProofLine("Ein bestätigter Veröffentlichungstermin ist keine Zusage, dass diese Folge bereits auf deinem Silo-Server vorhanden ist.")
            }
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Button(onClick={
                    try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(release.source.url)))}
                    catch(_:Exception){Toast.makeText(context,"Kein Browser verfügbar. Der Quellenlink steht oben.",Toast.LENGTH_LONG).show()}
                }){Text("Quelle öffnen")}
                Button(onClick=onDismiss){Text("Schließen")}
            }
        }
    }
}

@Composable
private fun ProofLine(text:String) {
    var focused by remember{mutableStateOf(false)}
    Text(text,color=Color.White,fontSize=14.sp,lineHeight=20.sp,
        modifier=Modifier.fillMaxWidth().onFocusChanged{focused=it.isFocused}.focusable()
            .border(1.dp,if(focused)Color.White else Color.Transparent,RoundedCornerShape(4.dp)).padding(8.dp))
}
