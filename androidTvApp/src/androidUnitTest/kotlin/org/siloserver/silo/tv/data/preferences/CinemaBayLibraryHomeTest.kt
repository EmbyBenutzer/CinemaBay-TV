package org.siloserver.silo.tv.data.preferences

import org.junit.Test
import org.siloserver.silo.tv.ui.screens.home.normalizeTvHomeSections
import org.junit.Assert.*
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.AuthScopeSnapshot

class CinemaBayLibraryHomeTest {
    private fun row(id: String, items: List<SectionItem> = listOf(SectionItem("movie", "movie", "Film"))) =
        ResolvedSection(id, "recently_added", id, items = items)
    @Test fun librariesAreOptInAndEmptyLibrariesRemainConfigurable() {
        val lib = row(cinemaBayLibraryRowId(4))
        val empty = row(cinemaBayLibraryRowId(5), emptyList())
        val trend = row("trending_movies")
        val all = listOf(trend, lib, empty)
        assertEquals(listOf(trend), TvHomeSectionPreferences.arrange(all, TvHomeSectionLayout()))
        assertEquals(all, TvHomeSectionPreferences.arrange(all, TvHomeSectionLayout(), includingHidden = true, includingEmpty = true))
        assertEquals(listOf(trend, lib), TvHomeSectionPreferences.arrange(all, TvHomeSectionLayout(enabledOptionalSectionIds = setOf(lib.id))))
    }
    @Test fun libraryAndTrendVisibilityAndOrderStayIndependent() {
        val lib = row(cinemaBayLibraryRowId(4)); val trend = row("trending_movies"); val standard = row("continue_watching")
        val layout = TvHomeSectionLayout(orderedSectionIds = listOf(lib.id, standard.id, trend.id), hiddenSectionIds = setOf(trend.id), enabledOptionalSectionIds = setOf(lib.id))
        assertEquals(listOf(lib, standard), TvHomeSectionPreferences.arrange(listOf(trend, standard, lib), layout))
        assertEquals(listOf(lib, standard, trend), TvHomeSectionPreferences.arrange(listOf(trend, standard, lib), layout, includingHidden = true))
    }
    @Test fun recentEpisodesPromoteOneParentAndKeepSourceOrder() {
        val episode = SectionItem("episode-new", "episode", "New episode", seriesId = "series", seriesTitle = "Show", seriesPosterUrl = "series.jpg", posterUrl = "spoiler.jpg", seasonNumber = 3, episodeNumber = 8, year = 2026)
        val other = SectionItem("other", "series", "Other")
        val result = cinemaBayLibraryCards(listOf(episode, other, episode.copy(contentId = "episode-old"), episode.copy(seriesId = null)))
        assertEquals(listOf("series", "other"), result.map { it.contentId })
        assertEquals("Show", result.first().title); assertEquals("series.jpg", result.first().posterUrl)
        assertEquals("series", result.first().type); assertEquals(0, result.first().year)
        assertNull(result.first().episodeNumber); assertNull(result.first().seasonNumber)
    }
    @Test fun onlyValidVideoItemsAreUsedAndRowsRemainBounded() {
        assertTrue(cinemaBayLibraryCards(listOf(SectionItem("book", "ebook", "Book"), SectionItem("orphan", "episode", "Orphan"))).isEmpty())
        assertEquals(20, cinemaBayLibraryCards((1..30).map { SectionItem("$it", "movie", "Movie") }).size)
    }
    @Test fun recentlyReleasedAndTrendingAreNotConfusedWithNewLibraryContents() {
        assertTrue(cinemaBayRecentSection(row("recent")))
        assertFalse(cinemaBayRecentSection(row("trending").copy(sectionType = "trending")))
        assertFalse(cinemaBayRecentSection(row("release").copy(sectionType = "recently_released")))
    }
    @Test fun oldAccountProfileServerAndCredentialsNeverShareRows() {
        val owner = AuthScopeSnapshot("server", "profile", "https://example.invalid", null, accountId = "account", identityGeneration = 5, credentialEpoch = 3)
        assertTrue(cinemaBaySameLibraryViewer(owner, owner))
        assertFalse(cinemaBaySameLibraryViewer(null, owner))
        listOf(owner.copy(profileId = "other"), owner.copy(accountId = "other"), owner.copy(serverId = "other"),
            owner.copy(serverUrl = "https://other.invalid"), owner.copy(identityGeneration = 6), owner.copy(credentialEpoch = 4))
            .forEach { assertFalse(cinemaBaySameLibraryViewer(owner, it)) }
    }
    @Test fun newEpisodeMovesAnOlderSeriesAheadOfANewlyAddedSeries() {
        val older = SectionItem("old-show", "series", "Older show", year = 1995)
        val new = SectionItem("new-show", "series", "New show", year = 2026)
        val episode = SectionItem("latest-episode", "episode", "Episode", seriesId = "old-show", seriesTitle = "Older show")
        val ranked = cinemaBayRankLibraryUpdates(listOf(older to "2020-01-01T00:00:00Z", new to "2026-11-16T00:00:00Z"), listOf(episode to "2026-11-17T00:00:00Z"))!!
        assertEquals(listOf("old-show", "new-show"), ranked.map { it.contentId })
        assertEquals("Older show", ranked.first().title)
    }
    @Test fun missingDatesAndIgnoredEpisodeFiltersCannotInventAnUpdateOrder() {
        val show = SectionItem("show", "series", "Show")
        val episode = SectionItem("episode", "episode", "Episode", seriesId = "show")
        assertNull(cinemaBayRankLibraryUpdates(listOf(show to null), listOf(episode to "2026-11-17T00:00:00Z")))
        assertNull(cinemaBayRankLibraryUpdates(listOf(show to "2026-11-16T00:00:00Z"), listOf(show to "2026-11-17T00:00:00Z")))
    }
    @Test fun separateSeriesTrendsAreOptInAndKeepExistingTrendsUnchanged() {
        val movie = SectionItem("movie", "movie", "Movie")
        val show = SectionItem("show", "series", "Show")
        val original = ResolvedSection("trends", "trending_discover", "Trends", items = listOf(movie, show))
        val rows = listOf(original).normalizeTvHomeSections()
        assertEquals(2, rows.size); assertEquals(original, rows.first())
        assertEquals(listOf(show), rows.last().items)
        assertEquals(listOf(original), TvHomeSectionPreferences.arrange(rows, TvHomeSectionLayout()))
        assertEquals(rows, TvHomeSectionPreferences.arrange(rows, TvHomeSectionLayout(enabledOptionalSectionIds = setOf(rows.last().id))))
    }
}
