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
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.ItemVideo
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class CinemaBayTrailerChoice(val videoId: String, val original: Boolean, val source: String, val directUrl: String? = null, val fallbackUrl: String? = null, val germanCaptionsEmbedded: Boolean = false)
internal fun cinemaBayTrailerLanguage(value: String?): String? = value?.trim()?.lowercase()?.replace('_', '-')?.substringBefore('-')?.takeIf { it.isNotBlank() }
private fun validTrailer(video: ItemVideo) = video.kind.equals("trailer", true) && video.site.equals("youtube", true) && video.siteKey.matches(Regex("[A-Za-z0-9_-]{11}"))

/** The provider response must match BOTH the requested identity and media type. Never scan recommendations. */
internal fun cinemaBayKinoCheckTrailers(json: String, tmdbId: String?, imdbId: String?, series: Boolean, language: String): List<String> {
    val root = Json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
    fun field(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
    if (field(root, "type") != if (series) "show" else "movie") return emptyList()
    if (tmdbId != null && field(root, "tmdb_id") != tmdbId) return emptyList()
    if (tmdbId == null && (imdbId == null || field(root, "imdb_id") != imdbId)) return emptyList()
    if (cinemaBayTrailerLanguage(field(root, "language")) != language) return emptyList()
    val videos = listOfNotNull(root["trailer"] as? JsonObject) + (root["videos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    return videos.filter { video ->
        cinemaBayTrailerLanguage(field(video, "language")) == language &&
            (video["categories"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull.equals("Trailer", true) }
    }.mapNotNull { field(it, "youtube_video_id")?.takeIf { id -> id.matches(Regex("[A-Za-z0-9_-]{11}")) } }.distinct()
}

/** Prefer German across sources; a provider outage must not suppress an available original trailer. */
internal suspend fun resolveCinemaBayCardTrailer(
    detail: ItemDetail,
    rejected: Set<String> = emptySet(),
    germanAlternative: suspend () -> CinemaBayTrailerChoice? = { null },
    subtitledOriginal: suspend () -> CinemaBayTrailerChoice? = { null },
    extra: suspend (String) -> List<String>,
): CinemaBayTrailerChoice? {
    val videos = detail.videos.orEmpty().filter(::validTrailer).sortedByDescending { it.isOfficial }
    videos.firstOrNull { cinemaBayTrailerLanguage(it.language) == "de" && it.siteKey !in rejected }?.let {
        return CinemaBayTrailerChoice(it.siteKey, false, "Mediathek")
    }
    var sourceError: Exception? = null
    try { extra("de").firstOrNull { it !in rejected }?.let { return CinemaBayTrailerChoice(it, false, "KinoCheck") } }
    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (error: Exception) { sourceError = error }
    try { germanAlternative()?.takeIf { !it.original && it.videoId !in rejected }?.let { return it } }
    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (error: Exception) { sourceError = error }

    try { subtitledOriginal()?.takeIf { it.original && it.germanCaptionsEmbedded && it.videoId !in rejected }?.let { return it } }
    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (error: Exception) { sourceError = error }
    val language = cinemaBayTrailerLanguage(detail.originalLanguage) ?: run { if (sourceError != null) throw sourceError; return null }
    if (language == "de") { if (sourceError != null) throw sourceError; return null }
    videos.firstOrNull { cinemaBayTrailerLanguage(it.language) == language && it.siteKey !in rejected }?.let {
        return CinemaBayTrailerChoice(it.siteKey, true, "Mediathek")
    }
    if (language == "en") {
        try { extra("en").firstOrNull { it !in rejected }?.let { return CinemaBayTrailerChoice(it, true, "KinoCheck") } }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (error: Exception) { sourceError = error }
    }
    if (sourceError != null) throw sourceError
    return null
}

internal class CinemaBayKinoCheckSource(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "cinemabay-trailer-sources-v1")
    suspend fun trailers(detail: ItemDetail, language: String): List<String> = withContext(Dispatchers.IO) {
        if (language !in setOf("de", "en") || detail.type.lowercase() !in setOf("movie", "series")) return@withContext emptyList()
        val tmdb = detail.tmdbId?.takeIf { it.matches(Regex("[0-9]{1,12}")) }
        val imdb = detail.imdbId?.takeIf { it.matches(Regex("tt[0-9]{5,12}")) }
        if (tmdb == null && imdb == null) return@withContext emptyList()
        val series = detail.type.equals("series", true)
        val url = "https://api.kinocheck.de/${if (series) "shows" else "movies"}?${if (tmdb != null) "tmdb_id=$tmdb" else "imdb_id=$imdb"}&language=$language&categories=Trailer"
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        locks.getOrPut(key) { Mutex() }.withLock {
            directory.mkdirs()
            val cache = File(directory, "$key.json")
            val text = if (cache.isFile && System.currentTimeMillis() - cache.lastModified() in 0..86_400_000L) cache.readText() else {
                permits.withPermit {
                    client.newCall(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "CinemaBay-TV/0.1.11").build()).execute().use { response ->
                        when (response.code) {
                            404, 410 -> "{}"
                            200 -> {
                                if ((response.body?.contentLength() ?: 0) > 2_000_000) throw IOException("Trailerantwort zu groß")
                                val body = response.body ?: throw IOException("Leere Trailerantwort")
                                body.byteStream().use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        if (output.size() + read > 2_000_000) throw IOException("Trailerantwort zu groß")
                                        output.write(buffer, 0, read)
                                    }
                                    output.toString("UTF-8")
                                }
                            }
                            else -> throw IOException("Zusätzliche Trailerquelle nicht erreichbar")
                        }
                    }
                }.also { result ->
                    Json.parseToJsonElement(result)
                    cache.writeText(result)
                    // A small bounded metadata cache, never media files.
                    directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(250)?.forEach { it.delete() }
                }
            }
            cinemaBayKinoCheckTrailers(text, tmdb, imdb, series, language)
        }
    }
    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        private val permits = Semaphore(2)
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build()
    }
}
