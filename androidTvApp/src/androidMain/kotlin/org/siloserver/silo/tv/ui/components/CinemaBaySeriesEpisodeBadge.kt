package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.koin.compose.koinInject
import org.siloserver.silo.network.AccessChangeSignals
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.HomeRealtimeCoordinator
import org.siloserver.silo.tv.R

internal fun cinemaBaySeriesBadgeId(type: String, contentId: String, seriesId: String?): String? = when {
    type.equals("series", ignoreCase = true) -> contentId
    type.equals("episode", ignoreCase = true) -> seriesId
    else -> null
}?.takeIf { it.isNotBlank() }

/** Small Emby-style count: remaining regular episodes, or a check only for a known complete series. */
@Composable
internal fun CinemaBaySeriesEpisodeBadge(seriesId: String, played: Boolean?, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val catalog: CatalogRepository = koinInject()
    val identities: IdentityTransitionBarrier = koinInject()
    val access: AccessChangeSignals = koinInject()
    val realtime: HomeRealtimeCoordinator = koinInject()
    val generation by identities.generation.collectAsState()
    val revision by access.revision.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val key = remember(seriesId, generation, revision) { CinemaBayEpisodeBadgeKey(generation, revision, seriesId) }
    var refresh by remember(key) { mutableIntStateOf(0) }
    var counts by remember(key, played) { mutableStateOf(CinemaBayEpisodeBadgeStore.peek(key)) }
    LaunchedEffect(key, lifecycleState) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        launch { realtime.refreshSignals.collect { refresh++ } }
        while (true) { delay(60_000); refresh++ }
    }
    LaunchedEffect(key, played, lifecycleState, refresh) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        delay(300)
        fun current() = key.generation == identities.generation.value && key.accessRevision == access.revision.value
        val value = CinemaBayEpisodeBadgeStore.read(catalog, key, ::current)
        if (current()) counts = value
    }
    val remaining = counts?.remaining ?: return
    val description = if (remaining == 0) stringResource(R.string.cinemabay_series_all_watched)
        else stringResource(R.string.cinemabay_series_unwatched, remaining)
    Box(modifier.height(size).widthIn(min = size)
        .background(Color.White, CircleShape)
        .clearAndSetSemantics { contentDescription = description }
        .padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        if (remaining == 0) Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(size * .55f))
        else Text(remaining.toString(), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
