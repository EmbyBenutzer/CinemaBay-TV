package org.siloserver.silo.tv.data.preferences

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.koin.compose.koinInject
import org.siloserver.silo.network.IdentityTransitionBarrier

/** Home and Settings share one bounded reader; old profile cards are hidden synchronously on identity change. */
@Composable
internal fun rememberCinemaBayLibraryHome(refreshSignal: Any?): CinemaBayLibraryHomeState {
    val repository: CinemaBayLibraryHomeRepository = koinInject()
    val preferences: TvHomeSectionPreferences = koinInject()
    val transitions: IdentityTransitionBarrier = koinInject()
    val generation by transitions.generation.collectAsState()
    val settings by preferences.state.collectAsState()
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(generation, settings.layout.enabledOptionalSectionIds, refreshSignal) {
        preferences.refresh()
        repository.refresh(preferences.state.value.layout.enabledOptionalSectionIds.filter { it.startsWith("cinemabay-library:") }.toSet(), force = true)
    }
    LifecycleResumeEffect(repository, generation) {
        val job = scope.launch {
            preferences.refresh()
            repository.refresh(preferences.state.value.layout.enabledOptionalSectionIds.filter { it.startsWith("cinemabay-library:") }.toSet(), force = true)
        }
        onPauseOrDispose { job.cancel() }
    }
    return if (state.identityGeneration == generation && state.authority == settings.authority) state
        else CinemaBayLibraryHomeState(loading = true)
}
