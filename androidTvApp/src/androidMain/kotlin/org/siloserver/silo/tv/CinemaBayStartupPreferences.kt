package org.siloserver.silo.tv

import android.content.Context

/** Device-local preferences: startup choices never change another viewer's server settings. */
class CinemaBayStartupPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("cinemabay_startup", Context.MODE_PRIVATE)

    var showIntro: Boolean
        get() = preferences.getBoolean("show_intro", true)
        set(value) { preferences.edit().putBoolean("show_intro", value).apply() }
}
