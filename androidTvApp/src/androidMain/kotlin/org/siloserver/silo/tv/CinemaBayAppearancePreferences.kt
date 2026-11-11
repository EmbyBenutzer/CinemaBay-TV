package org.siloserver.silo.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Device-local appearance. Existing installations and first launches stay in normal mode. */
class CinemaBayAppearancePreferences(context: Context) {
    internal val preferences = context.applicationContext
        .getSharedPreferences("cinemabay_appearance", Context.MODE_PRIVATE)
    // New key: removed experimental previews must never silently re-enable this version.
    var cardTrailers: Boolean
        get() = preferences.getBoolean("card_trailers", false)
        set(value) { preferences.edit().putBoolean("card_trailers", value).apply() }

    var lightMode: Boolean
        get() = preferences.getBoolean("light_mode", false)
        set(value) { preferences.edit().putBoolean("light_mode", value).apply() }
}

val LocalCinemaBayLightMode = staticCompositionLocalOf { false }

@Composable
fun rememberCinemaBayLightMode(): Boolean {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { CinemaBayAppearancePreferences(context) }
    var enabled by remember(store) { mutableStateOf(store.lightMode) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "light_mode") enabled = store.lightMode
        }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        enabled = store.lightMode
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}


val LocalCinemaBayCardTrailers = staticCompositionLocalOf { false }
val LocalCinemaBayPreviewAllowed = staticCompositionLocalOf { true }

@Composable
fun rememberCinemaBayCardTrailers(): Boolean {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { CinemaBayAppearancePreferences(context) }
    var enabled by remember(store) { mutableStateOf(store.cardTrailers) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "card_trailers") enabled = store.cardTrailers
        }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        enabled = store.cardTrailers
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}
