package org.siloserver.silo.tv.highlights

import java.io.File
import java.time.LocalDate
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import java.io.IOException

class CinemaBayHighlightsTest {
    @Test fun parsesDatesWithoutInventingMonthOnlyDays() {
        assertEquals(LocalDate.of(2026,12,17),highlightDate("Donnerstag, 17. Dezember 2026 Netflix"))
        assertNull(highlightDate("Dezember 2026"))
        assertNull(highlightDate("31. Februar 2026"))
    }
    @Test fun mediaAllowlistBlocksAdsRedirectTargetsAndUntrustedSchemes() {
        assertTrue(approvedHighlightVideo("https://de.vid.web.acsta.net/nmedia/49/trailer.mp4?source=www"))
        assertTrue(approvedHighlightVideo("https://sjme.de/v/trailer_de.mp4"))
        for(url in listOf("https://doubleclick.net/trailer.mp4","http://sjme.de/v/a.mp4","https://sjme.de.evil.test/v/a.mp4",
            "https://sjme.de:444/v/a.mp4","https://user@sjme.de/v/a.mp4","file:///v/a.mp4","https://sjme.de/v/page.html"))assertFalse(approvedHighlightVideo(url),url)
    }
    @Test fun metadataRequestsAreLimitedToKnownPublicPages() {
        assertTrue(approvedHighlightPage("https://www.filmstarts.de/filme-vorschau/de/week-2026-12-17/"))
        assertTrue(approvedHighlightPage("https://www.serienjunkies.de/video/my-series-trailer_de-123.html"))
        assertFalse(approvedHighlightPage("https://www.filmstarts.de/account/"))
        assertFalse(approvedHighlightPage("https://www.serienjunkies.de.evil.test/docs/serienplaner.html"))
        assertFalse(approvedHighlightPage("https://www.serienjunkies.de:444/docs/serienplaner.html"))
    }
    @Test fun moviesKeepTitleDateAndTheirOwnDetailIdentity() {
        val items=parseHighlightMovies("""<div class="entity-card"><a class="meta-title-link" href="/kritiken/123.html">Ein Film</a><span class="date">17. Dezember 2026</span><img class="thumbnail-img" data-src="https://de.web.img3.acsta.net/img/poster.jpg"></div>""")
        assertEquals(1,items.size);assertEquals("Ein Film",items.single().title)
        assertEquals(LocalDate.of(2026,12,17),items.single().date)
        assertEquals(HighlightKind.Movie,items.single().kind)
        assertEquals("https://www.filmstarts.de/kritiken/123.html",items.single().page)
    }
    @Test fun seriesRetainSeasonAndProviderButExcludeUncertainDates() {
        val html="""<div class="sjf-show-planner-item"><div class="sjf-show-planner-title"><a href="/example-series/">Eine Serie</a> Staffel 3</div><div class="sjf-show-planner-date">Freitag, 20. November 2026</div><div class="sjf-show-planner-logo"><img alt="Disney+"></div></div>"""
        val items=parseHighlightSeries(html,LocalDate.of(2026,11,12))
        assertEquals("Staffel 3",items.single().season);assertEquals("Disney+",items.single().provider)
        assertTrue(parseHighlightSeries(html.replace("Freitag, 20. November 2026","November 2026"),LocalDate.of(2026,11,12)).isEmpty())
    }
    @Test fun missingPageStructureIsAnErrorInsteadOfFalseEmptySuccess() {
        assertFailsWith<IllegalArgumentException>{parseHighlightMovies("<html>blocked</html>")}
        assertFailsWith<IllegalArgumentException>{parseHighlightSeries("<html>blocked</html>",LocalDate.of(2026,11,12))}
    }
    @Test fun trailerResolutionNeverUsesRecommendationsOfAnotherMovie() {
        val item=CinemaBayHighlight("123","Film",LocalDate.of(2026,12,17),HighlightKind.Movie,null,"https://www.filmstarts.de/kritiken/123.html","Kino")
        val links=highlightTrailerPages("""<a href="/kritiken/999/trailer/222.html">Fremder Film</a><a href="/kritiken/123/trailer/111.html">Trailer</a>""",item)
        assertEquals(listOf("https://www.filmstarts.de/kritiken/123/trailer/111.html"),links)
    }
    @Test fun originalAndSubtitleVersionsAreNeverSelectedAsGerman() {
        val page="https://www.filmstarts.de/kritiken/123/trailer/111.html"
        val html="""<div class="media-info-title">Film Trailer DF</div><script type="application/ld+json">{"contentUrl":"https:\/\/de.vid.web.acsta.net\/nmedia\/111_hd_013.mp4"}</script>"""
        assertNotNull(parseHighlightTrailer(html,page))
        assertNull(parseHighlightTrailer(html.replace("Trailer DF","Trailer OV"),page))
        assertNull(parseHighlightTrailer(html.replace("Trailer DF","Trailer OmU"),page))
        assertNull(parseHighlightTrailer(html.replace("de.vid.web.acsta.net","ads.example.com"),page))
    }
    @Test fun genreAliasesProduceStableGermanFilters() {
        assertEquals(listOf("Abenteuer","Komödie","Science-Fiction"),highlightGenres(listOf("Adventure","Comedy","science fiction","Science-Fiction"," ")))
    }
    @Test fun genreFilteringNeverInventsMissingCategories() {
        val known=CinemaBayHighlight("a","A",LocalDate.of(2026,11,12),HighlightKind.Movie,null,"https://www.filmstarts.de/kritiken/1.html","Kino",genres=listOf("Drama","Fantasy"))
        assertTrue(highlightHasGenre(known,""));assertTrue(highlightHasGenre(known,"Fantasy"));assertFalse(highlightHasGenre(known,"Action"))
        assertFalse(highlightHasGenre(known,"Genre unbekannt"));assertTrue(highlightHasGenre(known.copy(genres=emptyList()),"Genre unbekannt"))
    }
    @Test fun metadataUsesTheMatchingSeriesObjectFromTheGraph() {
        val html="""<script type="application/ld+json">{"@graph":[{"@type":"Organization","description":"Not a synopsis"},{"@type":"TVSeries","url":"https://www.serienjunkies.de/example/","genre":["Mystery","Adventure"],"description":"Ein Junge entdeckt ein Geheimnis."}]}</script>"""
        val data=parseHighlightMetadata(html,"https://www.serienjunkies.de/example/",HighlightKind.Series)
        assertEquals(listOf("Abenteuer","Mystery"),data.genres);assertEquals("Ein Junge entdeckt ein Geheimnis.",data.summary)
    }
    @Test fun metadataOfAnotherTitleOrMediaTypeIsRejected() {
        val html="""<script type="application/ld+json">{"@type":"TVSeries","url":"https://www.serienjunkies.de/other/","genre":"Drama","description":"Wrong title"}</script>"""
        assertEquals(HighlightMetadata(emptyList(),""),parseHighlightMetadata(html,"https://www.serienjunkies.de/example/",HighlightKind.Series))
        assertEquals(HighlightMetadata(emptyList(),""),parseHighlightMetadata(html,"https://www.serienjunkies.de/other/",HighlightKind.Movie))
    }
    @Test fun missingMetadataRemainsExplicitlyUnknown() {
        assertEquals(HighlightMetadata(emptyList(),""),parseHighlightMetadata("<html>unknown</html>","https://www.serienjunkies.de/example/",HighlightKind.Series))
    }
    @Test fun summariesArePlainShortTextWithoutMarkup() {
        assertEquals("Ein Film & eine Serie.",highlightSummary("<p>Ein <b>Film</b> &amp; eine Serie.</p>"))
        val long=highlightSummary("Eine lange Geschichte. ".repeat(40));assertTrue(long.length<=240);assertTrue(long.endsWith("…"))
    }
    @Test fun previewGenresDoNotAccidentallyContainCastOrDirectors() {
        val html="""<div class="entity-card"><a class="meta-title-link" href="/kritiken/123.html">Film</a><span class="date">17. Dezember 2026</span><div class="meta-body-info"><span class="dark-grey-link">Drama</span></div><div class="meta-body-actor"><span class="dark-grey-link">An Actor</span></div></div>"""
        assertEquals(listOf("Drama"),parseHighlightMovies(html).single().genres)
    }
    private fun trailerItem()=CinemaBayHighlight("123","Film",LocalDate.of(2026,11,12),HighlightKind.Movie,null,"https://www.filmstarts.de/kritiken/123.html","Kino")
    private val trailerDetail="""<a href="/kritiken/123/trailer/456.html">Trailer</a>"""
    private val germanTrailer="""<div class="media-info-title">Film Trailer DF</div><script type="application/ld+json">{"contentUrl":"https://de.vid.web.acsta.net/nmedia/456_hd_013.mp4"}</script>"""
    @Test fun availabilityProbeReadsMetadataWithoutRequestingTheVideo()=runBlocking<Unit> {
        val calls=mutableListOf<String>()
        val data=resolveHighlightTrailer(trailerItem()){url->calls+=url;if(url==trailerItem().page)trailerDetail else germanTrailer}
        assertTrue(approvedHighlightVideo(data.url));assertEquals(2,calls.size)
        assertTrue(calls.all(::approvedHighlightPage));assertFalse(calls.contains(data.url))
    }
    @Test fun onlyEnglishTrailersAreMarkedUnavailable()=runBlocking<Unit> {
        assertFailsWith<HighlightTrailerUnavailableException>{resolveHighlightTrailer(trailerItem()){url->
            if(url==trailerItem().page)trailerDetail else germanTrailer.replace("Trailer DF","Trailer OV")
        }}
    }
    @Test fun missingPublishedVideoPageIsUnavailableRatherThanAPlayerAttempt()=runBlocking<Unit> {
        assertFailsWith<HighlightTrailerUnavailableException>{resolveHighlightTrailer(trailerItem()){url->
            if(url==trailerItem().page)trailerDetail else throw HighlightHttpException(404)
        }}
    }
    @Test fun serverFailureIsNeverReportedAsNoGermanTrailer()=runBlocking<Unit> {
        val error=assertFailsWith<HighlightHttpException>{resolveHighlightTrailer(trailerItem()){throw HighlightHttpException(503)}}
        assertEquals(503,error.status)
    }
    @Test fun networkFailureDuringVideoLookupRemainsRetryable()=runBlocking<Unit> {
        val error=assertFailsWith<IOException>{resolveHighlightTrailer(trailerItem()){url->
            if(url==trailerItem().page)trailerDetail else throw IOException("offline")
        }}
        assertFalse(error is HighlightTrailerUnavailableException)
    }
    @Test fun anotherPublishedGermanTrailerCanRecoverFromALookupFailure()=runBlocking<Unit> {
        val detail=trailerDetail+"""<a href="/kritiken/123/trailer/457.html">Trailer 2</a>"""
        val data=resolveHighlightTrailer(trailerItem()){url->when {
            url==trailerItem().page->detail
            url.endsWith("/456.html")->throw IOException("temporary")
            else->germanTrailer.replace("456_hd_013","457_hd_013")
        }}
        assertTrue(approvedHighlightVideo(data.url))
    }
    @Test fun canceledProbesAreNotConvertedIntoUnavailableLabels()=runBlocking<Unit> {
        assertFailsWith<CancellationException>{resolveHighlightTrailer(trailerItem()){throw CancellationException("canceled")}}
    }
    @Test fun fullHdIsPreferredWithItsPublished720Fallback() {
        val hd="https://de.vid.web.acsta.net/nmedia/456_hd_013.mp4"
        val full="https://de.vid.web.acsta.net/nmedia/456_full.mp4"
        val selected=selectHighlightVideo("Trailer",listOf(HighlightVideoCandidate(hd,720),HighlightVideoCandidate(full,1080)))!!
        assertEquals(full,selected.url);assertEquals(hd,selected.fallbackUrl);assertEquals(1080,selected.preferredHeight)
    }
    @Test fun a720OnlyTrailerDoesNotInventAFullHdAddress() {
        val hd="https://de.vid.web.acsta.net/nmedia/456_hd_013.mp4"
        val selected=selectHighlightVideo("Trailer",listOf(HighlightVideoCandidate(hd)))!!
        assertEquals(hd,selected.url);assertNull(selected.fallbackUrl);assertEquals(720,selected.preferredHeight)
    }
    @Test fun untrustedFullHdIsIgnoredAnd4kDoesNotOverride1080() {
        val hd="https://de.vid.web.acsta.net/nmedia/456_720.mp4"
        val full="https://de.vid.web.acsta.net/nmedia/456_1080.mp4"
        val selected=selectHighlightVideo("Trailer",listOf(HighlightVideoCandidate(hd,720),HighlightVideoCandidate("https://ads.example.com/a.mp4",1080),HighlightVideoCandidate(full,1080),HighlightVideoCandidate("https://de.vid.web.acsta.net/nmedia/456_2160.mp4",2160)))!!
        assertEquals(full,selected.url);assertEquals(hd,selected.fallbackUrl)
    }
    @Test fun publishedPlayerSourcesAreMatchedToTheExactTrailer() {
        val html="""<div class="media-info-title">Film Trailer DF</div><div data-model='{"videos":[{"id":456,"sources":{"hd":"https://de.vid.web.acsta.net/nmedia/456_hd_013.mp4","1080":"https://de.vid.web.acsta.net/nmedia/456_full.mp4"}},{"id":999,"sources":{"1080":"https://de.vid.web.acsta.net/nmedia/999_full.mp4"}}]}'></div>"""
        val selected=parseHighlightTrailer(html,"https://www.filmstarts.de/kritiken/123/trailer/456.html")!!
        assertEquals("https://de.vid.web.acsta.net/nmedia/456_full.mp4",selected.url)
        assertEquals("https://de.vid.web.acsta.net/nmedia/456_hd_013.mp4",selected.fallbackUrl)
        assertNull(parseHighlightTrailer(html.replace("Trailer DF","Trailer OV"),"https://www.filmstarts.de/kritiken/123/trailer/456.html"))
    }
    @Test fun videoSourceHeightChoosesFullHdForGermanSeries() {
        val html="""<h1>Serientrailer</h1><video><source src="https://sjme.de/v/example_de_720.mp4" data-height="720"><source src="https://sjme.de/v/example_de_1080.mp4" data-height="1080"></video>"""
        val selected=parseHighlightTrailer(html,"https://www.serienjunkies.de/video/example-trailer_de-123.html")!!
        assertEquals(1080,selected.preferredHeight);assertTrue(selected.url.endsWith("1080.mp4"));assertTrue(selected.fallbackUrl!!.endsWith("720.mp4"))
    }
    @Test fun widescreenFullHdUsesItsPublished1920PixelWidth() {
        val html="""<div class="media-info-title">Film Trailer DF</div><script type="application/ld+json">{"@type":"VideoObject","name":"Film Trailer DF","encoding":[{"contentUrl":"https://de.vid.web.acsta.net/nmedia/456_main.mp4","width":1920,"height":800},{"contentUrl":"https://de.vid.web.acsta.net/nmedia/456_hd_013.mp4","width":1280,"height":720}]}</script>"""
        val selected=parseHighlightTrailer(html,"https://www.filmstarts.de/kritiken/123/trailer/456.html")!!
        assertEquals(1080,selected.preferredHeight);assertNotNull(selected.fallbackUrl)
    }
    @Test fun playbackFallbackCannotLoopOrUseAnAdvertisingHost() {
        val full="https://de.vid.web.acsta.net/nmedia/456_1080.mp4";val hd="https://de.vid.web.acsta.net/nmedia/456_720.mp4"
        val source=HighlightTrailer(full,"Trailer",hd,1080)
        assertEquals(hd,highlightVideoFallback(source,full));assertNull(highlightVideoFallback(source,hd))
        assertNull(highlightVideoFallback(source.copy(fallbackUrl="https://ads.example.com/clip.mp4"),full))
        assertNull(highlightVideoFallback(source.copy(fallbackUrl=full),full))
    }
    @Test fun liveSourceFixturesAreParsedWhenAvailable() {
        val path=System.getenv("CINEMABAY_HIGHLIGHTS_FIXTURES") ?: return
        val dir=File(path)
        val movies=parseHighlightMovies(File(dir,"highlights-filmstarts.html").readText())
        val series=parseHighlightSeries(File(dir,"highlights-serienplaner.html").readText(),LocalDate.of(2026,11,12))
        println("Live fixtures: ${movies.size} films / ${series.size} series starts parsed")
        assertTrue(movies.size>=10);assertTrue(series.size>=10)
        assertTrue(series.any{it.title.contains("Percy Jackson")})
        val item=movies.first{it.page.endsWith("/1000034007.html")}
        assertTrue(highlightTrailerPages(File(dir,"highlights-film-detail.html").readText(),item).any{it.endsWith("/20644807.html")})
        val trailer=parseHighlightTrailer(File(dir,"highlights-film-video.html").readText(),"https://www.filmstarts.de/kritiken/1000034007/trailer/20644807.html")
        assertNotNull(trailer);assertTrue(trailer.title.contains("DF"));assertEquals(720,trailer.preferredHeight)
        assertTrue(movies.first{it.page.endsWith("/1000034007.html")}.genres.contains("Abenteuer"))
        val filmMetadata=parseHighlightMetadata(File(dir,"highlights-film-detail.html").readText(),item.page,HighlightKind.Movie)
        assertTrue(filmMetadata.genres.contains("Fantasy"));assertTrue(filmMetadata.summary.isNotBlank())
        val seriesMetadata=parseHighlightMetadata(File(dir,"highlights-series-detail.html").readText(),"https://www.serienjunkies.de/percy-jackson-and-the-olympians/",HighlightKind.Series)
        assertTrue(seriesMetadata.genres.contains("Mystery"));assertTrue(seriesMetadata.summary.isNotBlank())
    }
}
