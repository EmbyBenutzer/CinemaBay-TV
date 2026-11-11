package org.siloserver.silo.tv.search

import android.content.Context
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogQueryRule
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.searchPeopleForQuery

data class CinemaBayIndexState(
    val epoch: Long = 0,
    val count: Int = 0,
    val syncing: Boolean = false,
    val complete: Boolean = false,
    val notice: String? = null,
)

data class CinemaBaySearchPage(
    val items: List<BrowseItem>, val total: Int, val hasMore: Boolean,
    val exact: Boolean, val intent: CinemaBaySearchIntent, val notice: String? = null,
    val epoch: Long = 0,
)

/**
 * One active, freshly authorized snapshot. Old on-disk rows are never served after a cold
 * start, account/profile/server transition, or access-policy change. No credential is stored.
 */
class CinemaBaySearchRepository(
    context: Context,
    private val catalog: CatalogRepository,
    private val tokens: TokenManager,
    private val identities: IdentityTransitionBarrier,
    private val access: AccessChangeSignals,
    private val ai: CinemaBayAiQueryInterpreter? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val index = CinemaBaySearchIndex(context.applicationContext)
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val mutableState = MutableStateFlow(CinemaBayIndexState())
    val state: StateFlow<CinemaBayIndexState> = mutableState.asStateFlow()
    private data class Owner(val auth: AuthScopeSnapshot, val access: Long, val epoch: Long, val key: String)
    private var owner: Owner? = null
    private var syncJob: Job? = null
    private var completedAt = 0L
    private data class Selection(val ids: Set<String>, val expiresAt: Long, val partial: Boolean)
    private val selectionCache = object : LinkedHashMap<String, Selection>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Selection>?) = size > 4
    }
    private val remoteQueries = object : LinkedHashMap<String, Long>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 32
    }

    init {
        identities.installGate { transition ->
            if (!closed.get() && transition.phase == IdentityTransitionPhase.WILL_CHANGE && transition.affectsCurrentIdentity) reset()
        }
        scope.launch { access.revision.drop(1).collect { reset(); ensureOwner() } }
    }

    fun start() { scope.launch { ensureOwner() } }
    fun refresh() { scope.launch { reset(); ensureOwner() } }

    suspend fun close() {
        closed.set(true)
        scope.coroutineContext[Job]?.cancelAndJoin()
        withContext(Dispatchers.IO) { mutex.withLock { index.clear(); index.close() } }
    }

    private suspend fun reset() {
        val next = epoch.incrementAndGet()
        syncJob?.cancel()
        mutableState.value = CinemaBayIndexState(epoch = next)
        withContext(Dispatchers.IO) { mutex.withLock { owner = null; completedAt = 0; remoteQueries.clear(); selectionCache.clear(); index.clear() } }
    }

    private suspend fun ensureOwner(): Owner? {
        // Capture outside our DB lock: the auth mutation gate must never deadlock with a snapshot.
        val auth = tokens.snapshotCurrentScope() ?: return null
        if (auth.profileId.isNullOrBlank()) return null
        val revision = access.revision.value
        val key = MessageDigest.getInstance("SHA-256").digest(
            listOf(auth.serverId, auth.serverUrl, auth.accountId, auth.profileId, auth.credentialEpoch.toString()).joinToString("\u0000").toByteArray(),
        ).joinToString("") { "%02x".format(it) }
        return mutex.withLock {
            if (auth.identityGeneration != identities.generation.value || revision != access.revision.value) return@withLock null
            val existing = owner
            if (existing != null && existing.key == key && existing.auth.isSameIdentityAs(auth) && existing.access == revision &&
                (completedAt == 0L || android.os.SystemClock.elapsedRealtime() - completedAt < 300_000)) return@withLock existing
            syncJob?.cancel(); index.clear(); remoteQueries.clear(); selectionCache.clear()
            val captured = Owner(auth, revision, epoch.incrementAndGet(), key)
            owner = captured; completedAt = 0
            mutableState.value = CinemaBayIndexState(captured.epoch, syncing = true)
            syncJob = scope.launch { synchronize(captured) }
            captured
        }
    }

    private suspend fun current(captured: Owner): Boolean {
        val now = tokens.snapshotCurrentScope()
        return currentFast(captured) && captured.auth.isSameIdentityAs(now) && now?.profileId == captured.auth.profileId &&
            now?.accountId == captured.auth.accountId && now?.serverUrl == captured.auth.serverUrl
    }

    private fun currentFast(captured: Owner): Boolean = epoch.get() == captured.epoch &&
        identities.generation.value == captured.auth.identityGeneration && access.revision.value == captured.access

    private suspend fun store(captured: Owner, items: List<BrowseItem>) {
        if (!current(captured)) throw CancellationException("Search identity changed")
        mutex.withLock { if (currentFast(captured)) index.upsert(items) }
    }

    private suspend fun synchronize(captured: Owner) {
        try {
            var capped = false
            for (type in listOf("movie", "series")) {
                var cursor: CatalogContinuationV2? = null
                var pages = 0
                do {
                    ensureActiveOwner(captured)
                    val response = catalog.browse(source = "query", mediaType = type, sort = "title", order = "asc", limit = 100, continuation = cursor)
                    coroutineContext.ensureActive()
                    if (response !is ApiResult.Success) {
                        if (current(captured)) mutableState.update { if (it.epoch == captured.epoch) it.copy(syncing = false, notice = "Lokaler Katalog noch unvollständig. Onlinesuche bleibt verfügbar; Aktualisieren versucht es erneut.") else it }
                        return
                    }
                    ensureActiveOwner(captured)
                    store(captured, response.data.items)
                    val count = mutex.withLock { index.count() }
                    if (current(captured)) mutableState.update { if (it.epoch == captured.epoch) it.copy(count = count) else it }
                    cursor = response.data.continuation
                    pages++
                    if (count >= 25_000 || pages >= 250) { capped = response.data.hasMore || type == "movie"; break }
                    if (!response.data.hasMore || response.data.items.isEmpty()) break
                    delay(75) // Low priority; do not flood older boxes or the server.
                } while (cursor != null)
                if (capped) break
            }
            if (current(captured)) {
                mutex.withLock { completedAt = android.os.SystemClock.elapsedRealtime() }
                mutableState.update { if (it.epoch == captured.epoch) it.copy(syncing = false, complete = !capped, notice = if (capped) "Lokaler Katalog auf 25.000 Titel begrenzt; Onlinesuche ergänzt." else null) else it }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            if (current(captured)) mutableState.update { if (it.epoch == captured.epoch) it.copy(syncing = false, notice = "Lokaler Katalog derzeit nicht vollständig erreichbar. Aktualisieren versucht es erneut.") else it }
        }
    }

    private suspend fun ensureActiveOwner(captured: Owner) {
        coroutineContext.ensureActive()
        if (!current(captured)) throw CancellationException("Search identity changed")
    }

    suspend fun search(raw: String, manual: CinemaBaySearchFilters, type: String?, offset: Int, limit: Int = 40): CinemaBaySearchPage = withContext(Dispatchers.IO) {
        val local = CinemaBayGermanQueryParser.parse(raw)
        val external = if (ai != null && raw.isNotBlank()) try { withTimeoutOrNull(2500) { ai.interpret(raw) } } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null } else null
        var intent = (external ?: local).let { it.copy(filters = it.filters.and(manual), mediaType = type ?: it.mediaType) }
        val captured = ensureOwner() ?: return@withContext CinemaBaySearchPage(emptyList(), 0, false, false, intent, "Bitte zuerst ein Profil anmelden und den Server verbinden.")
        ensureActiveOwner(captured)

        // A partial local snapshot is supplemented by the existing read-only catalog endpoint.
        if (!state.value.complete || intent.similarTo != null) {
            val text = intent.similarTo ?: intent.text
            val cacheKey = "$text|${intent.mediaType}|${intent.filters}"
            val shouldRead = mutex.withLock {
                val now = android.os.SystemClock.elapsedRealtime()
                val old = remoteQueries[cacheKey]
                if (old != null && now-old < 30_000) false else { remoteQueries[cacheKey] = now; true }
            }
            if (shouldRead) {
                val result = catalog.browse(source = "query", query = text.takeIf { it.isNotBlank() }, mediaType = intent.mediaType, limit = 100,
                    yearMin = intent.filters.yearMin, yearMax = intent.filters.yearMax)
                ensureActiveOwner(captured)
                if (result is ApiResult.Success) store(captured, result.data.items)
                else mutex.withLock { remoteQueries.remove(cacheKey) }
            }
        }

        var allowedIds: Set<String>? = null
        var notice: String? = state.value.notice
        var scopeLimited = false
        if (intent.person != null) {
            val key = "person:${intent.person}:${intent.mediaType}"
            val cached = mutex.withLock { selectionCache[key]?.takeIf { it.expiresAt > android.os.SystemClock.elapsedRealtime() } }
            if (cached != null) {
                allowedIds = cached.ids; scopeLimited = cached.partial
            } else {
                val people = catalog.searchPeopleForQuery(intent.person!!, listOf(intent.mediaType ?: "video"))
                ensureActiveOwner(captured)
                val wanted = CinemaBaySearchText.normalize(intent.person!!)
                val person = people.firstOrNull { CinemaBaySearchText.normalize(it.name) == wanted }
                if (person == null) return@withContext CinemaBaySearchPage(emptyList(), 0, false, true, intent, "Keine eindeutig passende Person gefunden. Bitte den vollständigen Namen verwenden.")
                val ids = mutableSetOf<String>(); var cursor: CatalogContinuationV2? = null
                var pages = 0
                do {
                    val credits = catalog.getPersonItems(person.id, mediaType = intent.mediaType, continuation = cursor, limit = 100)
                    ensureActiveOwner(captured)
                    if (credits !is ApiResult.Success) return@withContext CinemaBaySearchPage(emptyList(), 0, false, false, intent, "Filmografie derzeit nicht verfügbar.")
                    store(captured, credits.data.items); ids += credits.data.items.map { it.contentId }
                    cursor = credits.data.continuation; pages++
                    if (!credits.data.hasMore || credits.data.items.isEmpty()) break
                } while (cursor != null && pages < 20)
                if (cursor != null) notice = "Filmografie auf die ersten 2.000 Einträge begrenzt."
                allowedIds = ids
                scopeLimited = cursor != null
                mutex.withLock { if (currentFast(captured)) selectionCache[key] = Selection(ids, android.os.SystemClock.elapsedRealtime()+30_000, scopeLimited) }
            }
        }

        if (intent.filters.audioLanguage != null) {
            val key = "audio:${intent.filters.audioLanguage}:${intent.mediaType}"
            val cached = mutex.withLock { selectionCache[key]?.takeIf { it.expiresAt > android.os.SystemClock.elapsedRealtime() } }
            if (cached != null) {
                allowedIds = allowedIds?.intersect(cached.ids) ?: cached.ids
                scopeLimited = scopeLimited || cached.partial
            } else {
                val ids = mutableSetOf<String>(); var cursor: CatalogContinuationV2? = null; var pages = 0
                do {
                    val result = catalog.browse(source = "query", mediaType = intent.mediaType, limit = 100, continuation = cursor,
                        queryGroups = listOf(CatalogQueryGroup(rules = listOf(CatalogQueryRule("audio_language", "is", intent.filters.audioLanguage!!)))))
                    ensureActiveOwner(captured)
                    if (result !is ApiResult.Success) return@withContext CinemaBaySearchPage(emptyList(), 0, false, false, intent, "Der Server konnte den Tonspurfilter nicht bestätigen. Es werden keine ungeprüften Treffer angezeigt.")
                    store(captured, result.data.items); ids += result.data.items.map { it.contentId }
                    cursor = result.data.continuation; pages++
                    if (!result.data.hasMore || result.data.items.isEmpty()) break
                } while (cursor != null && pages < 250)
                if (cursor != null) notice = "Tonspurfilter auf die ersten 25.000 passenden Titel begrenzt."
                allowedIds = allowedIds?.intersect(ids) ?: ids
                scopeLimited = scopeLimited || cursor != null
                mutex.withLock { if (currentFast(captured)) selectionCache[key] = Selection(ids, android.os.SystemClock.elapsedRealtime()+30_000, cursor != null) }
            }
        }

        var seed: BrowseItem? = null
        if (intent.similarTo != null) {
            seed = mutex.withLock { index.search(CinemaBaySearchIntent(text = intent.similarTo!!), intent.mediaType, 40).items.firstOrNull() }
            if (seed == null || cinemaBaySearchScore(seed, intent.similarTo!!) < 70) return@withContext CinemaBaySearchPage(emptyList(), 0, false, state.value.complete, intent, "Ausgangstitel für den Vergleich noch nicht gefunden.")
            if (seed.genres.isEmpty() && CinemaBaySearchText.tokens(seed.overview.orEmpty()).none { it.length >= 5 })
                return@withContext CinemaBaySearchPage(emptyList(), 0, false, state.value.complete, intent, "Für den Ausgangstitel fehlen Vergleichsmetadaten.")
            intent = intent.copy(text = "", filters = if (intent.filters.genres.isEmpty()) intent.filters.copy(genres = seed.genres.map(CinemaBaySearchText::canonicalGenre).toSet(), anyGenre = true) else intent.filters)
            notice = "Ähnliche Titel nach Genres und Beschreibung, ohne Cloud-Modell."
        }
        val matches = mutex.withLock { index.search(intent, intent.mediaType) }
        coroutineContext.ensureActive()
        ensureActiveOwner(captured)
        var items = matches.items.filter { allowedIds?.contains(it.contentId) != false && it.contentId != seed?.contentId }
        if (seed != null) {
            val sourceGenres = seed.genres.map(CinemaBaySearchText::canonicalGenre).toSet()
            val sourceWords = CinemaBaySearchText.tokens(seed.overview.orEmpty()).filter { it.length >= 5 }.toSet()
            items = items.sortedByDescending { candidate -> candidate.genres.count { CinemaBaySearchText.canonicalGenre(it) in sourceGenres } * 10 + CinemaBaySearchText.tokens(candidate.overview.orEmpty()).count { it in sourceWords } }
        }
        if (matches.truncated) notice = listOfNotNull(notice, "Mehr als 1.200 passende Titel. Bitte die Suche weiter eingrenzen.").joinToString(" ")
        CinemaBaySearchPage(items.drop(offset).take(limit), items.size, offset + limit < items.size,
            state.value.complete && !matches.truncated && !scopeLimited, intent, notice, captured.epoch)
    }
}
