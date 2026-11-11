package org.siloserver.silo.tv.ui.screens.search

import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.common.i18n.errorText
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.repository.BOX_SET_SEARCH_GRACE_MILLIS
import org.siloserver.silo.model.catalog.BOX_SET_TYPE
import org.siloserver.silo.model.catalog.toSearchCard
import org.siloserver.silo.tv.search.CinemaBaySearchRepository
import org.siloserver.silo.tv.search.CinemaBaySearchFilters
import org.siloserver.silo.tv.search.CinemaBaySearchIntent
import org.siloserver.silo.tv.search.CinemaBayGermanQueryParser
import org.siloserver.silo.tv.search.CinemaBayIndexState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.navigation.isAudiobookLikeLibraryType
import org.siloserver.silo.model.navigation.tvMediaModeCapabilities
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.searchPeopleForQuery
import org.siloserver.silo.tv.ui.util.visibleOnTv
import org.siloserver.silo.tv.data.preferences.TvLibraryScopeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.annotation.StringRes
import org.siloserver.silo.common.R as CommonR

/** Media type filter in the search header. */
enum class TvSearchMediaType(@StringRes val label: Int, val wire: String?) {
    All(CommonR.string.search_filter_all, null),
    Movies(CommonR.string.search_filter_movies, "movie"),
    Series(CommonR.string.search_filter_series, "series"),
    Audiobooks(CommonR.string.search_filter_audiobooks, "audiobook"),
}

/**
 * People-search scopes for each filter. All stays within what TV shows: video,
 * plus audiobooks only when that tab is enabled. Book and manga credits are
 * never searched, because TV does not show those media types.
 */
/** Box sets are movies; the Series and Audiobooks filters leave them out. */
internal val TvSearchMediaType.includesBoxSets: Boolean
    get() = this == TvSearchMediaType.All || this == TvSearchMediaType.Movies

internal fun TvSearchMediaType.peopleMediaScopes(showAudiobooks: Boolean): List<String?> = when (this) {
    TvSearchMediaType.All -> if (showAudiobooks) listOf("video", "audiobook") else listOf("video")
    TvSearchMediaType.Movies -> listOf("movie")
    TvSearchMediaType.Series -> listOf("series")
    TvSearchMediaType.Audiobooks -> listOf("audiobook")
}

@OptIn(FlowPreview::class)
class TvSearchViewModel(
    private val catalogRepository: CatalogRepository,
    private val personalDataRepository: PersonalDataRepository,
    private val libraryScopeStore: TvLibraryScopeStore,
    private val aiSearch: CinemaBaySearchRepository? = null,
) : ViewModel() {
    /** Access changes this ViewModel has applied, kept while its screen is away. */
    val accessChanges = org.siloserver.silo.network.AccessChangeCursor()

    data class UiState(
        val query: String = "",
        val aiEnabled: Boolean = false,
        val aiFilters: CinemaBaySearchFilters = CinemaBaySearchFilters(),
        val aiIntent: CinemaBaySearchIntent? = null,
        val aiIndex: CinemaBayIndexState = CinemaBayIndexState(),
        val aiNotice: String? = null,
        val mediaType: TvSearchMediaType = TvSearchMediaType.All,
        /** Media-type chips to show, derived from the user's libraries. */
        val availableMediaTypes: List<TvSearchMediaType> =
            TvSearchMediaType.entries.filterNot { it == TvSearchMediaType.Audiobooks },
        val items: List<BrowseItem> = emptyList(),
        /** Cast and crew matching the query, shown above the title grid. */
        val people: List<Person> = emptyList(),
        /** True until the current query's people lookup answers. */
        val isLoadingPeople: Boolean = false,
        val total: Int = 0,
        val totalExact: Boolean = false,
        val searchDiagnostics: org.siloserver.silo.network.apiv2.CatalogSearchDiagnosticsV2? = null,
        val hasMore: Boolean = false,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val error: UiText? = null,
        val rawResultCount: Int = 0,
        val showAudiobooks: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState(aiEnabled = aiSearch != null))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Declared before init, which starts the first load and stores its job here.
    private var mediaTypesJob: Job? = null

    init { loadAvailableMediaTypes() }

    /**
     * Derive the visible media-type chips from the user's libraries (parity with
     * the phone search): Movies/Series need a video library, Audiobooks need an
     * audio library. "All" is always present. If the active filter becomes
     * unavailable it falls back to All.
     */
    private fun loadAvailableMediaTypes() {
        // A reload after an access change replaces an unfinished one, so the
        // older library list cannot land after it.
        mediaTypesJob?.cancel()
        mediaTypesJob = viewModelScope.launch {
            val libraries = when (val result = personalDataRepository.listUserLibraries()) {
                is ApiResult.Success -> result.data
                else -> return@launch
            }
            val caps = libraries.tvMediaModeCapabilities()
            val showAudiobooks = libraryScopeStore.getShowAudiobooksTab()
            ensureActive()
            // Servers without box sets report no library with them on, so
            // search never asks those servers for any.
            val hadBoxSets = boxSetsAvailable
            boxSetsAvailable = libraries.any { it.boxSetsEnabled }
            // The "Audiobooks" chip sends type=audiobook, so gate it on an
            // actual audiobook-like library — hasAudio also covers music, which
            // wouldn't match the audiobook filter.
            val hasAudiobooks = libraries.any { isAudiobookLikeLibraryType(it.type) }
            val types = buildList {
                add(TvSearchMediaType.All)
                if (caps.hasVideo) {
                    add(TvSearchMediaType.Movies)
                    add(TvSearchMediaType.Series)
                }
                if (showAudiobooks && hasAudiobooks) add(TvSearchMediaType.Audiobooks)
            }
            val current = _uiState.value
            val nextMediaType = if (current.mediaType in types) current.mediaType else TvSearchMediaType.All
            _uiState.update {
                it.copy(
                    availableMediaTypes = types,
                    mediaType = nextMediaType,
                    showAudiobooks = showAudiobooks,
                )
            }
            // If the active filter was forced to change while a query is live,
            // re-run so results aren't left stale under the old filter.
            if (
                (
                    nextMediaType != current.mediaType || showAudiobooks != current.showAudiobooks ||
                        boxSetsAvailable != hadBoxSets
                    ) &&
                current.query.isNotBlank()
            ) {
                searchGeneration += 1
                searchJob?.cancel()
                loadMoreJob?.cancel()
                searchJob = viewModelScope.launch { runSearchInternal(reset = true) }
            }
        }
    }

    /** Some library this profile sees has box sets on. */
    private var boxSetsAvailable = false

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var peopleJob: Job? = null
    private var aiPeopleKey: String? = null
    private var continuation: CatalogContinuationV2? = null
    private val pageSize = 40
    private val debounceMs = 300L

    // Search-generation token. Every query/filter edit bumps [searchGeneration];
    // a reset search stamps the current generation onto the results it produces
    // ([resultsGeneration]). A load-more only runs — and only appends — while the
    // accumulated results still belong to the current generation, so a page
    // fetched for a query the user has already changed can never mix into the new
    // query's results (it sent the new query at the old query's offset otherwise).
    private var searchGeneration = 0
    private var resultsGeneration = 0

    fun onQueryChanged(query: String) {
        searchGeneration += 1
        if (aiSearch != null) aiPeopleKey = null
        _uiState.update { current -> current.copy(query = query,
            aiIntent = if (aiSearch != null && current.mediaType != TvSearchMediaType.Audiobooks)
                CinemaBayGermanQueryParser.parse(query).let { it.copy(filters = it.filters.and(current.aiFilters), mediaType = current.mediaType.wire ?: it.mediaType) }
                else null,
            aiNotice = null) }
        searchJob?.cancel()
        loadMoreJob?.cancel()
        if (query.isBlank() && !hasAiSelection()) {
            peopleJob?.cancel()
            _uiState.update {
                it.copy(
                    items = emptyList(),
                    people = emptyList(),
                    isLoadingPeople = false,
                    total = 0,
                    hasMore = false,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null,
                    rawResultCount = 0,
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(debounceMs)
            runSearchInternal(reset = true)
        }
    }

    fun onMediaTypeChanged(mediaType: TvSearchMediaType) {
        if (mediaType !in _uiState.value.availableMediaTypes) return
        searchGeneration += 1
        _uiState.update { it.copy(mediaType = mediaType) }
        searchJob?.cancel()
        loadMoreJob?.cancel()
        if (_uiState.value.query.isNotBlank() || hasAiSelection()) {
            searchJob = viewModelScope.launch { runSearchInternal(reset = true) }
        } else if (aiSearch != null) {
            peopleJob?.cancel()
            _uiState.update { it.copy(items=emptyList(), people=emptyList(), total=0, hasMore=false,
                isLoading=false, isLoadingMore=false, isLoadingPeople=false, rawResultCount=0, aiIntent=null, aiNotice=null) }
        }
    }

    /**
     * The server reported an access change: libraries (and so the media-type
     * chips) and the titles a query can match may differ under the new policy.
     */
    fun refreshForAccessChange() {
        if (aiSearch != null) {
            searchGeneration += 1; searchJob?.cancel(); loadMoreJob?.cancel(); peopleJob?.cancel()
            _uiState.update { it.copy(items = emptyList(), people = emptyList(), total = 0, hasMore = false, isLoading = false, isLoadingMore = false) }
            aiSearch.refresh()
        }
        loadAvailableMediaTypes()
        if (_uiState.value.query.isNotBlank() || hasAiSelection()) submitSearch()
    }

    fun submitSearch() {
        // Bump the generation (mirroring onQueryChanged) so a cancelled in-flight
        // load-more's response fails the stale-generation guard instead of
        // writing over the submitted search's results.
        searchGeneration += 1
        searchJob?.cancel()
        loadMoreJob?.cancel()
        val query = _uiState.value.query
        if (query.isBlank() && !hasAiSelection()) {
            peopleJob?.cancel()
            _uiState.update {
                it.copy(
                    items = emptyList(),
                    people = emptyList(),
                    isLoadingPeople = false,
                    total = 0,
                    hasMore = false,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null,
                    rawResultCount = 0,
                )
            }
            return
        }
        searchJob = viewModelScope.launch { runSearchInternal(reset = true) }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.error != null || state.isLoading || state.isLoadingMore || !state.hasMore || (state.query.isBlank() && !hasAiSelection())) return
        if (loadMoreJob?.isActive == true) return
        loadMoreJob = viewModelScope.launch {
            try {
                runSearchInternal(reset = false)
            } finally {
                // A load-more cancelled/superseded mid-flight (new keystroke)
                // must not leak isLoadingMore=true — that would wedge paging and
                // hide the grid's retry footer. The update is non-suspending, so
                // it still runs when this coroutine is cancelled.
                if (_uiState.value.isLoadingMore) {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    /**
     * Looks up people beside the title search, on its own job so a slow or
     * failed people lookup never holds back or fails the titles. The previous
     * query's people are cleared first so they never sit beside new titles.
     */
    private fun searchPeople(generation: Int, query: String, mediaType: TvSearchMediaType, showAudiobooks: Boolean, aiKey: String? = null) {
        peopleJob?.cancel()
        _uiState.update { it.copy(people = emptyList(), isLoadingPeople = true) }
        peopleJob = viewModelScope.launch {
            val people = catalogRepository.searchPeopleForQuery(query, mediaType.peopleMediaScopes(showAudiobooks))
            // Cancellation surfaces as an empty answer, not an exception.
            if (!isActive || (if (aiKey != null) aiKey != aiPeopleKey else generation != searchGeneration)) return@launch
            _uiState.update { it.copy(people = people, isLoadingPeople = false) }
        }
    }

    private suspend fun runSearchInternal(reset: Boolean) {
        if (aiSearch != null && _uiState.value.mediaType != TvSearchMediaType.Audiobooks) {
            runAiSearch(reset)
            return
        }
        val generation = searchGeneration
        val state = _uiState.value
        val requestedQuery = state.query
        val requestedMediaType = state.mediaType

        // A load-more whose accumulated results predate the current generation is
        // paging a stale offset for a superseded query — abort before firing.
        if (!reset && resultsGeneration != generation) {
            _uiState.update { it.copy(isLoadingMore = false) }
            return
        }

        var offset = if (reset) 0 else state.rawResultCount
        var cursor = if (reset) null else continuation
        var fetchedRawCount = 0
        _uiState.update {
            if (reset) it.copy(isLoading = true, isLoadingMore = false, error = null)
            else it.copy(isLoadingMore = true)
        }
        if (reset) searchPeople(generation, requestedQuery, requestedMediaType, state.showAudiobooks)
        // Matching box sets lead the grid, ahead of the movies themselves.
        // They land with the first page, so the grid never shifts under focus.
        val boxSetsRequest = if (reset && boxSetsAvailable && requestedMediaType.includesBoxSets) {
            viewModelScope.async { catalogRepository.searchBoxSets(requestedQuery) }
        } else {
            null
        }

        try {
        while (true) {
            val result = catalogRepository.browse(
                source = "query",
                query = requestedQuery,
                mediaType = requestedMediaType.wire,
                continuation = cursor,
                limit = pageSize,
            )

            // safeApiCall upstream swallows CancellationException into NetworkError —
            // rethrow so a cancelled coroutine can't write any state from beyond the grave.
            if (result is ApiResult.NetworkError && result.exception is CancellationException) {
                throw result.exception
            }

            // Drop a page fetched for a query/filter the user has already moved past,
            // so it can neither replace nor append onto the current generation.
            if (generation != searchGeneration) {
                if (!reset) _uiState.update { it.copy(isLoadingMore = false) }
                return
            }

            when (result) {
                is ApiResult.Success -> {
                    // The results now belong to this generation; load-more may page.
                    if (reset) resultsGeneration = generation
                    val response = result.data
                    cursor = response.continuation
                    fetchedRawCount += response.items.size
                    val visibleItems = response.items
                        .visibleOnTv()
                        .filter { _uiState.value.showAudiobooks || !isAudiobookItemType(it.type) }

                    // A raw page can contain only hidden audiobook/reading
                    // results. The grid cannot request another page when it has
                    // no rendered cards, so transparently drain hidden-only
                    // pages until something visible is found or paging ends.
                    if (visibleItems.isEmpty() && response.hasMore && response.items.isNotEmpty()) {
                        offset += response.items.size
                        continue
                    }

                    // Box sets only add to the titles, so a slow answer never holds them
                    // back for long: past the grace period this query shows none.
                    val boxSetCards = withTimeoutOrNull(BOX_SET_SEARCH_GRACE_MILLIS) { boxSetsRequest?.await() }
                        .orEmpty().map { it.toSearchCard() }
                    if (generation != searchGeneration) return
                    continuation = cursor
                    _uiState.update {
                        val accumulatedItems = if (reset) boxSetCards + visibleItems else it.items + visibleItems
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            items = accumulatedItems,
                            total = if (requestedMediaType == TvSearchMediaType.All) {
                                accumulatedItems.size
                            } else {
                                response.total + accumulatedItems.count { item -> item.type == BOX_SET_TYPE }
                            },
                            totalExact = if (requestedMediaType == TvSearchMediaType.All) !response.hasMore else response.totalExact == true,
                            searchDiagnostics = response.searchDiagnostics,
                            hasMore = response.hasMore && response.items.isNotEmpty(),
                            error = null,
                            rawResultCount = if (reset) fetchedRawCount else it.rawResultCount + fetchedRawCount,
                        )
                    }
                    return
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = result.errorText(CommonR.string.search_failed),
                        )
                    }
                    return
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            // Never the raw exception: it carries the full
                            // request URL and read like a stack trace on a
                            // ten-foot screen.
                            error = result.errorText(CommonR.string.search_failed),
                        )
                    }
                    return
                }
            }
        }
        } finally {
            boxSetsRequest?.cancel()
        }
    }
    private fun hasAiSelection(): Boolean = aiSearch != null && _uiState.value.mediaType != TvSearchMediaType.Audiobooks &&
        (_uiState.value.aiFilters.active || _uiState.value.mediaType in listOf(TvSearchMediaType.Movies, TvSearchMediaType.Series))

    fun onAiFiltersChanged(filters: CinemaBaySearchFilters) {
        _uiState.update { current -> current.copy(aiFilters = filters, aiNotice=null,
            aiIntent = CinemaBayGermanQueryParser.parse(current.query).let { it.copy(filters=it.filters.and(filters),mediaType=current.mediaType.wire ?: it.mediaType) }) }
        submitSearch()
    }

    fun refreshAiIndex() { aiSearch?.refresh() }

    init {
        if (aiSearch != null) {
            aiSearch.start()
            viewModelScope.launch {
                var appliedEpoch = -1L
                var refresh: Job? = null
                aiSearch.state.collect { index ->
                    if (appliedEpoch != index.epoch) {
                        appliedEpoch = index.epoch
                        searchGeneration += 1; searchJob?.cancel(); loadMoreJob?.cancel(); peopleJob?.cancel(); aiPeopleKey=null
                        _uiState.update { it.copy(items = emptyList(), people = emptyList(), total = 0, hasMore = false,
                            isLoading = false, isLoadingMore = false, isLoadingPeople = false, rawResultCount = 0) }
                    }
                    _uiState.update { it.copy(aiIndex = index) }
                    if ((_uiState.value.query.isNotBlank() || hasAiSelection()) && refresh?.isActive != true) {
                        refresh = viewModelScope.launch { delay(600); submitSearch() }
                    }
                }
            }
        }
    }

    private suspend fun runAiSearch(reset: Boolean) {
        val repository = aiSearch ?: return
        val generation = searchGeneration
        val state = _uiState.value
        if (!reset && resultsGeneration != generation) return
        val offset = if (reset) 0 else state.rawResultCount
        _uiState.update { it.copy(isLoading = reset, isLoadingMore = !reset, error = null) }
        val parsed = CinemaBayGermanQueryParser.parse(state.query)
        if (reset) {
            val peopleQuery = parsed.person ?: parsed.text
            val peopleKey = "$peopleQuery|${state.mediaType}|${repository.state.value.epoch}"
            if (peopleQuery.length >= 2 && aiPeopleKey != peopleKey) {
                aiPeopleKey=peopleKey
                searchPeople(generation, peopleQuery, state.mediaType, state.showAudiobooks, peopleKey)
            }
            else if (peopleQuery.length >= 2) { /* A progressive index update must not repeat the same people request. */ }
            else { peopleJob?.cancel(); _uiState.update { it.copy(people = emptyList(), isLoadingPeople = false) } }
        }
        val boxSets = if (reset && boxSetsAvailable && state.mediaType.includesBoxSets &&
            !parsed.filters.active && !state.aiFilters.active && parsed.person == null && parsed.similarTo == null && state.query.isNotBlank()) {
            viewModelScope.async { catalogRepository.searchBoxSets(state.query) }
        } else null
        try {
            val page = repository.search(state.query, state.aiFilters, state.mediaType.wire, offset, pageSize)
            val boxCards = withTimeoutOrNull(BOX_SET_SEARCH_GRACE_MILLIS) { boxSets?.await() }.orEmpty().map { it.toSearchCard() }
            kotlin.coroutines.coroutineContext.ensureActive()
            if (generation != searchGeneration || (page.epoch != 0L && page.epoch != repository.state.value.epoch)) return
            resultsGeneration = generation
            _uiState.update {
                val results = (if (reset) boxCards + page.items else it.items + page.items).distinctBy { item -> item.contentId }
                it.copy(items = results, total = page.total + results.count { item -> item.type == BOX_SET_TYPE },
                    totalExact = page.exact, hasMore = page.hasMore, isLoading = false, isLoadingMore = false,
                    rawResultCount = offset + page.items.size, aiIntent = page.intent, aiNotice = page.notice, searchDiagnostics = null)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            if (generation == searchGeneration) _uiState.update { it.copy(isLoading = false, isLoadingMore = false,
                error = UiText.Raw("Suche derzeit nicht erreichbar. Bitte den Server prüfen oder den lokalen Katalog aktualisieren."),
                aiNotice = "Bitte den Server prüfen oder den lokalen Katalog aktualisieren.") }
        } finally { boxSets?.cancel() }
    }

}
