package org.siloserver.silo.tv.calendar

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

internal data class GermanCalendarSnapshot(val feed:GermanCalendarFeed,val offline:Boolean)

internal class CinemaBayGermanCalendarRepository(context:Context) {
    private val app=context.applicationContext
    private val cache=File(app.filesDir,"cinemabay-german-calendar.json")
    private val trust=app.getSharedPreferences("cinemabay-german-calendar",Context.MODE_PRIVATE)
    private val mutex=Mutex()
    private var loaded:GermanCalendarSnapshot?=null
    private var fetchedAt=0L
    suspend fun load(force:Boolean=false):GermanCalendarSnapshot=withContext(Dispatchers.IO) { mutex.withLock {
        val now=Instant.now();val clock=android.os.SystemClock.elapsedRealtime()
        loaded?.let{if(!force && !it.offline && it.feed.isCurrent(now) && clock-fetchedAt<6*60*60*1000L)return@withLock it}
        val blocked=trust.getBoolean("feed_blocked",false)
        var fallback:GermanCalendarFeed?=loaded?.feed?.takeIf{!blocked && it.isCurrent(now)}
        for(read in if(blocked)emptyList() else listOf<()->String>({require(cache.length()<=1_000_000);cache.readText()}, {app.assets.open("cinemabay/de-synchro.json").bufferedReader().use{it.readText()}})) {
            val candidate=runCatching{parseGermanCalendarFeed(read(),now)}.getOrNull()
            if(candidate!=null && (fallback==null || candidate.updatedAt>fallback!!.updatedAt))fallback=candidate
        }
        try {
            val connection=URL("https://raw.githubusercontent.com/EmbyBenutzer/CinemaBay-TV/main/calendar/de-synchro.json").openConnection() as HttpsURLConnection
            val text=try {
                connection.instanceFollowRedirects=false;connection.connectTimeout=15_000;connection.readTimeout=20_000
                connection.setRequestProperty("User-Agent","CinemaBay-German-Calendar/1")
                val status=connection.responseCode
                if(status>=500 || status==429)throw IOException("Termindienst nicht erreichbar")
                require(status==200){"Bestätigungsfeed nicht verfügbar"}
                connection.inputStream.use {input ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;require(output.size()+n<=1_000_000);output.write(buffer,0,n)}
                    output.toString("UTF-8")
                }
            } finally {connection.disconnect()}
            val feed=parseGermanCalendarFeed(text,Instant.now())
            require(fallback==null || feed.updatedAt>=fallback!!.updatedAt){"Älterer Feedstand wird nicht erneut übernommen"}
            currentCoroutineContext().ensureActive()
            runCatching{cache.writeText(text)}
            trust.edit().putBoolean("feed_blocked",false).apply()
            GermanCalendarSnapshot(feed,false).also{loaded=it;fetchedAt=clock}
        }catch(failure:IOException){
            val safe=fallback?.takeIf{it.isCurrent(Instant.now())} ?: throw failure
            GermanCalendarSnapshot(safe,true).also{loaded=it}
        }catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
        catch(failure:Exception){
            loaded=null;fetchedAt=0;cache.delete()
            trust.edit().putBoolean("feed_blocked",true).apply()
            throw failure
        }
    } }
}
