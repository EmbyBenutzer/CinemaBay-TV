package org.siloserver.silo.tv.ui.components

import org.siloserver.silo.model.catalog.*
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

internal data class CinemaBayEpisodeCounts(val total: Int, val watched: Int?) {
    val remaining: Int? get() = watched?.takeIf { total > 0 && it in 0..total }?.let { total - it }
}

/** Match technical extra labels, never an ordinary episode merely mentioning a trailer. */
internal fun cinemaBayExtraEpisodeTitle(title: String?): Boolean = title?.trim()?.let {
    Regex("""^(?:(?:official|offizieller|deutscher|german|original)\s+)?(?:trailer|teaser)(?:\s+(?:\d+|deutsch|german|official|ov|omu|hd|4k|1080p|720p|final|finaler))*$""", RegexOption.IGNORE_CASE).matches(it) ||
        Regex("""^(?:extras?|specials?|sonderfolgen?)(?:\s*[-:#]?\s*\d+)?$""", RegexOption.IGNORE_CASE).matches(it)
} ?: false

internal fun SeasonsResponse.cinemaBayRegularSeasons(): List<Season> = seasons
    .filter { it.seasonNumber > 0 && !it.isSpecials && it.episodeCount > 0 && !cinemaBayExtraEpisodeTitle(it.title) }
    .groupBy { it.seasonNumber }.values.map { versions -> versions.maxBy { it.episodeCount } }
    .sortedBy { it.seasonNumber }

/** A full episode response is required; files/encodes of one SxxExx are one episode. */
internal fun Season.cinemaBayEpisodeCounts(episodes: List<EpisodeListItem>): CinemaBayEpisodeCounts? {
    if (seasonNumber <= 0 || isSpecials || episodeCount <= 0 || cinemaBayExtraEpisodeTitle(title)) return null
    val raw = episodes.filter { it.seasonNumber == seasonNumber }.distinctBy { it.contentId }
    if (raw.size < episodeCount) return null // A partial list must never produce a completion check.
    val hasFiles = raw.any { it.files.isNotEmpty() }
    val eligible = raw.filter { it.episodeNumber > 0 && !cinemaBayExtraEpisodeTitle(it.title) && (!hasFiles || it.files.isNotEmpty()) }
    val groups = eligible.groupBy { it.seasonNumber to it.episodeNumber }.values
    if (groups.isEmpty()) return CinemaBayEpisodeCounts(0, 0)
    val played = groups.map { versions -> when {
        versions.any { it.userData?.played == true } -> true
        versions.all { it.userData != null } -> false
        else -> null
    } }
    val watched = when {
        played.all { it != null } -> played.count { it == true }
        userData?.played == true && played.none { it == false } -> groups.size
        userData?.watchedCount == 0 && played.none { it == true } -> 0
        raw.size == eligible.size && groups.size == episodeCount && userData != null ->
            userData!!.watchedCount.takeIf { it in played.count { value -> value == true }..(played.count { value -> value == true } + played.count { value -> value == null }) }
        else -> null
    }
    return CinemaBayEpisodeCounts(groups.size, watched)
}

internal fun cinemaBaySumEpisodeCounts(values: List<CinemaBayEpisodeCounts>): CinemaBayEpisodeCounts? {
    val total = values.sumOf { it.total.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    if (total <= 0) return null
    val watched = if (values.all { it.watched != null }) values.sumOf { it.watched!!.toLong() }.coerceAtMost(total.toLong()).toInt() else null
    return CinemaBayEpisodeCounts(total, watched)
}

/** Missing roll-up state must not keep stale leaf states for the metadata-cache lifetime. */
internal fun cinemaBayCanReuseEpisodeCounts(season: Season, value: CinemaBayEpisodeCounts, excluded: Boolean): Boolean =
    season.userData != null && value.watched != null && !excluded

internal data class CinemaBayEpisodeBadgeKey(val generation: Long, val accessRevision: Long, val seriesId: String)

/** Shared, viewer-scoped metadata cache. Unchanged ordinary seasons avoid repeated episode-list reads. */
internal object CinemaBayEpisodeBadgeStore {
    private data class Signature(val total: Int, val played: Boolean?, val watched: Int?)
    private data class SeasonCount(val signature: Signature, val value: CinemaBayEpisodeCounts, val excluded: Boolean, val checkedAt: Long)
    private data class Entry(val value: CinemaBayEpisodeCounts?, val seasons: Map<Int, SeasonCount>, val checkedAt: Long)
    private val entries = ConcurrentHashMap<CinemaBayEpisodeBadgeKey, Entry>()
    private val locks = ConcurrentHashMap<CinemaBayEpisodeBadgeKey, Mutex>()
    private val requests = Semaphore(2)
    fun peek(key: CinemaBayEpisodeBadgeKey): CinemaBayEpisodeCounts? = entries[key]?.value

    suspend fun read(catalog: CatalogRepository, key: CinemaBayEpisodeBadgeKey, current: () -> Boolean): CinemaBayEpisodeCounts? =
        locks.getOrPut(key) { Mutex() }.withLock {
            if (!current()) return@withLock null
            entries.keys.filter { it.generation != key.generation || it.accessRevision != key.accessRevision }.forEach { entries.remove(it) }
            val previous = entries[key]
            if (previous != null && System.currentTimeMillis() - previous.checkedAt in 0..1_500L) return@withLock previous.value
            requests.withPermit {
                if (!current()) return@withPermit null
                val response = (catalog.getSeasons(key.seriesId, fresh = true) as? ApiResult.Success)?.data ?: return@withPermit null
                val seasons = response.cinemaBayRegularSeasons()
                val result = linkedMapOf<Int, SeasonCount>()
                for (season in seasons) {
                    if (!current()) return@withPermit null
                    val signature = Signature(season.episodeCount, season.userData?.played, season.userData?.watchedCount)
                    val old = previous?.seasons?.get(season.seasonNumber)
                    if (old != null && old.signature == signature && cinemaBayCanReuseEpisodeCounts(season, old.value, old.excluded) && System.currentTimeMillis() - old.checkedAt in 0..300_000L) {
                        result[season.seasonNumber] = old
                        continue
                    }
                    var episodes = catalog.getCachedEpisodes(key.seriesId, season.seasonNumber)?.episodes
                    fun cacheIsCurrent(items: List<EpisodeListItem>?): Boolean {
                        if (items == null || old != null || season.userData == null) return false
                        val value = season.cinemaBayEpisodeCounts(items) ?: return false
                        // Aggregate equality alone cannot validate state if excluded entries exist.
                        if (value.total != items.distinctBy { it.contentId }.size || items.any { it.userData == null }) return false
                        return items.count { it.userData?.played == true } == season.userData!!.watchedCount &&
                            value.total == season.episodeCount
                    }
                    if (!cacheIsCurrent(episodes)) episodes = (catalog.getEpisodes(key.seriesId, season.seasonNumber, fresh = true) as? ApiResult.Success)?.data?.episodes
                    val items = episodes ?: return@withPermit null
                    val counts = season.cinemaBayEpisodeCounts(items) ?: return@withPermit null
                    result[season.seasonNumber] = SeasonCount(signature, counts,
                        excluded = counts.total != items.size, checkedAt = System.currentTimeMillis())
                }
                if (!current()) return@withPermit null
                val value = cinemaBaySumEpisodeCounts(result.values.map { it.value })
                entries[key] = Entry(value, result, System.currentTimeMillis())
                entries.entries.sortedByDescending { it.value.checkedAt }.drop(64).forEach { entries.remove(it.key); locks.remove(it.key) }
                value
            }
        }
}
