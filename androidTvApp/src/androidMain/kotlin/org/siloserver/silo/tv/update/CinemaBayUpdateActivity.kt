package org.siloserver.silo.tv.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.tv.material3.*
import org.siloserver.silo.tv.BuildConfig
import org.siloserver.silo.tv.ui.theme.SiloTvTheme

class CinemaBayUpdateActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    override fun onResume(){super.onResume();resumeTick++}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SiloTvTheme { UpdateScreen(resumeTick,::finish) } }
    }
    @OptIn(ExperimentalTvMaterial3Api::class)
    @Composable
    private fun UpdateScreen(tick:Int,onClose:()->Unit) {
        val state by CinemaBayUpdates.state.collectAsState()
        LaunchedEffect(Unit){CinemaBayUpdates.check(this@CinemaBayUpdateActivity)}
        val permitted=remember(tick){Build.VERSION.SDK_INT<26 || packageManager.canRequestPackageInstalls()}
        val available=state.available
        val history=(state.releases+cinemaBayBundledHistory).distinctBy{it.version}
            .sortedWith {a,b -> when{cinemaBayNewerVersion(a.version,b.version)->-1;cinemaBayNewerVersion(b.version,a.version)->1;else->0}}
        Row(Modifier.fillMaxSize().background(Color(0xFF101014)).padding(36.dp),horizontalArrangement=Arrangement.spacedBy(30.dp)) {
            Column(Modifier.width(310.dp).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("CinemaBay · App-Updates",fontSize=25.sp,fontWeight=FontWeight.Bold,color=Color.White)
                Text("Installiert: ${BuildConfig.VERSION_NAME}",color=Color.LightGray)
                Text(when {
                    state.checking -> "Updates werden geprüft …"
                    available!=null -> "Update ${available.version} verfügbar"
                    state.checked && state.error==null && state.releases.any{cinemaBayNewerVersion(it.version,BuildConfig.VERSION_NAME)} -> "Neue Version angekündigt; noch keine geprüfte APK verfügbar."
                    state.checked && state.error==null -> "Du verwendest die aktuelle Version."
                    else -> "Updates direkt aus dem CinemaBay-Repository auf GitHub."
                },color=Color.White,fontSize=17.sp)
                state.progress?.let { Text("Download: $it %",color=Color.White) }
                state.error?.let { Text(it,color=Color(0xFFFF8A80),fontSize=14.sp) }
                Button(onClick={CinemaBayUpdates.check(this@CinemaBayUpdateActivity,true)},enabled=!state.busy){Text("Jetzt prüfen")}
                if(available!=null && state.ready==null && state.progress==null) {
                    Button(onClick={CinemaBayUpdates.download(this@CinemaBayUpdateActivity)},enabled=!state.busy){Text("Update herunterladen")}
                    Text("${available.size/1_000_000} MB · Prüfsumme und App-Signatur werden geprüft.",fontSize=13.sp,color=Color.LightGray)
                }
                if(state.progress!=null) Button(onClick={CinemaBayUpdates.cancelDownload()}){Text("Download abbrechen")}
                if(!permitted && state.ready==null && Build.VERSION.SDK_INT>=26) {
                    Button(onClick={
                        try {startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))}
                        catch(_:Exception){CinemaBayUpdates.installationError()}
                    }){Text("Installationsfreigabe öffnen")}
                }
                if(state.ready!=null) {
                    Text("APK heruntergeladen und geprüft.",color=Color.White)
                    Button(onClick={
                        try {
                            if(!permitted && Build.VERSION.SDK_INT>=26) {
                                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))
                            } else {
                                val file=CinemaBayUpdates.state.value.ready ?: return@Button
                                val uri=FileProvider.getUriForFile(this@CinemaBayUpdateActivity,"$packageName.cinemabay.updates",file)
                                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=ClipData.newRawUri("CinemaBay Update",uri)})
                            }
                        }catch(_:Exception){CinemaBayUpdates.installationError()}
                    }){Text(if(permitted)"Update installieren" else "Installation erlauben")}
                }
                Text(if(permitted)"Die Installation bestätigst du im Android-Systemdialog." else "Ab Android 8: Beim Installieren einmalig „Unbekannte Apps installieren“ für CinemaBay erlauben. Danach hierher zurückkehren und „Update installieren“ wählen.",fontSize=13.sp,color=Color.LightGray)
                Text("Prüfung beim App-Start und auf der Startseite alle 6 Stunden. Downloads starten nur auf deinen Wunsch. Die Prüfung verbindet sich mit GitHub; Silo-Zugangsdaten werden nicht übertragen.",fontSize=12.sp,color=Color.Gray)
                Button(onClick=onClose){Text("Zurück")}
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                item {Text("Was ist neu? · Versionsverlauf",fontSize=23.sp,color=Color.White,fontWeight=FontWeight.Bold)}
                history.forEach { release ->
                    item(key="title-${release.version}"){Text("CinemaBay ${release.version}",fontSize=20.sp,color=Color(0xFFFF4B52),fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))}
                    items(release.notes.split(Regex("\\n\\s*\\n")).filter{it.isNotBlank()}.flatMap{it.chunked(1200)}) { paragraph ->
                        var focused by remember {mutableStateOf(false)}
                        Text(paragraph.replace(Regex("(?m)^#{1,6}\\s*"),""),color=Color.White,fontSize=15.sp,
                            modifier=Modifier.fillMaxWidth().onFocusChanged{focused=it.isFocused}.focusable()
                                .background(Color(0xFF1D1D24),RoundedCornerShape(8.dp))
                                .border(2.dp,if(focused)Color.White else Color.Transparent,RoundedCornerShape(8.dp)).padding(12.dp))
                    }
                }
            }
        }
    }
    companion object { fun open(context:Context){context.startActivity(Intent(context,CinemaBayUpdateActivity::class.java))} }
}

internal val cinemaBayBundledHistory=listOf(
    CinemaBayRelease("0.1.3","App-Updates direkt in CinemaBay: Hinweis auf der Startseite, Update-Prüfung, Download und Installation über die Einstellungen. Versionsverlauf mit alten und neuen Änderungen. Geprüfte Downloads aus dem CinemaBay-GitHub-Repository; Installationsfreigabe über Android."),
    CinemaBayRelease("0.1.2","Folgenzahlen und persönlicher Wiedergabestand erscheinen auch auf Episoden-Kacheln mit Serien-Cover. Dafür wird die übergeordnete Serie verwendet."),
    CinemaBayRelease("0.1.1","Serien-Cover zeigen verfügbare und vollständig gesehene Folgen im aktuellen Profil, einschließlich Specials. Verzögerte Abfragen mit höchstens zwei gleichzeitigen Anfragen."),
    CinemaBayRelease("0.1.0","CinemaBay AI Search 1.0: lokale Tippfehlersuche, deutsche Suchanfragen, kombinierbare Filter und Live-Ergebnisse mit Filmplakaten. Optionaler externer KI-Interpreter vorbereitet; kein eingebautes Sprachmodell."),
    CinemaBayRelease("0.0.9","Frei wählbare automatische Trailer-Vorschau, standardmäßig aus. Ausgewählte Kacheln erweitern sich nach kurzem Verweilen auf 16:9. Nur passende deutschsprachige Trailer, mit Ton und ohne automatisch eingeblendete Untertitel."),
    CinemaBayRelease("0.0.8","Neue Profilbildauswahl nach Netflix, Disney+, Prime Video und HBO/Max mit Serie-/Franchise-Auswahl. Vorherige Avatar-Stile aus der Auswahl entfernt; vorhandene Profilbilder bleiben lesbar."),
    CinemaBayRelease("0.0.7","Frühe experimentelle automatische Trailer-Vorschau entfernt. Normale Wiedergabe und manuell aufrufbare Trailer bleiben erhalten."),
    CinemaBayRelease("0.0.5","Vollständiges Piratenschiff als App-Icon und TV-Kachel. Innerhalb der App wieder CinemaBay-Schriftzug mit vereinfachtem Segel-/Play-Symbol."),
    CinemaBayRelease("0.0.4","Optionaler Leichtmodus in den Einstellungen. Reduzierte Grafikeffekte, kleinere Bilder und weniger Vorladen. Standard bleibt normale Darstellung; Wiedergabequalität ist unabhängig."),
    CinemaBayRelease("0.0.3","„App beenden“ mit Bestätigung im Profilmenü. Wiedergabe wird beendet und die App-Aufgabe geschlossen; Anmeldung bleibt erhalten. Vollständiges Piratenschiff als App-Icon wiederhergestellt."),
)
