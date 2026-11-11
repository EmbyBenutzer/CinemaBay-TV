package org.siloserver.silo.tv.search

import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.siloserver.silo.network.SiloJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Optional query interpretation only. It can never inject titles or grant library access. */
fun interface CinemaBayAiQueryInterpreter {
    suspend fun interpret(query: String): CinemaBaySearchIntent?
}

/**
 * Opt-in adapter for a user-supplied HTTPS service. Not registered/enabled by default.
 * Request: {"version":1,"language":"de","query":"..."}; response: CinemaBaySearchIntent.
 * Uses a dedicated connection, never the authenticated Silo HTTP client.
 */
class CinemaBayHttpsQueryInterpreter(endpoint: URL, private val apiKey: () -> String? = { null }) : CinemaBayAiQueryInterpreter {
    private val protocolJson = kotlinx.serialization.json.Json(SiloJson) { ignoreUnknownKeys = false }
    private val endpoint = endpoint.also { require(it.protocol == "https" && it.userInfo == null && it.ref == null) }
    override suspend fun interpret(query: String): CinemaBaySearchIntent? = withContext(Dispatchers.IO) {
        val connection = endpoint.openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2000; connection.readTimeout = 2000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            apiKey()?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            val body = buildJsonObject { put("version", 1); put("language", "de"); put("query", query.take(240)) }
            connection.outputStream.use { it.write(SiloJson.encodeToString(body).toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return@withContext null
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                while (output.size() <= 16_384) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 16_385-output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > 16_384) return@withContext null
            val result = protocolJson.decodeFromString<CinemaBaySearchIntent>(bytes.toString(Charsets.UTF_8))
            result.takeIf { it.text.length <= 240 && it.mediaType in setOf(null, "movie", "series") &&
                it.filters.yearMin?.let { y -> y in 1850..2100 } != false &&
                it.filters.yearMax?.let { y -> y in 1850..2100 } != false &&
                it.filters.minRating?.let { r -> r.isFinite() && r in 0.0..10.0 } != false &&
                it.filters.maxMinutes?.let { m -> m in 1..1440 } != false &&
                it.filters.genres.size <= 10 && it.filters.excludedGenres.size <= 10 &&
                it.filters.genreGroups.size <= 5 && it.filters.genreGroups.all { g -> g.genres.size <= 10 && g.genres.all { v -> v.length <= 64 } } &&
                it.filters.sort in setOf("relevance", "year", "rating", "title") &&
                (it.filters.originalLanguage == null || it.filters.originalLanguage.matches(Regex("[a-z]{2}"))) &&
                (it.filters.audioLanguage == null || it.filters.audioLanguage.matches(Regex("[a-z]{2}"))) &&
                (it.person?.length ?: 0) <= 100 && (it.similarTo?.length ?: 0) <= 160 }
        } finally { connection.disconnect() }
    }
}
