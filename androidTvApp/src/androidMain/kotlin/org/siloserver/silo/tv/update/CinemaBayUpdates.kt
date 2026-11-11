package org.siloserver.silo.tv.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.siloserver.silo.tv.BuildConfig
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class CinemaBayRelease(val version: String, val notes: String, val url: String? = null, val size: Long = 0, val sha256: String? = null)
internal data class CinemaBayUpdateState(
    val releases: List<CinemaBayRelease> = emptyList(), val checking: Boolean = false,
    val progress: Int? = null, val ready: File? = null, val error: String? = null, val checked: Boolean = false,
) {
    val available: CinemaBayRelease? get() = releases.firstOrNull { cinemaBayNewerVersion(it.version, BuildConfig.VERSION_NAME) && it.url != null }
    val busy: Boolean get() = checking || progress != null
}

internal fun cinemaBayVersion(value: String): List<Int>? = Regex("^v?(\\d{1,5})\\.(\\d{1,5})\\.(\\d{1,5})(?:-cinemabay(?:-ai-search)?|-updater-preview)?$")
    .matchEntire(value)?.groupValues?.drop(1)?.map(String::toInt)
internal fun cinemaBayNewerVersion(candidate: String, installed: String): Boolean {
    val a=cinemaBayVersion(candidate) ?: return false; val b=cinemaBayVersion(installed) ?: return false
    for(i in 0..2) { if(a[i]!=b[i]) return a[i]>b[i] }; return false
}
internal fun cinemaBayReleaseUrl(value: String): Boolean = runCatching {
    val u=URL(value)
    u.protocol=="https" && u.host=="github.com" && u.port in listOf(-1,443) && u.userInfo==null &&
        u.path.startsWith("/EmbyBenutzer/CinemaBay-TV/releases/download/") && u.path.endsWith(".apk") && u.query==null && u.ref==null
}.getOrDefault(false)

internal fun cinemaBayParseReleases(json: String): List<CinemaBayRelease> {
    val array=JSONArray(json)
    return buildList {
        for(i in 0 until array.length()) {
            val r=array.getJSONObject(i);val tag=r.optString("tag_name")
            if(r.optBoolean("draft") || r.optBoolean("prerelease") || cinemaBayVersion(tag)==null) continue
            val assets=r.optJSONArray("assets") ?: JSONArray()
            val expected="CinemaBay-${tag.removePrefix("v")}.apk"
            val asset=(0 until assets.length()).map{assets.getJSONObject(it)}.firstOrNull {
                it.optString("name")==expected && it.optString("state")=="uploaded" && cinemaBayReleaseUrl(it.optString("browser_download_url")) &&
                    it.optLong("size") in 1..150_000_000 && Regex("sha256:[a-fA-F0-9]{64}").matches(it.optString("digest"))
            }
            add(CinemaBayRelease(tag.removePrefix("v"),r.optString("body").take(24_000).ifBlank{"Keine Versionshinweise hinterlegt."},
                asset?.optString("browser_download_url"),asset?.optLong("size") ?: 0,asset?.optString("digest")?.removePrefix("sha256:")?.lowercase()))
        }
    }.sortedWith { a,b -> when { cinemaBayNewerVersion(a.version,b.version)->-1; cinemaBayNewerVersion(b.version,a.version)->1;else->0 } }
}

internal object CinemaBayUpdates {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex()
    private val mutable=MutableStateFlow(CinemaBayUpdateState())
    val state=mutable.asStateFlow()
    private var lastCheck=0L
    private var downloadJob: Job?=null

    fun check(context: Context, force: Boolean=false) {
        val app=context.applicationContext
        scope.launch { mutex.withLock {
            if(!force && lastCheck>0 && android.os.SystemClock.elapsedRealtime()-lastCheck<6*60*60*1000L) return@withLock
            if(mutable.value.progress!=null) return@withLock
            if(!mutable.value.checked && mutable.value.releases.isEmpty()) {
                runCatching {
                    val file=File(app.filesDir,"cinemabay-release-history.json")
                    if(file.isFile && file.length()<=1_000_000) {
                        val cached=JSONArray(file.readText())
                        val history=(0 until cached.length()).map{cached.getJSONObject(it)}.mapNotNull {
                            val version=it.optString("version")
                            if(cinemaBayVersion(version)==null)null else CinemaBayRelease(version,it.optString("notes").take(24_000))
                        }
                        mutable.update{it.copy(releases=history)}
                    }
                }
            }
            mutable.update{it.copy(checking=true,error=null)}
            try {
                val releases=mutableListOf<CinemaBayRelease>()
                for(page in 1..3) {
                    val text=readText("https://api.github.com/repos/EmbyBenutzer/CinemaBay-TV/releases?per_page=100&page=$page")
                    releases+=cinemaBayParseReleases(text)
                    if(JSONArray(text).length()<100) break
                }
                val list=releases.distinctBy{it.version}
                val nextVersion=list.firstOrNull{cinemaBayNewerVersion(it.version,BuildConfig.VERSION_NAME) && it.url!=null}?.version
                val sameDownload=mutable.value.available?.version==nextVersion
                if(!sameDownload) mutable.value.ready?.delete()
                mutable.update{it.copy(releases=list,checking=false,checked=true,ready=it.ready.takeIf{sameDownload})}
                lastCheck=android.os.SystemClock.elapsedRealtime()
                File(app.filesDir,"cinemabay-release-history.json").writeText(JSONArray().apply {
                    list.forEach { put(org.json.JSONObject().put("version",it.version).put("notes",it.notes)) }
                }.toString())
            } catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){mutable.update{it.copy(checking=false,checked=true,error="Update-Prüfung nicht möglich. Internetverbindung prüfen oder später erneut versuchen.")}}
        } }
    }

    fun download(context: Context) {
        val app=context.applicationContext
        if(downloadJob?.isActive==true || mutable.value.busy) return
        val release=mutable.value.available ?: return
        downloadJob=scope.launch {
            val directory=File(app.cacheDir,"cinemabay-updates").apply{mkdirs()}
            val part=File(directory,"update.part");val apk=File(directory,"CinemaBay-update.apk")
            mutable.update{it.copy(progress=0,error=null,ready=null)}
            try {
                part.delete();apk.delete()
                val connection=open(release.url!!)
                try {
                    check(connection.responseCode==200)
                    val hash=MessageDigest.getInstance("SHA-256");var count=0L
                    connection.inputStream.use { input -> part.outputStream().use { output ->
                        val buffer=ByteArray(64*1024)
                        while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                            count+=n;check(count<=release.size && count<=150_000_000)
                            output.write(buffer,0,n);hash.update(buffer,0,n)
                            val percent=(count*100/release.size).toInt().coerceIn(0,99)
                            if(mutable.value.progress!=percent) mutable.update{it.copy(progress=percent)}
                        }
                    } }
                    check(count==release.size)
                    check(MessageDigest.isEqual(hash.digest(),release.sha256!!.chunked(2).map{it.toInt(16).toByte()}.toByteArray()))
                } finally {connection.disconnect()}
                validateApk(app,part)
                check(part.renameTo(apk))
                mutable.update{it.copy(progress=null,ready=apk,error=null)}
            }catch(cancel:CancellationException){part.delete();mutable.update{it.copy(progress=null)};throw cancel}
            catch(_:Exception){part.delete();apk.delete();mutable.update{it.copy(progress=null,ready=null,error="Download oder Sicherheitsprüfung fehlgeschlagen. Bitte erneut versuchen.")}}
        }
    }
    fun cancelDownload(){downloadJob?.cancel()}
    fun installationError(){mutable.update{it.copy(error="Der Android-Paketinstaller konnte nicht geöffnet werden. Bitte die Installationsfreigabe prüfen.")}}

    @Suppress("DEPRECATION")
    internal fun validateApk(context:Context,file:File) {
        val pm=context.packageManager
        val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive=pm.getPackageArchiveInfo(file.absolutePath,flags) ?: error("Invalid APK")
        val installed=pm.getPackageInfo(context.packageName,flags)
        check(archive.packageName==context.packageName)
        val next=if(Build.VERSION.SDK_INT>=28) archive.longVersionCode else archive.versionCode.toLong()
        val current=if(Build.VERSION.SDK_INT>=28) installed.longVersionCode else installed.versionCode.toLong()
        check(next>current)
        if(Build.VERSION.SDK_INT>=24) check(archive.applicationInfo!!.minSdkVersion<=Build.VERSION.SDK_INT)
        fun signatures(info:PackageInfo)= (if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(""){b->"%02x".format(b)} }?.toSet().orEmpty()
        val trusted=signatures(installed);check(trusted.isNotEmpty() && signatures(archive)==trusted)
    }
    private fun readText(url:String):String {
        val c=open(url)
        try {check(c.responseCode==200);return c.inputStream.use{input ->
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;check(output.size()+n<=1_000_000);output.write(buffer,0,n)}
            output.toString("UTF-8")
        }} finally {c.disconnect()}
    }
    private fun open(initial:String):HttpsURLConnection {
        var address=URL(initial)
        repeat(6) {
            check(address.protocol=="https" && address.userInfo==null && address.port in listOf(-1,443))
            check(address.host in setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com"))
            val c=(address.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects=false;connectTimeout=15_000;readTimeout=25_000
                setRequestProperty("User-Agent","CinemaBay/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept",if(address.host=="api.github.com")"application/vnd.github+json" else "application/octet-stream")
            }
            if(c.responseCode !in listOf(301,302,303,307,308)) return c
            val location=c.getHeaderField("Location");c.disconnect();check(location!=null);address=URL(address,location)
        }
        error("Too many redirects")
    }
}
