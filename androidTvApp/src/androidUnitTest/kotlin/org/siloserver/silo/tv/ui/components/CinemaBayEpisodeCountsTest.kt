package org.siloserver.silo.tv.ui.components

import kotlin.test.*
import org.siloserver.silo.model.catalog.*

class CinemaBayEpisodeCountsTest {
    private fun episode(id: String, number: Int, played: Boolean? = false, title: String = "Episode $number", season: Int = 1) =
        EpisodeListItem(id, season, number, title = title, userData = played?.let { LeafItemUserData(played = it) })
    @Test fun episodeUsesParentSeriesAndNeverItsOwnId() {
        assertEquals("series-1", cinemaBaySeriesBadgeId("episode", "episode-9", "series-1"))
        assertEquals("series-1", cinemaBaySeriesBadgeId("series", "series-1", null))
        assertNull(cinemaBaySeriesBadgeId("episode", "episode-9", null))
        assertNull(cinemaBaySeriesBadgeId("episode", "episode-9", " "))
        assertNull(cinemaBaySeriesBadgeId("movie", "movie-1", "series-1"))
    }
    @Test fun excludesSeasonZeroFlaggedSpecialsAndTrailerSeasons() {
        val normal = Season("s1", 1, episodeCount = 8)
        val result = SeasonsResponse(listOf(normal, Season("s0", 0, episodeCount = 9),
            Season("special", 2, isSpecials = true, episodeCount = 2),
            Season("trailer", 99, title = "Trailer", episodeCount = 1), Season("invalid", -1, episodeCount = 1)))
            .cinemaBayRegularSeasons()
        assertEquals(listOf(normal), result)
    }
    @Test fun countsRemainingAndTurnsIntoCompletionOnlyAtZero() {
        val season = Season("s", 1, episodeCount = 3)
        val fresh = listOf(episode("a", 1), episode("b", 2), episode("c", 3))
        assertEquals(3, season.cinemaBayEpisodeCounts(fresh)?.remaining)
        assertEquals(2, season.cinemaBayEpisodeCounts(fresh.mapIndexed { i, e -> if (i == 0) e.copy(userData = LeafItemUserData(played = true)) else e })?.remaining)
        assertEquals(0, season.cinemaBayEpisodeCounts(fresh.map { it.copy(userData = LeafItemUserData(played = true)) })?.remaining)
    }
    @Test fun positiveNumberTrailerSpecialAndEpisodeZeroNeverCount() {
        val items = listOf(episode("a", 1, true), episode("b", 2),
            episode("trailer", 3, true, "Official Trailer"), episode("special", 4, true, "Special 2"), episode("zero", 0))
        assertEquals(CinemaBayEpisodeCounts(2, 1), Season("s", 1, episodeCount = 5).cinemaBayEpisodeCounts(items))
    }
    @Test fun trailerParkIsAnOrdinaryEpisodeNotATechnicalTrailerLabel() {
        assertFalse(cinemaBayExtraEpisodeTitle("Trailer Park"))
        assertFalse(cinemaBayExtraEpisodeTitle("The Special One"))
        assertTrue(cinemaBayExtraEpisodeTitle("Trailer 2 Deutsch"))
        assertEquals(1, Season("s", 1, episodeCount = 1).cinemaBayEpisodeCounts(listOf(episode("a", 1, title = "Trailer Park")))?.total)
    }
    @Test fun duplicateVersionsCountOnceAndEitherWatchedVersionCountsAsSeen() {
        assertEquals(CinemaBayEpisodeCounts(1, 1), Season("s", 1, episodeCount = 2)
            .cinemaBayEpisodeCounts(listOf(episode("hd", 1), episode("uhd", 1, true))))
    }
    @Test fun aPartialListDoesNotInventCompletion() {
        assertNull(Season("s", 1, episodeCount = 3).cinemaBayEpisodeCounts(listOf(episode("a", 1, true))))
    }
    @Test fun unknownWatchStateDoesNotMeanZeroWatchedOrAllWatched() {
        val value = Season("s", 1, episodeCount = 1).cinemaBayEpisodeCounts(listOf(episode("a", 1, null)))
        assertEquals(CinemaBayEpisodeCounts(1, null), value)
        assertNull(value?.remaining)
    }
    @Test fun seasonSummaryCanFillMissingLeafStateOnlyWithoutAmbiguousExtras() {
        assertEquals(CinemaBayEpisodeCounts(2, 1), Season("s", 1, episodeCount = 2, userData = SeasonUserData(watchedCount = 1))
            .cinemaBayEpisodeCounts(listOf(episode("a", 1, null), episode("b", 2, null))))
        assertNull(Season("s", 1, episodeCount = 2, userData = SeasonUserData(watchedCount = 1))
            .cinemaBayEpisodeCounts(listOf(episode("a", 1, null), episode("trailer", 2, null, "Trailer")))?.watched)
    }
    @Test fun invalidAggregatesDoNotProduceAnAllSeenCheck() {
        assertNull(Season("s", 1, episodeCount = 1, userData = SeasonUserData(watchedCount = 50))
            .cinemaBayEpisodeCounts(listOf(episode("a", 1, null)))?.remaining)
        assertNull(CinemaBayEpisodeCounts(5, 8).remaining)
    }
    @Test fun unavailableEpisodesAreNotCountedWhenFileAvailabilityIsProvided() {
        val available = episode("a", 1).copy(files = listOf(EpisodeFile(1)))
        assertEquals(CinemaBayEpisodeCounts(1, 0), Season("s", 1, episodeCount = 1)
            .cinemaBayEpisodeCounts(listOf(available, episode("upcoming", 2))))
    }
    @Test fun allRegularEpisodesSeenDoesNotDependOnUnwatchedSpecials() {
        val season = Season("s", 1, episodeCount = 1)
        val normal = season.cinemaBayEpisodeCounts(listOf(episode("a", 1, true)))!!
        assertEquals(0, cinemaBaySumEpisodeCounts(listOf(normal))?.remaining)
        assertNull(cinemaBaySumEpisodeCounts(listOf(CinemaBayEpisodeCounts(0, 0))))
        assertNull(Season("special", 0, episodeCount = 1).cinemaBayEpisodeCounts(listOf(episode("extra", 1, season = 0))))
    }
    @Test fun duplicateSeasonNumbersDoNotDoubleTheSeries() {
        assertEquals(1, SeasonsResponse(listOf(Season("a", 1, episodeCount = 2), Season("b", 1, episodeCount = 2))).cinemaBayRegularSeasons().size)
    }
    @Test fun absentRollupOrAmbiguousExtrasMustRefreshIndividualWatchStates() {
        val unknown = Season("s", 1, episodeCount = 6)
        val known = unknown.copy(userData = SeasonUserData(watchedCount = 1))
        assertFalse(cinemaBayCanReuseEpisodeCounts(unknown, CinemaBayEpisodeCounts(6, 1), false))
        assertFalse(cinemaBayCanReuseEpisodeCounts(known, CinemaBayEpisodeCounts(6, null), false))
        assertFalse(cinemaBayCanReuseEpisodeCounts(known, CinemaBayEpisodeCounts(6, 1), true))
        assertTrue(cinemaBayCanReuseEpisodeCounts(known, CinemaBayEpisodeCounts(6, 1), false))
    }
}
