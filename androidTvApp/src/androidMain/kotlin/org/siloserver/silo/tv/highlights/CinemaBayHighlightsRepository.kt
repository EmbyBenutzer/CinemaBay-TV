package org.siloserver.silo.tv.highlights

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal enum class HighlightKind { Movie, Series }
internal data class CinemaBayHighlight(val id:String,val title:String,val date:LocalDate,val kind:HighlightKind,
    val poster:String?,val page:String,val provider:String,val season:String="",
    val genres:List<String> = emptyList(),val summary:String="",val metadataLoaded:Boolean=false)
internal data class HighlightsResult(val items:List<CinemaBayHighlight>,val notices:List<String>)
internal data class HighlightTrailer(val url:String,val title:String,val fallbackUrl:String?=null,val preferredHeight:Int?=null)
internal class HighlightTrailerUnavailableException : IOException("Für diesen Titel ist derzeit kein direkt abspielbarer deutscher Trailer verfügbar.")
internal class HighlightHttpException(val status:Int) : IOException("Vorschauseite derzeit nicht erreichbar")

internal data class HighlightMetadata(val genres:List<String>,val summary:String)
internal fun highlightGenres(values:List<String>):List<String> {
    val aliases=mapOf("adventure" to "Abenteuer","comedy" to "Komödie","crime" to "Krimi","documentary" to "Dokumentation",
        "family" to "Familie","biography" to "Biografie","history" to "Historie","music" to "Musik","romance" to "Romantik",
        "science fiction" to "Science-Fiction","science-fiction" to "Science-Fiction","sci-fi" to "Science-Fiction","war" to "Krieg")
    return values.map{it.trim()}.filter{it.length in 2..45}.map{aliases[it.lowercase(Locale.ROOT)] ?: it.replaceFirstChar{c->c.titlecase(Locale.GERMAN)}}.distinct().sorted()
}
internal fun highlightSummary(value:String):String {
    val text=Jsoup.parseBodyFragment(value).text().replace(Regex("\\s+")," ").trim()
    if(text.length<=240)return text
    val clipped=text.take(237);return clipped.substringBeforeLast(' ',clipped).trimEnd('.',',',';')+"…"
}
internal fun parseHighlightMetadata(html:String,page:String,kind:HighlightKind):HighlightMetadata {
    val doc=Jsoup.parse(html,page)
    fun objects(element:JsonElement):List<JsonObject> = when(element) {
        is JsonArray->element.flatMap(::objects)
        is JsonObject->listOf(element)+(element["@graph"]?.let(::objects) ?: emptyList())
        else->emptyList()
    }
    val expected=if(kind==HighlightKind.Movie)"Movie" else "TVSeries"
    val model=doc.select("script[type=application/ld+json]").flatMap { script ->
        runCatching{objects(Json.parseToJsonElement(script.data()))}.getOrDefault(emptyList())
    }.firstOrNull { obj ->
        val types=obj["@type"]
        val matches=when(types){is JsonPrimitive->types.content==expected;is JsonArray->types.any{(it as? JsonPrimitive)?.content==expected};else->false}
        val url=(obj["url"] as? JsonPrimitive)?.contentOrNull
        matches && (url==null || runCatching{URI(url).path.trimEnd('/')==URI(page).path.trimEnd('/')}.getOrDefault(false))
    }
    val genres=when(val field=model?.get("genre")) {
        is JsonArray->field.mapNotNull{(it as? JsonPrimitive)?.contentOrNull}
        is JsonPrimitive->listOfNotNull(field.contentOrNull)
        else->emptyList()
    }
    val description=(model?.get("description") as? JsonPrimitive)?.contentOrNull.orEmpty()
    return HighlightMetadata(highlightGenres(genres),highlightSummary(description))
}
internal fun highlightHasGenre(item:CinemaBayHighlight,genre:String):Boolean =
    genre.isBlank() || if(genre=="Genre unbekannt")item.genres.isEmpty() else genre in item.genres

private const val MOVIES="https://www.filmstarts.de"
private const val SERIES="https://www.serienjunkies.de"
private val germanDate=DateTimeFormatter.ofPattern("d. MMMM uuuu",Locale.GERMAN).withResolverStyle(java.time.format.ResolverStyle.STRICT)
private val datePattern=Regex("\\d{1,2}\\. [\\p{L}]+ \\d{4}")
internal fun highlightDate(value:String):LocalDate? = datePattern.find(value)?.value?.let {
    runCatching {LocalDate.parse(it,germanDate)}.getOrNull()
}
internal fun approvedHighlightPage(url:String):Boolean=runCatching {
    val u=URI(url)
    u.scheme=="https" && u.port in listOf(-1,443) && u.userInfo==null && u.fragment==null &&
        (u.host=="www.filmstarts.de" && (u.path.startsWith("/filme-vorschau/de/") || Regex("/kritiken/\\d+(?:/trailer/\\d+)?\\.html").matches(u.path)) ||
         u.host=="www.serienjunkies.de" && (u.path=="/docs/serienplaner.html" || Regex("/[a-z0-9-]+/").matches(u.path) || Regex("/video/[a-z0-9_-]+\\.html").matches(u.path)))
}.getOrDefault(false)
/** Positive allowlist: no HTML, ad endpoints, third-party players or ad redirects are accepted. */
internal fun approvedHighlightVideo(url:String):Boolean=runCatching {
    val u=URI(url)
    u.scheme=="https" && u.port in listOf(-1,443) && u.userInfo==null && u.fragment==null &&
        (u.host=="de.vid.web.acsta.net" && u.path.startsWith("/nmedia/") || u.host=="sjme.de" && u.path.startsWith("/v/")) &&
        u.path.endsWith(".mp4",true)
}.getOrDefault(false)
private fun posterUrl(value:String?):String?=value?.takeIf {runCatching {
    val u=URI(it);u.scheme=="https" && (u.host?.endsWith(".acsta.net")==true || u.host=="id-serienjunkies-production.s3.eu-central-1.amazonaws.com")
}.getOrDefault(false)}
internal fun parseHighlightMovies(html:String):List<CinemaBayHighlight> {
    val doc=Jsoup.parse(html,MOVIES)
    require(doc.select(".entity-card .meta-title-link").isNotEmpty()) {"Filmvorschau wurde geändert"}
    return doc.select(".entity-card").mapNotNull { card ->
        val a=card.selectFirst(".meta-title-link[href]") ?: return@mapNotNull null
        val page=a.absUrl("href");if(!approvedHighlightPage(page))return@mapNotNull null
        val date=highlightDate(card.selectFirst(".date")?.text().orEmpty()) ?: return@mapNotNull null
        val img=card.selectFirst("img.thumbnail-img")
        CinemaBayHighlight(page,a.text(),date,HighlightKind.Movie,posterUrl(img?.attr("data-src")?.ifBlank {img.attr("src")}),page,"Kino in Deutschland",genres=highlightGenres(card.select(".meta-body-info .dark-grey-link").map{it.text()}),summary=highlightSummary(card.selectFirst(".synopsis")?.text().orEmpty()))
    }.distinctBy {it.id}
}
internal fun parseHighlightSeries(html:String,today:LocalDate):List<CinemaBayHighlight> {
    val doc=Jsoup.parse(html,SERIES)
    require(doc.select(".sjf-show-planner-item").isNotEmpty()) {"Serienvorschau wurde geändert"}
    return doc.select(".sjf-show-planner-item").mapNotNull { card ->
        val title=card.selectFirst(".sjf-show-planner-title") ?: return@mapNotNull null
        val a=title.selectFirst("a[href]") ?: return@mapNotNull null
        val page=a.absUrl("href");if(!approvedHighlightPage(page))return@mapNotNull null
        val label=card.selectFirst(".sjf-show-planner-date")?.text().orEmpty()
        val date=highlightDate(label) ?: when {
            label.contains("Heute!")->today
            label.contains("Morgen")->today.plusDays(1)
            else->return@mapNotNull null // Never invent a day for month-only announcements.
        }
        val provider=card.selectFirst(".sjf-show-planner-logo img")?.attr("alt").orEmpty()
        val season=Regex("Staffel \\d+").find(title.text())?.value.orEmpty()
        CinemaBayHighlight("$page|$date|$provider|$season",a.text(),date,HighlightKind.Series,
            posterUrl(card.selectFirst(".sjf-show-planner-image img")?.attr("src")),page,provider.ifBlank {"Deutschland-Start"},season)
    }.distinctBy{it.id}
}
private fun decodedLinks(doc:Document):List<String> = doc.select("a[href],span[class]").mapNotNull { e ->
    if(e.hasAttr("href"))return@mapNotNull e.absUrl("href")
    e.classNames().firstNotNullOfOrNull { cls -> if(!cls.startsWith("ACr"))null else runCatching {
        val path=String(java.util.Base64.getDecoder().decode(cls.replace("ACr","")),Charsets.UTF_8)
        URI(MOVIES).resolve(path).toString()
    }.getOrNull() }
}.filter(::approvedHighlightPage).distinct()
internal fun highlightTrailerPages(html:String,item:CinemaBayHighlight):List<String> {
    val doc=Jsoup.parse(html,item.page)
    return if(item.kind==HighlightKind.Movie) {
        val id=URI(item.page).path.removePrefix("/kritiken/").removeSuffix(".html")
        decodedLinks(doc).filter {URI(it).path.startsWith("/kritiken/$id/trailer/")}.take(4)
    } else {
        val slug=URI(item.page).path.trim('/')
        val embedded=Regex("\"embedUrl\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")").findAll(html).mapNotNull {
            runCatching{Json.decodeFromString<String>(it.groupValues[1])}.getOrNull()
        }.toList()
        (embedded+decodedLinks(doc)).filter {
            approvedHighlightPage(it) && URI(it).path.startsWith("/video/$slug-") &&
                Regex("_de(?:[-.]|$)").containsMatchIn(URI(it).path)
        }.distinct().take(4)
    }
}
internal fun highlightVideoFallback(source:HighlightTrailer,currentUrl:String?):String? = source.fallbackUrl?.takeIf {
    approvedHighlightVideo(it) && it!=source.url && it!=currentUrl
}

internal data class HighlightVideoCandidate(val url:String,val height:Int?=null)
internal fun highlightVideoHeight(value:String):Int? {
    val text=value.lowercase(Locale.ROOT)
    Regex("(?:^|[^0-9])(2160|1440|1080|720|576|480|360|240)(?:p|[^0-9]|$)").find(text)?.let{return it.groupValues[1].toInt()}
    if(text.contains("full hd") || text.contains("fullhd") || text.contains("fhd"))return 1080
    if(text=="hd" || Regex("_hd_").containsMatchIn(text))return 720
    return null
}
internal fun selectHighlightVideo(title:String,candidates:List<HighlightVideoCandidate>):HighlightTrailer? {
    fun height(c:HighlightVideoCandidate)=c.height ?: highlightVideoHeight(URI(c.url).path)
    fun rank(c:HighlightVideoCandidate)=when(val h=height(c)) {
        1080->3000
        720->2000
        null->1000 // Preserve a source's original default ahead of explicit SD encodes.
        else->h
    }
    val accepted=candidates.filter{approvedHighlightVideo(it.url) && (height(it)==null || height(it)!!<=1080)}
        .groupBy{it.url}.map{(_,same)->same.filter{it.height!=null}.maxByOrNull(::rank) ?: same.first()}.sortedByDescending(::rank)
    val best=accepted.firstOrNull() ?: return null
    val fallback=if(height(best)==1080)accepted.firstOrNull{height(it)==720}
        ?: accepted.firstOrNull{height(it)==null} else null
    return HighlightTrailer(best.url,title,fallback?.url, height(best))
}
internal fun parseHighlightTrailer(html:String,page:String):HighlightTrailer? {
    val doc=Jsoup.parse(html,page)
    val title=doc.selectFirst(".media-info-title")?.text() ?: doc.selectFirst("h1")?.text().orEmpty()
    val movie=URI(page).host=="www.filmstarts.de"
    // Quality selection must never weaken the German-language requirement.
    val german=if(movie) Regex("""\bDF\b""").containsMatchIn(title) && !title.contains("OmU",true)
        else Regex("_de(?:[-.]|$)").containsMatchIn(URI(page).path)
    if(!german)return null
    val candidates=mutableListOf<HighlightVideoCandidate>()
    val videoId=if(movie)Regex("""/trailer/(\d+)\.html""").find(URI(page).path)?.groupValues?.get(1)else null
    fun add(url:String,height:Int?=null) {
        if(!approvedHighlightVideo(url))return
        // Ignore related videos and advertising objects on the same page.
        if(videoId!=null && !URI(url).path.substringAfterLast('/').startsWith("${videoId}_"))return
        candidates+=HighlightVideoCandidate(url,height)
    }
    fun primitive(e:JsonElement?)=(e as? JsonPrimitive)?.contentOrNull
    fun collect(e:JsonElement?,hint:Int?=null) {
        when(e) {
            is JsonArray->e.forEach{collect(it,hint)}
            is JsonObject->{
                val width=primitive(e["width"])?.toIntOrNull()
                val pixelsHigh=primitive(e["height"])?.let{it.toIntOrNull() ?: highlightVideoHeight(it)}?.takeIf{it>0}
                val explicit=(if(width==1920 && (pixelsHigh ?: 1080)<=1080)1080 else if(width==1280 && (pixelsHigh ?: 720)<=720)720 else pixelsHigh)
                    ?: listOf("quality","label","format","name").firstNotNullOfOrNull{primitive(e[it])?.let(::highlightVideoHeight)}
                    ?: if(width==1920)1080 else if(width==1280)720 else hint
                listOf("contentUrl","src","url","file").forEach{key->primitive(e[key])?.let{add(it,explicit)}}
                listOf("encoding","sources","formats","video").forEach{key->collect(e[key],explicit)}
                e.forEach{(key,value)->
                    val namedHeight=highlightVideoHeight(key)
                    if(namedHeight!=null)collect(value,namedHeight)
                    else primitive(value)?.takeIf(::approvedHighlightVideo)?.let{add(it,explicit)}
                }
            }
            is JsonPrimitive->primitive(e)?.let{add(it,hint)}
            else->Unit
        }
    }
    fun videoObjects(e:JsonElement) {
        when(e) {
            is JsonArray->e.forEach(::videoObjects)
            is JsonObject->{
                if(primitive(e["@type"])=="VideoObject") {
                    val embed=primitive(e["embedUrl"])
                    val name=primitive(e["name"])
                    if(videoId==null || embed?.contains("/$videoId.html")==true || name==title)collect(e)
                }
                e["@graph"]?.let(::videoObjects)
            }
            else->Unit
        }
    }
    doc.select("script[type=application/ld+json]").forEach{script->runCatching{videoObjects(Json.parseToJsonElement(script.data()))}}
    doc.select("[data-model]").forEach{node->runCatching{
        val model=Json.parseToJsonElement(node.attr("data-model")) as? JsonObject ?: return@runCatching
        (model["videos"] as? JsonArray)?.forEach { entry ->
            val video=entry as? JsonObject ?: return@forEach
            if(videoId!=null && primitive(video["id"])==videoId)collect(video["sources"])
        }
    }}
    doc.select("video source[src]").forEach{node->
        val height=listOf("height","data-height","size","res","label","data-quality").firstNotNullOfOrNull{key->
            node.attr(key).toIntOrNull() ?: highlightVideoHeight(node.attr(key))
        }
        add(node.absUrl("src"),height)
    }
    // Compatibility with existing pages whose VideoObject is not valid JSON.
    Regex("\"contentUrl\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")").findAll(html).forEach {
        runCatching{Json.decodeFromString<String>(it.groupValues[1])}.getOrNull()?.let{url->add(url)}
    }
    return selectHighlightVideo(title,candidates)
}

internal class CinemaBayHighlightsRepository(context:Context) {
    private val directory=File(context.applicationContext.cacheDir,"cinemabay-highlights")
    private val permits=Semaphore(3)
    private val pageLocks=java.util.concurrent.ConcurrentHashMap<String,Mutex>()
    private suspend fun page(url:String,force:Boolean=false):Pair<String,Boolean> = withContext(Dispatchers.IO) {pageLocks.computeIfAbsent(url){Mutex()}.withLock {permits.withPermit {
        require(approvedHighlightPage(url))
        val name=MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)}
        val file=File(directory,name)
        val age=System.currentTimeMillis()-file.lastModified()
        if(!force && file.isFile && file.length()<=3_000_000 && age in 0..6*3600*1000L)return@withPermit file.readText(Charsets.UTF_8) to false
        try {
            val c=URL(url).openConnection() as HttpsURLConnection
            val html=try {
                c.instanceFollowRedirects=false;c.connectTimeout=8000;c.readTimeout=10000
                c.setRequestProperty("User-Agent","Mozilla/5.0 CinemaBay-TV/1.0")
                if(c.responseCode!=200)throw HighlightHttpException(c.responseCode)
                c.inputStream.use {input->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                        if(out.size()+n>3_000_000)throw IOException("Vorschau zu groß");out.write(buffer,0,n)}
                    out.toString("UTF-8")
                }
            }finally{c.disconnect()}
            directory.mkdirs();file.writeText(html,Charsets.UTF_8);html to false
        }catch(cancel:CancellationException){throw cancel}catch(e:Exception){
            if(file.isFile && file.length()<=3_000_000 && age in 0..36*3600*1000L)file.readText(Charsets.UTF_8) to true else throw e
        }
    }}}
    suspend fun load(month:YearMonth,force:Boolean=false):HighlightsResult=withContext(Dispatchers.IO) {coroutineScope {
        val today=LocalDate.now(ZoneId.of("Europe/Berlin"))
        val start=maxOf(month.atDay(1),today);val end=month.atEndOfMonth()
        val urls=mutableListOf<String>()
        var thursday=start.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.THURSDAY))
        while(thursday<=end){urls+="$MOVIES/filme-vorschau/de/week-$thursday/";thursday=thursday.plusWeeks(1)}
        val notices=java.util.Collections.synchronizedList(mutableListOf<String>())
        val movies=urls.map{url->async {
            try {val (html,stale)=page(url,force);if(stale)notices+="Filmvorschau aus Zwischenspeicher";parseHighlightMovies(html)}
            catch(cancel:CancellationException){throw cancel}catch(_:Exception){notices+="Ein Teil der Filmvorschau ist gerade nicht verfügbar";emptyList()}
        }}
        val series=async {
            try {val(html,stale)=page("$SERIES/docs/serienplaner.html",force);if(stale)notices+="Serienvorschau aus Zwischenspeicher";parseHighlightSeries(html,today)}
            catch(cancel:CancellationException){throw cancel}catch(_:Exception){notices+="Serienvorschau ist gerade nicht verfügbar";emptyList()}
        }
        val items=(movies.awaitAll().flatten()+series.await()).filter{it.date>=start && it.date<=end}
            .groupBy{Triple(it.page,it.date,it.season)}.values.map{group->group.first().copy(provider=group.map{it.provider}.distinct().joinToString(" / "))}
            .distinctBy{it.id}.sortedWith(compareBy<CinemaBayHighlight>{it.date}.thenBy{it.title})
        HighlightsResult(items,notices.distinct())
    }}
    suspend fun details(item:CinemaBayHighlight,force:Boolean=false):HighlightMetadata = withContext(Dispatchers.IO) {
        parseHighlightMetadata(page(item.page,force).first,item.page,item.kind)
    }
    suspend fun trailer(item:CinemaBayHighlight):HighlightTrailer = withContext(Dispatchers.IO) {
        resolveHighlightTrailer(item) { url -> page(url).first }
    }
}

/** Resolve metadata only. The video itself is not loaded or played during a probe. */
internal suspend fun resolveHighlightTrailer(
    item:CinemaBayHighlight,
    read:suspend (String)->String,
):HighlightTrailer {
    val html=try { read(item.page) } catch(e:HighlightHttpException) {
        if(e.status==404 || e.status==410)throw HighlightTrailerUnavailableException()
        throw e
    }
    var temporaryFailure=false
    for(url in highlightTrailerPages(html,item)) {
        try { parseHighlightTrailer(read(url),url)?.let{return it} }
        catch(cancel:CancellationException){throw cancel}
        catch(e:HighlightHttpException){if(e.status!=404 && e.status!=410)temporaryFailure=true}
        catch(_:Exception){temporaryFailure=true}
    }
    if(temporaryFailure)throw IOException("Trailerprüfung derzeit nicht möglich")
    throw HighlightTrailerUnavailableException()
}
