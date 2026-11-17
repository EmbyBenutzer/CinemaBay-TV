package org.siloserver.silo.tv

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.siloserver.silo.overlays.*

enum class CinemaBayRatingSource(val label: String, val id: OverlayId? = null) {
    Existing("Wie bisher"), Off("Ausblenden"), Imdb("IMDb", OverlayId.RatingImdb),
    Tmdb("TMDB", OverlayId.RatingTmdb), Critics("Rotten Tomatoes · Kritiker", OverlayId.RatingRt),
    Audience("Rotten Tomatoes · Publikum", OverlayId.RatingRtAudience), All("Alle verfügbaren Bewertungen")
}
enum class CinemaBayRatingCorner(val label: String, val position: OverlayPosition) {
    TopLeft("Oben links", OverlayPosition.TopLeft), TopRight("Oben rechts", OverlayPosition.TopRight),
    BottomLeft("Unten links", OverlayPosition.BottomLeft), BottomRight("Unten rechts", OverlayPosition.BottomRight)
}
data class CinemaBayRatingAppearance(
    val source: CinemaBayRatingSource = CinemaBayRatingSource.Existing,
    val corner: CinemaBayRatingCorner = CinemaBayRatingCorner.BottomRight,
    val positionChosen: Boolean = false,
)
internal val cinemaBayRatingIds = setOf(OverlayId.RatingImdb, OverlayId.RatingTmdb, OverlayId.RatingRt, OverlayId.RatingRtAudience)
internal fun CinemaBayRatingAppearance.applyTo(prefs: CardOverlayPrefs): CardOverlayPrefs {
    if (source == CinemaBayRatingSource.Existing && !positionChosen) return prefs
    return prefs.copy(items = prefs.items.toMutableMap().apply {
        for (id in cinemaBayRatingIds) {
            val old = get(id) ?: OverlayItemConfig(false, corner.position)
            val enabled = when(source) {
                CinemaBayRatingSource.Existing -> old.enabled
                CinemaBayRatingSource.Off -> false
                CinemaBayRatingSource.All -> true
                else -> source.id == id
            }
            put(id, old.copy(enabled = enabled, position = corner.position))
        }
    })
}
private fun CinemaBayAppearancePreferences.readRatings() = CinemaBayRatingAppearance(
    CinemaBayRatingSource.entries.firstOrNull { it.name == preferences.getString("rating_source", null) } ?: CinemaBayRatingSource.Existing,
    CinemaBayRatingCorner.entries.firstOrNull { it.name == preferences.getString("rating_corner", null) } ?: CinemaBayRatingCorner.BottomRight,
    preferences.contains("rating_corner"),
)
fun CinemaBayAppearancePreferences.setRatingSource(source: CinemaBayRatingSource) {
    preferences.edit().putString("rating_source", source.name).apply()
}
fun CinemaBayAppearancePreferences.resetRatingCorner() {
    preferences.edit().remove("rating_corner").apply()
}
fun CinemaBayAppearancePreferences.setRatingCorner(corner: CinemaBayRatingCorner) {
    preferences.edit().putString("rating_corner", corner.name).apply()
}
val LocalCinemaBayRatings = staticCompositionLocalOf { CinemaBayRatingAppearance() }
@Composable fun rememberCinemaBayRatings(): CinemaBayRatingAppearance {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { CinemaBayAppearancePreferences(context) }
    var value by remember(store) { mutableStateOf(store.readRatings()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "rating_source" || key == "rating_corner") value = store.readRatings()
        }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        value = store.readRatings()
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}
