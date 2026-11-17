package org.siloserver.silo.tv.ui.components

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.tv.highlights.parseHighlightTrailer
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class CinemaBayFilmstartsMatch(val id: String, val series: Boolean, val videoId: String?) {
    fun videoPage(video: String) = if (series) "https://www.filmstarts.de/serien/$id/videos/$video/"
        else "https://www.filmstarts.de/kritiken/$id/trailer/$video.html"
    val page get() = "https://www.filmstarts.de/${if (series) "serien" else "kritiken"}/$id.html"
}
private fun trailerTitle(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").replace("ß", "ss").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** Autocomplete also returns paid promotions, people, remakes and spin-offs. Never use those by position. */
internal fun cinemaBayFilmstartsMatch(json: String, detail: ItemDetail): CinemaBayFilmstartsMatch? {
    val root = Json.parseToJsonElement(json) as? JsonObject ?: return null
    fun text(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
    val series = detail.type.equals("series", true)
    if (!series && !detail.type.equals("movie", true)) return null
    val titles = listOfNotNull(detail.title, detail.originalTitle).map(::trailerTitle).filter { it.isNotBlank() }.toSet()
    val year = (if (series) detail.firstAirDate else detail.releaseDate)?.take(4)?.toIntOrNull()
        ?: detail.year.takeIf { it > 0 }
    val matches = (root["results"] as? JsonArray).orEmpty().mapNotNull { element ->
        val result = element as? JsonObject ?: return@mapNotNull null
        if (text(result, "sponsored") == "true" || text(result, "entity_type") != if (series) "series" else "movie") return@mapNotNull null
        if (listOfNotNull(text(result, "label"), text(result, "original_label")).none { trailerTitle(it) in titles }) return@mapNotNull null
        val data = result["data"] as? JsonObject ?: return@mapNotNull null
        val foundYear = text(data, "year")?.toIntOrNull()
        if (year != null && (foundYear == null || kotlin.math.abs(foundYear - year) > 1)) return@mapNotNull null
        val id = text(result, "entity_id")?.takeIf { it.matches(Regex("[0-9]{1,12}")) } ?: return@mapNotNull null
        CinemaBayFilmstartsMatch(id, series, text(data, "default_video")?.takeIf { it.matches(Regex("[0-9]{1,12}")) }) to (foundYear == year)
    }.distinctBy { it.first.id }
    val exactYear = matches.filter { it.second }
    return (if (exactYear.isNotEmpty()) exactYear else matches).singleOrNull()?.first
}

internal fun cinemaBayFilmstartsVideoPages(html: String, match: CinemaBayFilmstartsMatch): List<String> {
    val doc = Jsoup.parse(html, match.page)
    val links = doc.select("a[href]").map { it.absUrl("href") }.toMutableList()
    doc.select("span[class]").forEach { span -> span.classNames().filter { it.startsWith("ACr") }.forEach { cls ->
        runCatching { String(java.util.Base64.getDecoder().decode(cls.removePrefix("ACr")), Charsets.UTF_8) }
            .getOrNull()?.let { runCatching { URI(match.page).resolve(it).toString() }.getOrNull()?.let(links::add) }
    } }
    val pattern = if (match.series) Regex("/serien/${match.id}/videos/[0-9]{1,12}/")
        else Regex("/kritiken/${match.id}/trailer/[0-9]{1,12}\\.html")
    return links.filter { runCatching { val uri = URI(it); uri.scheme == "https" && uri.host == "www.filmstarts.de" && pattern.matches(uri.path) && uri.query == null }.getOrDefault(false) }.distinct().take(6)
}

/** Public Filmstarts metadata and German DF clips only; never an advertising player or an OV clip without captions. */
internal class CinemaBayFilmstartsSource(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "cinemabay-filmstarts-v1")
    suspend fun trailer(detail: ItemDetail, rejected: Set<String>, originalWithSubtitles: Boolean = false): CinemaBayTrailerChoice? = withContext(Dispatchers.IO) {
        for (title in listOfNotNull(detail.title, detail.originalTitle).distinct().take(2)) {
            val search = "https://www.filmstarts.de/_/autocomplete/" + URLEncoder.encode(title, "UTF-8").replace("+", "%20")
            val match = cinemaBayFilmstartsMatch(page(search), detail) ?: continue
            suspend fun video(url: String): CinemaBayTrailerChoice? {
                val key = "filmstarts:" + URI(url).path
                if (key in rejected) return null
                val trailer = parseHighlightTrailer(page(url), url, allowGermanSubtitledOriginal = originalWithSubtitles) ?: return null
                val subtitled = Regex("""\bOmU\b""", RegexOption.IGNORE_CASE).containsMatchIn(trailer.title)
                if (subtitled != originalWithSubtitles) return null
                return CinemaBayTrailerChoice(key, subtitled, "Filmstarts", trailer.url, trailer.fallbackUrl, subtitled)
            }
            match.videoId?.let { video(match.videoPage(it)) }?.let { return@withContext it }
            for (url in cinemaBayFilmstartsVideoPages(page(match.page), match)) {
                if (url == match.videoId?.let(match::videoPage)) continue
                video(url)?.let { return@withContext it }
            }
        }
        null
    }
    private suspend fun page(url: String): String {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "www.filmstarts.de" && uri.userInfo == null)
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return locks.getOrPut(key) { Mutex() }.withLock {
            directory.mkdirs()
            val cache = File(directory, key)
            if (cache.isFile && System.currentTimeMillis() - cache.lastModified() in 0..86_400_000L) return@withLock cache.readText()
            val result = permits.withPermit {
                client.newCall(Request.Builder().url(url).header("User-Agent", "CinemaBay-TV/0.1.12").build()).execute().use { response ->
                    if (response.code in listOf(404, 410)) return@use if (uri.path.startsWith("/_/autocomplete/")) "{\"results\":[]}" else ""
                    if (!response.isSuccessful) throw IOException("Trailerquelle nicht erreichbar")
                    val body = response.body ?: throw IOException("Leere Trailerantwort")
                    body.byteStream().use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val read = input.read(buffer); if (read < 0) break
                            if (output.size() + read > 2_000_000) throw IOException("Trailerantwort zu groß")
                            output.write(buffer, 0, read)
                        }
                        output.toString("UTF-8")
                    }
                }
            }
            cache.writeText(result)
            directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(250)?.forEach { it.delete() }
            result
        }
    }
    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        private val permits = Semaphore(2)
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build()
    }
}
