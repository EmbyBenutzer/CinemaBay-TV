package org.siloserver.silo.tv.ui.components

import kotlin.test.*
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.catalog.SeasonsResponse
import org.siloserver.silo.model.catalog.SeasonUserData

class CinemaBayEpisodeCountsTest {
    @Test fun episodeUsesParentSeriesAndNeverItsOwnId() {
        assertEquals("series-1", cinemaBaySeriesBadgeId("episode", "episode-9", "series-1"))
        assertEquals("series-1", cinemaBaySeriesBadgeId("series", "series-1", null))
        assertNull(cinemaBaySeriesBadgeId("episode", "episode-9", null))
        assertNull(cinemaBaySeriesBadgeId("episode", "episode-9", " "))
        assertNull(cinemaBaySeriesBadgeId("movie", "movie-1", "series-1"))
    }
    @Test fun sumsAvailableSeasonsAndSpecials() {
        val result=SeasonsResponse(listOf(
            Season("s1",1,episodeCount=10,userData=SeasonUserData(watchedCount=4)),
            Season("s2",2,episodeCount=8,userData=SeasonUserData(played=true)),
            Season("special",0,episodeCount=2,userData=SeasonUserData(watchedCount=1)),
        )).cinemaBayEpisodeCounts()
        assertEquals(CinemaBayEpisodeCounts(20,13),result)
    }
    @Test fun unknownWatchedCountIsNotZero() {
        assertEquals(CinemaBayEpisodeCounts(10,null),SeasonsResponse(listOf(Season("s",1,episodeCount=10))).cinemaBayEpisodeCounts())
    }
    @Test fun clampsInvalidWatchedCountsAndIgnoresDuplicateSeasons() {
        val season=Season("s",1,episodeCount=10,userData=SeasonUserData(watchedCount=50))
        assertEquals(CinemaBayEpisodeCounts(10,10),SeasonsResponse(listOf(season,season)).cinemaBayEpisodeCounts())
    }
    @Test fun emptyAndUnavailableSeasonsDoNotInventTotals() {
        assertNull(SeasonsResponse().cinemaBayEpisodeCounts())
        assertNull(SeasonsResponse(listOf(Season("s",1,episodeCount=-1))).cinemaBayEpisodeCounts())
    }
}
