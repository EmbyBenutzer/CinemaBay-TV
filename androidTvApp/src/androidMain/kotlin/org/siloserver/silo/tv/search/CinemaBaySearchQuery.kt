package org.siloserver.silo.tv.search

import java.text.Normalizer
import java.time.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class CinemaBayGenreGroup(val genres: Set<String> = emptySet(), val any: Boolean = false)

@Serializable
data class CinemaBaySearchFilters(
    val genres: Set<String> = emptySet(),
    val excludedGenres: Set<String> = emptySet(),
    val anyGenre: Boolean = false,
    val genreGroups: List<CinemaBayGenreGroup> = emptyList(),
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val minRating: Double? = null,
    val maxMinutes: Int? = null,
    val originalLanguage: String? = null,
    val audioLanguage: String? = null,
    val unwatched: Boolean = false,
    val favorites: Boolean = false,
    val watchlist: Boolean = false,
    val uhd: Boolean = false,
    val hdr: Boolean = false,
    val maxAge: Int? = null,
    val sort: String = "relevance",
) {
    val active: Boolean get() = this != CinemaBaySearchFilters()
    /** Manual choices narrow the parsed request; an explicit range is never silently widened. */
    fun and(other: CinemaBaySearchFilters) = copy(
        genres = if (genres.isEmpty()) other.genres else genres, excludedGenres = excludedGenres + other.excludedGenres,
        anyGenre = if (genres.isEmpty()) other.anyGenre else anyGenre,
        genreGroups = genreGroups + other.genreGroups + if (genres.isNotEmpty() && other.genres.isNotEmpty()) listOf(CinemaBayGenreGroup(other.genres, other.anyGenre)) else emptyList(),
        yearMin = listOfNotNull(yearMin, other.yearMin).maxOrNull(),
        yearMax = listOfNotNull(yearMax, other.yearMax).minOrNull(),
        minRating = listOfNotNull(minRating, other.minRating).maxOrNull(),
        maxMinutes = listOfNotNull(maxMinutes, other.maxMinutes).minOrNull(),
        originalLanguage = other.originalLanguage ?: originalLanguage,
        audioLanguage = other.audioLanguage ?: audioLanguage,
        unwatched = unwatched || other.unwatched, favorites = favorites || other.favorites,
        watchlist = watchlist || other.watchlist, uhd = uhd || other.uhd, hdr = hdr || other.hdr,
        maxAge = listOfNotNull(maxAge, other.maxAge).minOrNull(),
        sort = if (other.sort != "relevance") other.sort else sort,
    )

    fun labels(): List<String> = buildList {
        if (genres.isNotEmpty()) add(genres.joinToString(if (anyGenre) " oder " else " + "))
        genreGroups.forEach { group -> if (group.genres.isNotEmpty()) add(group.genres.joinToString(if (group.any) " oder " else " + ")) }
        if (excludedGenres.isNotEmpty()) add("Ohne " + excludedGenres.joinToString())
        if (yearMin != null || yearMax != null) add("Jahr ${yearMin ?: "…"}–${yearMax ?: "…"}")
        minRating?.let { add("Bewertung ≥ $it") }
        maxMinutes?.let { add("Bis $it Min.") }
        originalLanguage?.let { add("Original: $it") }
        audioLanguage?.let { add("Ton: $it") }
        if (unwatched) add("Ungesehen")
        if (favorites) add("Favoriten")
        if (watchlist) add("Merkliste")
        if (uhd) add("4K")
        if (hdr) add("HDR")
        maxAge?.let { add("Freigabe bis $it") }
        if (sort != "relevance") add(when (sort) { "year" -> "Neueste zuerst"; "rating" -> "Beste Bewertung"; else -> "Titel A–Z" })
    }
}

@Serializable
data class CinemaBaySearchIntent(
    val text: String = "",
    val mediaType: String? = null,
    val filters: CinemaBaySearchFilters = CinemaBaySearchFilters(),
    val person: String? = null,
    val similarTo: String? = null,
    val notes: List<String> = emptyList(),
)

object CinemaBaySearchText {
    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val whitespace = Regex("\\s+")
    fun normalize(text: String): String = Normalizer.normalize(text.lowercase().replace("ß", "ss"), Normalizer.Form.NFD)
        .replace(marks, "").replace(separators, " ").trim().replace(whitespace, " ")
    fun tokens(text: String): List<String> = normalize(text).split(' ').filter { it.isNotBlank() }.take(16)
    fun canonicalGenre(value: String): String = when (normalize(value)) {
        "komodie", "komoedie", "comedy" -> "Komödie"
        "science fiction", "sciencefiction", "sci fi", "scifi" -> "Science-Fiction"
        "romance", "romantik", "liebesfilm", "romantisch" -> "Romantik"
        "dokumentarfilm", "documentary", "dokumentation", "doku" -> "Dokumentation"
        "familie", "family" -> "Familie"
        "krimi", "crime" -> "Krimi"
        "abenteuer", "adventure" -> "Abenteuer"
        "animation", "animationsfilm" -> "Animation"
        "war", "krieg", "kriegsfilm" -> "Krieg"
        "history", "historie", "geschichte" -> "Historie"
        else -> normalize(value).replaceFirstChar { it.uppercase() }
    }
}

/** Deterministic German intent parser, not a bundled language model. Unknown text remains searchable. */
object CinemaBayGermanQueryParser {
    private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    val genres = listOf("Action", "Abenteuer", "Animation", "Drama", "Familie", "Fantasy", "Historie", "Horror", "Komödie", "Krieg", "Krimi", "Musik", "Mystery", "Romantik", "Science-Fiction", "Thriller", "Dokumentation", "Western")
    private val aliases = linkedMapOf(
        "science[ -]?fiction|sci[ -]?fi" to "Science-Fiction", "kom[oö]dien?|komoedien?|comedy|lustig\\w*|witzig\\w*" to "Komödie",
        "doku(?:mentation|mentarfilm)?e?n?" to "Dokumentation", "romanti\\w*|liebesfilme?" to "Romantik",
        "abenteuer(?:filme?)?" to "Abenteuer", "familienfilme?|familienfreundlich\\w*|kinderfilme?" to "Familie",
        "animations?filme?|animation" to "Animation", "krimi(?:s)?|crime" to "Krimi",
        "kriegsfilme?|krieg" to "Krieg", "historisch\\w*|historie" to "Historie",
        "action(?:filme?)?" to "Action", "horror(?:filme?)?|gruselig\\w*" to "Horror", "spannend\\w*|thriller" to "Thriller",
        "dramen|drama" to "Drama", "fantasy" to "Fantasy", "mystery" to "Mystery", "western" to "Western", "musikfilme?" to "Musik",
    )

    fun parse(raw: String, year: Int = LocalDate.now().year): CinemaBaySearchIntent {
        if (raw.trim().matches(Regex("\\d{4}"))) return CinemaBaySearchIntent(text = raw.trim())
        // Explicit quotes protect titles such as "Action Jackson" from filter extraction.
        val quoted = Regex("[\"„]([^\"“]+)[\"“]").find(raw)?.groupValues?.get(1)
        var text = raw.take(240).lowercase()
        if (quoted != null) text = text.replace(Regex("[\"„][^\"“]+[\"“]"), " ")
        var f = CinemaBaySearchFilters(); var type: String? = null; var semantic = false
        fun extract(pattern: String, action: (MatchResult) -> Unit) {
            val regex = patterns.getOrPut(pattern) { Regex(pattern, RegexOption.IGNORE_CASE) }
            regex.findAll(text).toList().forEach { action(it); semantic = true }
            text = text.replace(regex, " ")
        }
        extract("\\b(?:zwischen|von)\\s+(19\\d{2}|20\\d{2})\\s+(?:und|bis)\\s+(19\\d{2}|20\\d{2})\\b") {
            f = f.copy(yearMin = it.groupValues[1].toInt(), yearMax = it.groupValues[2].toInt())
        }
        extract("\\b(?:aus\\s+den\\s+)?(\\d{2}|19\\d0|20\\d0)er(?:n)?(?:\\s*jahre[n]?)?\\b") {
            var decade = it.groupValues[1].toInt(); if (decade < 100) decade += if (decade >= 30) 1900 else 2000
            f = f.copy(yearMin = decade, yearMax = decade + 9)
        }
        extract("\\b(ab|seit|nach|vor|bis)\\s+(19\\d{2}|20\\d{2})\\b") {
            val y = it.groupValues[2].toInt()
            f = when (it.groupValues[1]) { "vor" -> f.copy(yearMax = y - 1); "bis" -> f.copy(yearMax = y); "nach" -> f.copy(yearMin = y + 1); else -> f.copy(yearMin = y) }
        }
        extract("\\b(?:aus(?:\\s+dem\\s+jahr)?\\s+)?(19\\d{2}|20\\d{2})\\b") { f = f.copy(yearMin = it.groupValues[1].toInt(), yearMax = it.groupValues[1].toInt()) }
        extract("\\b(?:mindestens|bewertung(?:\\s+(?:ab|von|über|ueber|mindestens))?|rating(?:\\s+ab)?|ab)\\s*([0-9](?:[.,][0-9])?|10)(?:\\s*(?:sterne|punkte))?\\b") {
            f = f.copy(minRating = it.groupValues[1].replace(',', '.').toDouble().coerceIn(0.0, 10.0))
        }
        extract("\\b(?:unter|maximal|höchstens|hoechstens|bis|nicht\\s+länger\\s+als|nicht\\s+laenger\\s+als)\\s*(\\d+(?:[.,]\\d+)?|eine?|zwei|drei|anderthalb)\\s*(minuten?|mins?\\.?|stunden?)\\b") {
            val value = when (it.groupValues[1]) { "ein", "eine" -> 1.0; "zwei" -> 2.0; "drei" -> 3.0; "anderthalb" -> 1.5; else -> it.groupValues[1].replace(',', '.').toDouble() }
            f = f.copy(maxMinutes = (value * if (it.groupValues[2].startsWith("stund")) 60 else 1).toInt().coerceIn(1, 1440))
        }
        extract("\\b(?:noch\\s+nicht\\s+gesehen|nicht\\s+gesehen|ungesehen\\w*|ungeschaut\\w*)\\b") { f = f.copy(unwatched = true) }
        extract("\\b(?:favoriten|lieblingsfilme|lieblingsserien)\\b") { f = f.copy(favorites = true) }
        extract("\\b(?:auf\\s+(?:meiner|der)\\s+merkliste|merkliste|watchlist)\\b") { f = f.copy(watchlist = true) }
        extract("\\b(?:in\\s+)?(?:4k|uhd|2160p)\\b") { f = f.copy(uhd = true) }
        extract("\\b(?:in\\s+)?(?:hdr|dolby\\s+vision)\\b") { f = f.copy(hdr = true) }
        extract("\\b(?:fsk\\s*(?:bis|maximal)?\\s*|freigabe\\s+bis\\s*)(0|6|12|16|18)\\b") { f = f.copy(maxAge = it.groupValues[1].toInt()) }
        extract("\\b(?:auf\\s+deutsch|mit\\s+deutscher?\\s+(?:tonspur|ton)|deutschsprachig\\w*)\\b") { f = f.copy(audioLanguage = "de") }
        extract("\\b(?:deutsche[nrms]?\\s+(?=filme?|serien?)|originalsprache\\s+deutsch)\\b") { f = f.copy(originalLanguage = "de") }
        extract("\\b(?:englische[nrms]?\\s+(?=filme?|serien?)|originalsprache\\s+englisch)\\b") { f = f.copy(originalLanguage = "en") }
        extract("\\b(?:neueste[nrms]?|neusten?|aktuellste[nrms]?)\\b") { f = f.copy(sort = "year") }
        extract("\\b(?:beste[nrms]?|bestbewertete[nrms]?|gut\\s+bewertete[nrms]?)\\b") { f = f.copy(sort = "rating") }
        extract("\\b(?:neue[nrms]?|aktuelle[nrms]?)\\s+(?=filme?|serien?)") { f = f.copy(yearMin = year - 1) }
        aliases.forEach { (pattern, genre) ->
            extract("\\b(?:ohne|keine?)\\s+(?:$pattern)\\b") { f = f.copy(excludedGenres = f.excludedGenres + genre) }
        }
        aliases.forEach { (pattern, genre) -> extract("\\b(?:$pattern)\\b") { f = f.copy(genres = f.genres + genre) } }
        if (Regex("\\boder\\b").containsMatchIn(text) && f.genres.size > 1) f = f.copy(anyGenre = true)
        extract("\\b(?:filme?|spielfilme?)\\b") { type = "movie" }
        extract("\\b(?:serien?|tv-serien?)\\b") { type = "series" }
        var person: String? = null; var similar: String? = null
        extract("\\b(?:ähnlich\\s+(?:wie|zu)|aehnlich\\s+(?:wie|zu)|wie)\\s+(.+)$") { similar = it.groupValues[1].trim() }
        extract("\\b(?:mit|von)\\s+([\\p{L}][\\p{L}.'-]+(?:\\s+[\\p{L}][\\p{L}.'-]+){1,3})\\s*$") { person = it.groupValues[1].trim() }
        val filler = setOf("ich", "suche", "such", "zeige", "zeig", "mir", "bitte", "einen", "eine", "ein", "gute", "guten", "gut", "und", "oder", "aus", "den", "dem", "die", "der", "das", "zum", "thema", "uber", "mit", "fur", "von", "in")
        val terms = if (quoted != null) quoted else if (semantic) CinemaBaySearchText.tokens(text).filterNot { it in filler }.joinToString(" ") else raw.trim()
        return CinemaBaySearchIntent(terms, type, f, person, similar)
    }
}
