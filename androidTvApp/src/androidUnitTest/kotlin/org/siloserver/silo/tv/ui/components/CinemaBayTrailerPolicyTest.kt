package org.siloserver.silo.tv.ui.components

import org.junit.Test
import kotlin.test.*
import org.siloserver.silo.model.catalog.ItemVideo

class CinemaBayTrailerPolicyTest {
    private fun video(language: String?, id: String = "HTMcth-SXDQ", official: Boolean = false) =
        ItemVideo("trailer", "YouTube", id, language = language, isOfficial = official)
    @Test fun rejectsEnglishUnknownAndTitlesClaimingGerman() {
        assertNull(cinemaBayGermanTrailer(listOf(video("en"), video(null).copy(name="Trailer Deutsch"),video(""))))
    }
    @Test fun acceptsGermanRegionalTagsAndPrefersOfficial() {
        assertEquals("4_1LIA0P0rI", cinemaBayGermanTrailer(listOf(video("de-DE"),video(" DE_at ", "4_1LIA0P0rI",true))))
    }
    @Test fun rejectsInvalidIdsNonTrailersAndOtherProviders() {
        assertNull(cinemaBayGermanTrailer(listOf(video("de","bad<script>"),video("de").copy(kind="teaser"),video("de").copy(site="vimeo"))))
    }
    @Test fun onlyMoviesAndSeriesAreEligible() {
        assertTrue(cinemaBayTrailerMediaType("Movie")); assertTrue(cinemaBayTrailerMediaType("series"))
        assertFalse(cinemaBayTrailerMediaType("episode")); assertFalse(cinemaBayTrailerMediaType("audiobook"));assertFalse(cinemaBayTrailerMediaType(null))
    }
}
