package org.siloserver.silo.tv.ui.components

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.ItemVideo
import java.io.IOException

class CinemaBayTrailerSourcesTest {
    private fun video(id: String, language: String?) = ItemVideo("trailer", "youtube", id, language = language, isOfficial = true)
    private fun detail(vararg videos: ItemVideo, language: String? = "en") = ItemDetail(contentId = "film", type = "movie", title = "Film", originalLanguage = language, videos = videos.toList())
    @Test fun libraryGermanAvoidsUnnecessaryExternalRequests() = runBlocking<Unit> {
        val result = resolveCinemaBayCardTrailer(detail(video("GERMAN12345", "de-DE"))) { error("Unnecessary request") }
        assertEquals("GERMAN12345", result?.videoId); assertEquals(false, result?.original)
    }
    @Test fun extraGermanBeatsOriginalTrailer() = runBlocking<Unit> {
        val languages = mutableListOf<String>()
        val result = resolveCinemaBayCardTrailer(detail(video("ORIGINAL123", "en"))) { languages += it; listOf("EXTRADE1234") }
        assertEquals("EXTRADE1234", result?.videoId); assertEquals(false, result?.original); assertEquals(listOf("de"), languages)
    }
    @Test fun originalIsUsedOnlyAfterGermanSourceWasExhausted() = runBlocking<Unit> {
        val languages = mutableListOf<String>()
        val result = resolveCinemaBayCardTrailer(detail(video("ORIGINAL123", "en"))) { languages += it; emptyList() }
        assertEquals("ORIGINAL123", result?.videoId); assertEquals(true, result?.original); assertEquals(listOf("de"), languages)
    }
    @Test fun sourceOutageDoesNotSuppressAvailableOriginalTrailer() = runBlocking<Unit> {
        val selected = resolveCinemaBayCardTrailer(detail(video("ORIGINAL123", "en"))) { throw IOException("offline") }
        assertEquals("ORIGINAL123", selected?.videoId); assertTrue(selected!!.original)
    }
    @Test fun totalSourceOutageRemainsAnErrorWhenNoTrailerExists() = runBlocking<Unit> {
        try { resolveCinemaBayCardTrailer(detail()) { throw IOException("offline") }; fail("Expected failure") }
        catch (_: IOException) { }
    }
    @Test fun rejectedGermanTrailerCanUseAnotherGermanSource() = runBlocking<Unit> {
        val result = resolveCinemaBayCardTrailer(detail(video("GERMAN12345", "de"), video("ORIGINAL123", "en")), setOf("GERMAN12345")) { listOf("EXTRADE1234") }
        assertEquals("EXTRADE1234", result?.videoId); assertEquals(false, result?.original)
    }
    @Test fun unknownOriginalLanguageDoesNotSelectAnArbitraryDub() = runBlocking<Unit> {
        assertNull(resolveCinemaBayCardTrailer(detail(video("ORIGINAL123", "en"), language = null)) { emptyList() })
    }
    private val json = """{"type":"movie","tmdb_id":123,"language":"de","trailer":{"youtube_video_id":"GERMAN12345","language":"de","categories":["Trailer"]},"videos":[{"youtube_video_id":"ORIGINAL123","language":"en","categories":["Trailer"]}],"recommendations":[{"trailer":{"youtube_video_id":"EXTRADE1234","language":"de","categories":["Trailer"]}}]}"""
    @Test fun providerUsesOnlyMatchingIdentityLanguageAndTrailerCategory() {
        assertEquals(listOf("GERMAN12345"), cinemaBayKinoCheckTrailers(json, "123", null, false, "de"))
        assertTrue(cinemaBayKinoCheckTrailers(json, "124", null, false, "de").isEmpty())
        assertTrue(cinemaBayKinoCheckTrailers(json, "123", null, true, "de").isEmpty())
        assertTrue(cinemaBayKinoCheckTrailers(json.replace("\"Trailer\"", "\"Clip\""), "123", null, false, "de").isEmpty())
    }
    @Test fun originalPlayerRequestsGermanSubtitlesAndGermanPlayerKeepsThemOff() {
        assertTrue(cinemaBayCardTrailerHtml("ORIGINAL123", true).contains("cc_load_policy:1,cc_lang_pref:'de'"))
        assertTrue(cinemaBayCardTrailerHtml("ORIGINAL123", true).contains("if(true){e.target.mute();}"))
        assertTrue(cinemaBayCardTrailerHtml("ORIGINAL123", true).contains("!announced&&captionsReady"))
        assertTrue(cinemaBayCardTrailerHtml("GERMAN12345", false).contains("cc_load_policy:0,cc_lang_pref:'de'"))
    }

    @Test fun alternativeGermanSourceSurvivesKinoCheckOutage() = runBlocking<Unit> {
        val selected = resolveCinemaBayCardTrailer(detail(video("ORIGINAL123", "en")),
            germanAlternative = { CinemaBayTrailerChoice("direct", false, "Filmstarts", "https://example.invalid/movie.mp4") }) { throw IOException("offline") }
        assertEquals("Filmstarts", selected?.source); assertFalse(selected!!.original)
    }
    @Test fun episodesUseOnlyTheirActualParentSeries() {
        assertEquals("parent", cinemaBayTrailerContentId("episode", "episode", "parent"))
        assertNull(cinemaBayTrailerContentId("episode", "episode", null))
        assertEquals("movie", cinemaBayTrailerContentId("movie", "movie", "parent"))
        assertNull(cinemaBayTrailerContentId("audio", "audio", "parent"))
    }
    @Test fun autocompleteRejectsPaidPromotionsRemakesAndWrongMediaTypes() {
        val fixture = """{"results":[
            {"entity_type":"movie","entity_id":"1","label":"Film","sponsored":true,"data":{"year":"2026","default_video":111}},
            {"entity_type":"series","entity_id":"2","label":"Film","data":{"year":"2026"}},
            {"entity_type":"movie","entity_id":"3","label":"Film","data":{"year":"1990"}},
            {"entity_type":"movie","entity_id":"4","label":"Film 2","data":{"year":"2026"}},
            {"entity_type":"movie","entity_id":"5","label":"Film","data":{"year":"2026","default_video":123}}
        ]}"""
        assertEquals("5", cinemaBayFilmstartsMatch(fixture, detail().copy(year = 2026))?.id)
        assertNull(cinemaBayFilmstartsMatch(fixture.replace("\"entity_id\":\"5\"", "\"entity_id\":\"bad\""), detail().copy(year = 2026)))
    }
    @Test fun ambiguousUnknownYearDoesNotPickARemake() {
        val json = """{"results":[{"entity_type":"movie","entity_id":"1","label":"Film","data":{"year":"1990"}},{"entity_type":"movie","entity_id":"2","label":"Film","data":{"year":"2026"}}]}"""
        assertNull(cinemaBayFilmstartsMatch(json, detail()))
    }
    @Test fun realPublicPagesResolveGermanMovieAndSeriesButRejectOriginalOnly() {
        val root = System.getenv("CINEMABAY_HIGHLIGHTS_FIXTURES") ?: run { org.junit.Assume.assumeTrue("Optional live source fixtures not configured", false); return }
        fun file(name: String) = java.io.File(root, name).readText()
        val mongoose = ItemDetail("m", "movie", title = "The Mongoose", year = 2026)
        val match = cinemaBayFilmstartsMatch(file("filmstarts-autocomplete-the-mongoose.json"), mongoose)!!
        assertEquals("1000003650", match.id)
        assertNotNull(org.siloserver.silo.tv.highlights.parseHighlightTrailer(file("filmstarts-mongoose-video.html"), match.videoPage(match.videoId!!)))
        val series = ItemDetail("s", "series", title = "Stranger Things", year = 2016)
        val show = cinemaBayFilmstartsMatch(file("filmstarts-autocomplete-stranger-things.json"), series)!!
        assertEquals("19156", show.id)
        assertTrue(org.siloserver.silo.tv.highlights.parseHighlightTrailer(file("filmstarts-stranger-video.html"), show.videoPage(show.videoId!!))!!.url.contains("20631751_"))
        val allen = ItemDetail("a", "movie", title = "Allen", year = 2026)
        val original = cinemaBayFilmstartsMatch(file("filmstarts-autocomplete-allen.json"), allen)!!
        assertNull(org.siloserver.silo.tv.highlights.parseHighlightTrailer(file("filmstarts-allen-video-page.html"), original.videoPage(original.videoId!!)))
    }
    @Test fun filmstartsRelatedLinksCannotSwitchToOtherTitles() {
        val match = CinemaBayFilmstartsMatch("123", true, "456")
        val pages = cinemaBayFilmstartsVideoPages("""<a href="/serien/123/videos/456/">Trailer</a><a href="/serien/124/videos/789/">Other</a><a href="https://evil.invalid/serien/123/videos/888/">Evil</a>""", match)
        assertEquals(listOf("https://www.filmstarts.de/serien/123/videos/456/"), pages)
    }

    @Test fun subtitledOriginalNeverBeatsGermanAndWorksWithMissingOriginalLanguage() = runBlocking<Unit> {
        val omu = CinemaBayTrailerChoice("omu", true, "Filmstarts", germanCaptionsEmbedded = true)
        val selected = resolveCinemaBayCardTrailer(detail(language = null), subtitledOriginal = { omu }) { emptyList() }
        assertEquals("omu", selected?.videoId)
        val german = resolveCinemaBayCardTrailer(detail(video("GERMAN12345", "de")), subtitledOriginal = { error("German has priority") }) { emptyList() }
        assertEquals("GERMAN12345", german?.videoId)
        assertNull(resolveCinemaBayCardTrailer(detail(language = null), subtitledOriginal = { omu.copy(germanCaptionsEmbedded = false) }) { emptyList() })
    }
    @Test fun originalSubtitledParsingIsExplicitOptInAndRejectsEnglishCaptions() {
        val root = System.getenv("CINEMABAY_HIGHLIGHTS_FIXTURES") ?: run { org.junit.Assume.assumeTrue("Optional live source fixtures not configured", false); return }
        val html = java.io.File(root, "filmstarts-mongoose-video.html").readText().replace("Trailer DF", "Trailer OmU")
        val page = "https://www.filmstarts.de/kritiken/1000003650/trailer/20645914.html"
        assertNull(org.siloserver.silo.tv.highlights.parseHighlightTrailer(html, page))
        assertNotNull(org.siloserver.silo.tv.highlights.parseHighlightTrailer(html, page, true))
        assertNull(org.siloserver.silo.tv.highlights.parseHighlightTrailer(html.replace("Trailer OmU", "Trailer OmU English subtitles"), page, true))
    }
}
