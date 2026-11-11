package org.siloserver.silo.tv.ui.components

import org.siloserver.silo.model.catalog.ItemVideo

internal fun cinemaBayTrailerMediaType(type: String?): Boolean =
    type?.lowercase() in setOf("movie", "series")

/** Never infer audio language from a title or fall back to English/unknown language. */
internal fun cinemaBayGermanTrailer(videos: List<ItemVideo>?): String? = videos.orEmpty()
    .filter { video ->
        video.kind.equals("trailer", true) && video.site.equals("youtube", true) &&
            video.siteKey.matches(Regex("[A-Za-z0-9_-]{11}")) &&
            video.language?.trim()?.lowercase()?.replace('_', '-')?.substringBefore('-') == "de"
    }
    .sortedByDescending { it.isOfficial }
    .firstOrNull()?.siteKey
