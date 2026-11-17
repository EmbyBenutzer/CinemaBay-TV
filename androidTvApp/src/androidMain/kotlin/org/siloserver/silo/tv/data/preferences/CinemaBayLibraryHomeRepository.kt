package org.siloserver.silo.tv.data.preferences

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.network.SiloJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

internal fun cinemaBayLibraryRowId(id: Int) = "cinemabay-library:$id"
internal fun cinemaBayRecentSection(section: ResolvedSection): Boolean =
    (section.sectionType.lowercase().let { (it.contains("recent") && it.contains("add")) || it in setOf("recent", "latest", "new_episodes") }) ||
        section.titleKey?.contains("recentlyAdded", ignoreCase = true) == true

internal fun cinemaBaySameLibraryViewer(old: AuthScopeSnapshot?, current: AuthScopeSnapshot): Boolean =
    old != null && old.serverId == current.serverId && old.serverUrl == current.serverUrl &&
        old.accountId == current.accountId && old.profileId == current.profileId &&
        old.identityGeneration == current.identityGeneration && old.credentialEpoch == current.credentialEpoch

/** A recent episode promotes its parent, retaining the source's newest-first order. */
internal fun cinemaBayLibraryCards(items: List<SectionItem>): List<SectionItem> = items.mapNotNull { item ->
    when (item.type.lowercase()) {
        "episode" -> item.seriesId?.takeIf { it.isNotBlank() }?.let { parent ->
            item.copy(contentId = parent, type = "series", title = item.seriesTitle ?: item.title,
                posterUrl = item.seriesPosterUrl, posterThumbhash = item.seriesPosterThumbhash,
                backdropUrl = item.seriesBackdropUrl, backdropThumbhash = item.seriesBackdropThumbhash,
                year = 0, runtime = null, overview = null, seriesId = null, seriesTitle = null, seasonNumber = null, episodeNumber = null,
                positionSeconds = null, durationSeconds = null, userState = null,
                posterIsEpisodeStill = false, backdropIsEpisodeStill = false)
        }
        "movie", "series" -> item
        else -> null
    }
}.distinctBy { it.contentId }.take(20)

/** Only rank cross-source updates when both endpoints supply usable addition timestamps. */
internal fun cinemaBayRankLibraryUpdates(base: List<Pair<SectionItem, String?>>, episodes: List<Pair<SectionItem, String?>>): List<SectionItem>? {
    if (base.isEmpty() || episodes.isEmpty() || episodes.any { it.first.type != "episode" || it.first.seriesId.isNullOrBlank() }) return null
    fun time(text: String?): Long? = text?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
    val entries = base + episodes
    if (entries.any { time(it.second) == null }) return null
    return cinemaBayLibraryCards(entries.sortedByDescending { time(it.second) }.map { it.first })
}

internal data class CinemaBayLibraryHomeState(
    val authority: String? = null,
    val identityGeneration: Long = -1,
    val rows: List<ResolvedSection> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Only selected libraries are fetched. All data and in-memory cache are fenced to one current viewer. */
internal class CinemaBayLibraryHomeRepository(
    private val personal: PersonalDataRepository,
    private val sections: SectionRepository,
    private val catalog: CatalogRepository,
) {
    private val mutable = MutableStateFlow(CinemaBayLibraryHomeState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private val permits = Semaphore(2)
    private var owner: AuthScopeSnapshot? = null
    private var checkedAt = 0L
    private var requested = emptySet<String>()

    suspend fun refresh(enabled: Set<String>, force: Boolean = false) = mutex.withLock {
        val captured = sections.captureLibrarySectionAuthority() ?: run { clear(); return@withLock }
        val same = cinemaBaySameLibraryViewer(owner, captured)
        if (!same) clear()
        owner = captured
        val identity = listOfNotNull(captured.serverId, captured.accountId, captured.profileId).joinToString("\u001f")
        val elapsed = System.currentTimeMillis() - checkedAt
        if (same && requested == enabled && elapsed in 0..(if (force) 10_000L else 60_000L)) return@withLock
        mutable.value = mutable.value.copy(authority = identity, identityGeneration = captured.identityGeneration, loading = true, error = null)
        try {
            val libraries = (personal.listUserLibraries() as? ApiResult.Success)?.data
                ?: throw IllegalStateException("Bibliotheken derzeit nicht erreichbar")
            if (!sections.isLibrarySectionAuthorityCurrent(captured)) { clear(); return@withLock }
            val videoLibraries = libraries.filter { org.siloserver.silo.model.navigation.tvMediaModeForLibraryType(it.type) == org.siloserver.silo.model.navigation.MediaMode.Video }
                .sortedBy { it.sortOrder }
            val old = mutable.value.rows.associateBy { it.id }
            // Publish configuration choices first; an inactive library never requires a content request.
            mutable.value = mutable.value.copy(rows = videoLibraries.map { lib ->
                old[cinemaBayLibraryRowId(lib.id)] ?: placeholder(lib)
            })
            var failures = 0
            val rows = coroutineScope {
                videoLibraries.map { lib -> async {
                    if (cinemaBayLibraryRowId(lib.id) !in enabled) return@async placeholder(lib)
                    permits.withPermit {
                        try { load(lib, captured) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { synchronized(this@CinemaBayLibraryHomeRepository) { failures++ }; old[cinemaBayLibraryRowId(lib.id)] ?: placeholder(lib) }
                    }
                } }.awaitAll()
            }
            if (!sections.isLibrarySectionAuthorityCurrent(captured)) { clear(); return@withLock }
            mutable.value = CinemaBayLibraryHomeState(authority = identity, identityGeneration = captured.identityGeneration, rows = rows, error = if (failures > 0) "Einige Bibliotheksreihen konnten nicht aktualisiert werden. Gespeicherte Inhalte bleiben sichtbar." else null)
            checkedAt = System.currentTimeMillis(); requested = enabled.toSet()
        } catch (cancel: CancellationException) { mutable.value = mutable.value.copy(loading = false); throw cancel }
        catch (_: Exception) {
            if (sections.isLibrarySectionAuthorityCurrent(captured)) mutable.value = mutable.value.copy(loading = false, error = "Bibliotheken derzeit nicht erreichbar. Bitte erneut versuchen.") else clear()
        }
    }

    private suspend fun load(lib: UserLibrary, captured: AuthScopeSnapshot): ResolvedSection {
        val result = sections.getLibrarySections(lib.id, captured)
        if (result is ApiResult.Error && result.code in setOf(401, 403, 404)) return placeholder(lib)
        val response = (result as? ApiResult.Success)?.data
        val source = response?.sections?.filter(::cinemaBayRecentSection)?.let { candidates ->
            candidates.firstOrNull { it.items.any { item -> item.type == "episode" } } ?: candidates.firstOrNull()
        }
        android.util.Log.d("CinemaBayHomeLibraries", "library-${lib.id}-sources-${response?.sections?.joinToString { it.sectionType }}")
        val items = if (source != null) {
            if (source.items.isNotEmpty()) source.items else
                (sections.getLibrarySectionItems(lib.id, source.id, captured) as? ApiResult.Success)?.data?.items
                    ?: throw IllegalStateException("Bibliotheksreihe nicht erreichbar")
        } else {
            val page = (catalog.browse(libraryId = lib.id, sort = "added_at", order = "desc", limit = 20) as? ApiResult.Success)?.data
                ?: throw IllegalStateException("Bibliotheksinhalte nicht erreichbar")
            page.items.map { SiloJson.decodeFromString<SectionItem>(SiloJson.encodeToString(it)) }
        }
        val dated = if (items.none { it.type.equals("episode", true) } && lib.type.lowercase() in setOf("series", "show", "shows", "tv", "mixed", "mixed_video")) {
            val episodes = (catalog.browse(libraryId = lib.id, mediaType = "episode", sort = "added_at", order = "desc", limit = 100) as? ApiResult.Success)?.data?.items.orEmpty()
            android.util.Log.d("CinemaBayHomeLibraries", "library-${lib.id}-recent-episode-count-${episodes.count { it.type == "episode" }}")
            if (episodes.isEmpty()) null else {
                val base = (catalog.browse(libraryId = lib.id, sort = "added_at", order = "desc", limit = 20) as? ApiResult.Success)?.data?.items.orEmpty()
                fun entry(item: org.siloserver.silo.model.catalog.BrowseItem) =
                    SiloJson.decodeFromString<SectionItem>(SiloJson.encodeToString(item)) to item.addedAt
                cinemaBayRankLibraryUpdates(base.map(::entry), episodes.map(::entry))
            }
        } else null
        val cards = (dated ?: cinemaBayLibraryCards(items)).map { item ->
            if (item.type == "series" && item.year == 0) {
                val detail = (catalog.getItemDetailForPrefetch(item.contentId) as? ApiResult.Success)?.data
                if (detail != null) item.copy(title = detail.title, year = detail.year, posterUrl = detail.posterUrl ?: item.posterUrl,
                    posterThumbhash = detail.posterThumbhash ?: item.posterThumbhash,
                    backdropUrl = detail.backdropUrl ?: item.backdropUrl, logoUrl = detail.logoUrl,
                    overview = detail.overview, ratingImdb = detail.ratingImdb, ratingTmdb = detail.ratingTmdb,
                    genres = detail.genres, originalLanguage = detail.originalLanguage, studios = detail.studios, networks = detail.networks) else item
            } else item
        }
        return placeholder(lib).copy(items = cards, totalCount = cards.size,
            titleArgs = buildMap { put("cinemabay_library_id", lib.id.toString()); source?.let { put("cinemabay_source_id", it.id) } })
    }

    private fun placeholder(lib: UserLibrary) = ResolvedSection(
        id = cinemaBayLibraryRowId(lib.id), sectionType = "cinemabay_library_recent", title = lib.name, itemLimit = 20,
        titleArgs = mapOf("cinemabay_library_id" to lib.id.toString()))
    private fun clear() { owner = null; checkedAt = 0; requested = emptySet(); mutable.value = CinemaBayLibraryHomeState() }
}
