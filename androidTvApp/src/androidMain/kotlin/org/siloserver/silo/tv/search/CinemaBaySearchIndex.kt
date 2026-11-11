package org.siloserver.silo.tv.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import kotlinx.serialization.encodeToString
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.network.SiloJson

/** Android's built-in FTS4: no bundled database engine, model, posters, or native ABI libraries. */
internal class CinemaBaySearchIndex(context: Context) : SQLiteOpenHelper(
    context, File(context.cacheDir, "cinemabay-search-1.db").absolutePath, null, 1,
) {
    private var vocabulary = CinemaBayVocabulary()
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE documents(id TEXT UNIQUE NOT NULL, title TEXT NOT NULL, type TEXT NOT NULL, year INTEGER, runtime INTEGER, rating REAL, language TEXT, genres TEXT, watched INTEGER, favorite INTEGER, watchlist INTEGER, uhd INTEGER, hdr INTEGER, age INTEGER, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX search_type_year ON documents(type,year)")
        db.execSQL("CREATE INDEX search_rating ON documents(rating)")
        db.execSQL("CREATE VIRTUAL TABLE terms USING fts4(title,body,tokenize=unicode61)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS terms"); db.execSQL("DROP TABLE IF EXISTS documents"); onCreate(db)
    }
    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try { db.delete("terms", null, null); db.delete("documents", null, null); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        vocabulary = CinemaBayVocabulary()
    }
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM documents", null).use { it.moveToFirst(); it.getInt(0) }
    fun upsert(items: List<BrowseItem>) {
        val db = writableDatabase; db.beginTransaction()
        try {
            for (raw in items) {
                if (raw.type !in setOf("movie", "series")) continue
                val item = raw.copy(overview = raw.overview?.take(2400))
                val title = CinemaBaySearchText.normalize(item.title)
                CinemaBaySearchText.tokens(title).forEach(vocabulary::add)
                vocabulary.add(title.replace(" ", ""))
                val oldRow = db.rawQuery("SELECT rowid FROM documents WHERE id=?", arrayOf(item.contentId)).use { if (it.moveToFirst()) it.getLong(0) else null }
                val values = ContentValues().apply {
                    put("id", item.contentId); put("title", title); put("type", item.type); put("year", item.year)
                    put("runtime", item.runtime); put("rating", item.ratingImdb ?: item.ratingTmdb)
                    put("language", item.originalLanguage?.lowercase()?.substringBefore('-'))
                    put("genres", "|" + item.genres.joinToString("|") { CinemaBaySearchText.normalize(CinemaBaySearchText.canonicalGenre(it)) } + "|")
                    put("watched", if (item.userState?.played == true) 1 else 0)
                    put("favorite", if (item.userState?.isFavorite == true) 1 else 0)
                    put("watchlist", if (item.userState?.inWatchlist == true) 1 else 0)
                    val resolution = item.overlaySummary?.resolution?.lowercase().orEmpty()
                    put("uhd", if (resolution.contains("2160") || resolution.contains("4k") || resolution.contains("uhd")) 1 else 0)
                    val hdr = item.overlaySummary?.hdr?.lowercase().orEmpty()
                    put("hdr", if (hdr.contains("hdr") || hdr.contains("dolby") || hdr == "dv") 1 else 0)
                    val age = cinemaBayAge(item.contentRating)
                    if (age != null) put("age", age) else putNull("age")
                    put("payload", SiloJson.encodeToString(item))
                }
                val row = if (oldRow != null) {
                    db.update("documents", values, "rowid=?", arrayOf(oldRow.toString())); oldRow
                } else db.insertOrThrow("documents", null, values)
                db.delete("terms", "docid=?", arrayOf(row.toString()))
                db.insertOrThrow("terms", null, ContentValues().apply {
                    put("docid", row)
                    put("title", title + " " + title.replace(" ", ""))
                    put("body", CinemaBaySearchText.normalize(listOfNotNull(item.overview, item.genres.joinToString(" "), item.studios.joinToString(" "), item.networks.joinToString(" ")).joinToString(" ")))
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    data class Matches(val items: List<BrowseItem>, val truncated: Boolean)
    fun search(intent: CinemaBaySearchIntent, type: String?, maximum: Int = 1200): Matches {
        val args = mutableListOf<String>(); val clauses = mutableListOf("d.type IN ('movie','series')")
        fun condition(sql: String, value: Any?) { if (value != null) { clauses += sql; args += value.toString() } }
        val f = intent.filters
        condition("d.type=?", type ?: intent.mediaType)
        condition("d.year>=?", f.yearMin); condition("d.year<=?", f.yearMax)
        condition("d.rating>=?", f.minRating)
        if (f.maxMinutes != null) { clauses += "d.runtime>0"; condition("d.runtime<=?", f.maxMinutes) }
        condition("d.language=?", f.originalLanguage)
        if (f.unwatched) clauses += "d.watched=0"
        if (f.favorites) clauses += "d.favorite=1"
        if (f.watchlist) clauses += "d.watchlist=1"
        if (f.uhd) clauses += "d.uhd=1"
        if (f.hdr) clauses += "d.hdr=1"
        condition("d.age<=?", f.maxAge)
        for (group in listOf(CinemaBayGenreGroup(f.genres, f.anyGenre)) + f.genreGroups) {
            if (group.genres.isEmpty()) continue
            clauses += group.genres.joinToString(if (group.any) " OR " else " AND ", "(", ")") { "d.genres LIKE ?" }
            args += group.genres.map { "%|${CinemaBaySearchText.normalize(it)}|%" }
        }
        f.excludedGenres.forEach { condition("d.genres NOT LIKE ?", "%|${CinemaBaySearchText.normalize(it)}|%") }
        val tokens = CinemaBaySearchText.tokens(intent.text).take(8)
        if (tokens.isNotEmpty()) {
            val topics = mapOf("zeitreisen" to listOf("zeitreise", "zeitreisen", "time"), "weltraum" to listOf("weltraum", "space", "raumfahrt"), "roboter" to listOf("roboter", "robot", "robots"))
            val termClauses=mutableListOf<String>()
            for (word in tokens) {
                val alternatives = (vocabulary.alternatives(word) + topics[word].orEmpty()).distinct()
                // All alternatives are normalized letter/digit tokens, never FTS operators.
                val match = alternatives.joinToString(" OR ") { "$it*" }
                // SQL performs the intersection. Do not require FTS3's optional enhanced AND syntax.
                termClauses += "d.rowid IN (SELECT docid FROM terms WHERE terms MATCH ?)"; args += match
            }
            val compact=tokens.joinToString("")
            if(tokens.size>1 && compact.length in 5..64){
                clauses += "((${termClauses.joinToString(" AND ")}) OR d.rowid IN (SELECT docid FROM terms WHERE terms MATCH ?))"
                args += "$compact*"
            }else clauses += "(${termClauses.joinToString(" AND ")})"
        }
        val sort = when (f.sort) { "rating" -> "d.rating DESC,d.year DESC"; "year" -> "d.year DESC,d.rating DESC"; "title" -> "d.title ASC"; else -> "d.year DESC" }
        val rows = readableDatabase.rawQuery("SELECT d.payload FROM documents d WHERE ${clauses.joinToString(" AND ")} ORDER BY $sort LIMIT ${maximum+1}", args.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(SiloJson.decodeFromString<BrowseItem>(cursor.getString(0))) }
        }
        val ranked = if (f.sort == "relevance" && tokens.isNotEmpty()) rows.sortedWith(compareByDescending<BrowseItem> { cinemaBaySearchScore(it, intent.text) }.thenByDescending { it.ratingImdb ?: it.ratingTmdb ?: 0.0 }.thenBy { it.contentId }) else rows
        return Matches(ranked.take(maximum), rows.size > maximum)
    }
}

internal fun cinemaBayAge(value: String?): Int? {
    val normalized = value?.uppercase()?.trim() ?: return null
    if (normalized.startsWith("FSK") || normalized.matches(Regex("\\d{1,2}"))) return Regex("\\d{1,2}").find(normalized)?.value?.toIntOrNull()
    return when (normalized) { "G", "TV-G", "TV-Y" -> 0; "TV-Y7" -> 7; "PG" -> 6; "PG-13" -> 13; "TV-PG" -> 12; "TV-14" -> 14; "R" -> 17; "NC-17", "TV-MA" -> 18; else -> null }
}
