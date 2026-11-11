package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.compose.koinInject
import org.siloserver.silo.model.catalog.SeasonsResponse
import org.siloserver.silo.network.AccessChangeSignals
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.tv.R

private val episodeBadgeRequests = Semaphore(2)

internal data class CinemaBayEpisodeCounts(val total: Int, val watched: Int?)

/** Episode posters may use series artwork, but their contentId is not a series ID. */
internal fun cinemaBaySeriesBadgeId(type: String, contentId: String, seriesId: String?): String? = when {
    type.equals("series", ignoreCase = true) -> contentId
    type.equals("episode", ignoreCase = true) -> seriesId
    else -> null
}?.takeIf { it.isNotBlank() }

internal fun SeasonsResponse.cinemaBayEpisodeCounts(): CinemaBayEpisodeCounts? {
    val available = seasons.filter { it.episodeCount > 0 }.distinctBy { it.contentId }
    val total = available.sumOf { it.episodeCount.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    if (total == 0) return null
    val watched = if (available.all { it.userData != null }) available.sumOf {
        val state = requireNotNull(it.userData)
        (if (state.played) it.episodeCount else state.watchedCount.coerceIn(0, it.episodeCount)).toLong()
    }.coerceAtMost(total.toLong()).toInt() else null
    return CinemaBayEpisodeCounts(total, watched)
}

/** Only composed series cards request data; the existing repository owns scoped caching. */
@Composable
internal fun CinemaBaySeriesEpisodeBadge(seriesId: String, played: Boolean?, modifier: Modifier = Modifier) {
    val catalog: CatalogRepository = koinInject()
    val identities: IdentityTransitionBarrier = koinInject()
    val access: AccessChangeSignals = koinInject()
    val generation by identities.generation.collectAsState()
    val revision by access.revision.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    var counts by remember(seriesId, generation, revision, played) { mutableStateOf<CinemaBayEpisodeCounts?>(null) }
    LaunchedEffect(seriesId, generation, revision, played, lifecycleState) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        fun current() = generation == identities.generation.value && revision == access.revision.value
        catalog.getCachedSeasons(seriesId)?.let { if (current()) counts = it.cinemaBayEpisodeCounts() }
        delay(300) // Do not fetch every card passed while scrolling quickly.
        episodeBadgeRequests.withPermit {
            if (!current()) return@withPermit
            val result = catalog.getSeasons(seriesId, fresh = true)
            if (current() && result is ApiResult.Success) counts = result.data.cinemaBayEpisodeCounts()
        }
    }
    val value = counts ?: return
    Column(modifier.background(Color.Black.copy(alpha = .82f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.cinemabay_series_episode_total, value.total), color = Color.White, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        value.watched?.let {
            Text(stringResource(R.string.cinemabay_series_episode_watched, it), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
        }
    }
}
