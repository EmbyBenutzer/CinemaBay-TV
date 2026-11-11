package org.siloserver.silo.tv.ui.screens.search

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.siloserver.silo.tv.search.*
import org.siloserver.silo.tv.ui.components.TvDialogOption
import org.siloserver.silo.tv.ui.components.TvOptionDialog

@Composable
internal fun CinemaBaySearchBrand() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CinemaBay", color = Color(0xFFE53935), fontSize = 22.sp)
        Text("AI Search 1.0", color = Color.White, fontSize = 22.sp)
    }
}

@Composable
internal fun CinemaBaySearchExplanation(filters: CinemaBaySearchFilters, intent: CinemaBaySearchIntent?, index: CinemaBayIndexState, notice: String?) {
    val labels = intent?.filters?.labels() ?: filters.labels()
    val interpretation = buildList {
        intent?.mediaType?.let { add(if (it == "movie") "Filme" else "Serien") }
        addAll(labels)
        intent?.person?.let { add("Mit $it") }
        intent?.similarTo?.let { add("Ähnlich wie $it") }
    }.distinct().joinToString(" · ")
    Text(
        if (interpretation.isBlank()) "Titel, Tippfehler oder ganze Sätze: z. B. ungesehene Thriller ab 2015" else interpretation,
        fontSize = 13.sp, color = Color.White.copy(alpha = .8f), maxLines = 2,
    )
    val progress = when {
        index.syncing -> "Lokaler Katalog wird vorbereitet · ${index.count} Titel · Ergebnisse werden ergänzt"
        index.complete -> "Lokale Suche · ${index.count} Titel · ohne Cloud-Modell"
        else -> "Lokale Suche · ${index.count} Titel · Onlinesuche ergänzt"
    }
    Text(listOfNotNull(progress, notice ?: index.notice).joinToString(" · "), fontSize = 12.sp, color = Color.White.copy(alpha = .58f), maxLines = 2)
}

@Composable
internal fun CinemaBaySearchFiltersDialog(
    filters: CinemaBaySearchFilters,
    onChange: (CinemaBaySearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var category by remember { mutableStateOf<String?>(null) }
    fun choose(value: CinemaBaySearchFilters) { onChange(value) }
    val options: List<TvDialogOption> = when (category) {
        "genre" -> CinemaBayGermanQueryParser.genres.map { genre ->
            TvDialogOption(genre, genre, selected = genre in filters.genres,
                onClick = { choose(filters.copy(genres = if (genre in filters.genres) filters.genres-genre else filters.genres+genre)) })
        } + TvDialogOption("genre-any", "Genres mit ODER kombinieren", subtitle = "Aus: alle gewählten Genres müssen passen", selected = filters.anyGenre,
            onClick = { choose(filters.copy(anyGenre = !filters.anyGenre)) })
        "year" -> listOf(
            TvDialogOption("all", "Alle Jahre", selected = filters.yearMin == null && filters.yearMax == null, onClick = { choose(filters.copy(yearMin = null, yearMax = null)) }),
            TvDialogOption("recent", "Seit 2020", selected = filters.yearMin == 2020 && filters.yearMax == null, onClick = { choose(filters.copy(yearMin=2020,yearMax=null)) }),
        ) + (2020 downTo 1950 step 10).map { year -> TvDialogOption("$year", "$year–${year+9}", selected = filters.yearMin == year && filters.yearMax == year+9,
            onClick = { choose(filters.copy(yearMin=year,yearMax=year+9)) }) }
        "rating" -> listOf<Double?>(null, 6.0, 7.0, 7.5, 8.0, 8.5, 9.0).map { rating -> TvDialogOption("rating-$rating", rating?.let { "Mindestens $it / 10" } ?: "Keine Mindestbewertung",
            subtitle = "IMDb, falls vorhanden; sonst TMDB", selected = filters.minRating == rating, onClick = { choose(filters.copy(minRating=rating)) }) }
        "runtime" -> listOf<Int?>(null, 30, 60, 90, 120, 150, 180).map { minutes -> TvDialogOption("runtime-$minutes", minutes?.let { "Bis $it Minuten" } ?: "Beliebige Länge",
            subtitle = "Filmlänge bzw. typische Folgenlänge; unbekannte Laufzeit passt nicht auf einen Grenzwert", selected = filters.maxMinutes == minutes, onClick = { choose(filters.copy(maxMinutes=minutes)) }) }
        "language" -> listOf(null to "Alle Originalsprachen", "de" to "Deutsch", "en" to "Englisch", "fr" to "Französisch", "es" to "Spanisch", "ja" to "Japanisch", "ko" to "Koreanisch").map { (code, label) ->
            TvDialogOption("original-$code", label, subtitle = "Originalsprache, nicht Synchronfassung", selected = filters.originalLanguage == code, onClick = { choose(filters.copy(originalLanguage=code)) })
        } + TvDialogOption("audio-de", "Deutsche Tonspur", subtitle="Wird durch den bestehenden Serverfilter geprüft", selected=filters.audioLanguage=="de", onClick={choose(filters.copy(audioLanguage=if(filters.audioLanguage=="de")null else "de"))})
        "quality" -> listOf(
            TvDialogOption("uhd", "4K / UHD", selected = filters.uhd, onClick = { choose(filters.copy(uhd=!filters.uhd)) }),
            TvDialogOption("hdr", "HDR / Dolby Vision", selected = filters.hdr, onClick = { choose(filters.copy(hdr=!filters.hdr)) }),
        )
        "personal" -> listOf(
            TvDialogOption("unwatched", "Nur ungesehene Titel", selected=filters.unwatched, onClick={choose(filters.copy(unwatched=!filters.unwatched))}),
            TvDialogOption("favorites", "Nur Favoriten", selected=filters.favorites, onClick={choose(filters.copy(favorites=!filters.favorites))}),
            TvDialogOption("watchlist", "Nur Merkliste", selected=filters.watchlist, onClick={choose(filters.copy(watchlist=!filters.watchlist))}),
            TvDialogOption("age", "Freigabe bis 12", subtitle="Unbekannte Freigaben werden ausgeschlossen; Profilrechte bleiben maßgeblich", selected=filters.maxAge==12, onClick={choose(filters.copy(maxAge=if(filters.maxAge==12)null else 12))}),
        )
        "sort" -> listOf("relevance" to "Beste Übereinstimmung", "year" to "Neueste zuerst", "rating" to "Beste Bewertung", "title" to "Titel A–Z").map { (key,label) ->
            TvDialogOption(key,label,selected=filters.sort==key,onClick={choose(filters.copy(sort=key))}) }
        else -> listOf(
            "genre" to "Genre", "year" to "Zeitraum", "rating" to "Bewertung", "runtime" to "Laufzeit",
            "language" to "Originalsprache und Ton", "quality" to "Bildqualität", "personal" to "Gesehen, Favoriten und Merkliste", "sort" to "Sortierung",
        ).map { (key,label) -> TvDialogOption(key,label,onClick={category=key}) } +
            TvDialogOption("reset", "Manuelle Filter zurücksetzen", subtitle="Filter aus dem Suchsatz bleiben sichtbar wirksam", onClick={choose(CinemaBaySearchFilters());onDismiss()})
    }
    TvOptionDialog(
        title = if (category == null) "Intelligente Filter · miteinander kombinierbar" else when(category) {
            "genre" -> "Genre · mehrere auswählbar"; "year" -> "Zeitraum"; "rating" -> "Bewertung"; "runtime" -> "Laufzeit";
            "language" -> "Originalsprache und Ton"; "quality" -> "Bildqualität"; "personal" -> "Persönliche Filter"; else -> "Sortierung"
        },
        options = options,
        onDismiss = { if(category == null) onDismiss() else category=null },
    )
}
