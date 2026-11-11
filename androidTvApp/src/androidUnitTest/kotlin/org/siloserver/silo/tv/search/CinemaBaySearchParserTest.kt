package org.siloserver.silo.tv.search

import org.junit.Test
import org.siloserver.silo.model.catalog.BrowseItem
import kotlin.test.*

class CinemaBaySearchParserTest {
    @Test fun combinesNaturalGermanFilters() {
        val p = CinemaBayGermanQueryParser.parse("Zeige mir ungesehene Thriller ab 2015 unter 120 Minuten mit Bewertung mindestens 7,5")
        assertEquals(setOf("Thriller"), p.filters.genres); assertEquals(2015,p.filters.yearMin)
        assertEquals(120,p.filters.maxMinutes); assertEquals(7.5,p.filters.minRating); assertTrue(p.filters.unwatched)
        assertEquals("",p.text)
    }
    @Test fun protectsShortAndQuotedTitles() {
        assertEquals("Es",CinemaBayGermanQueryParser.parse("Es").text)
        val q=CinemaBayGermanQueryParser.parse("\"Action Jackson\" aus 1988")
        assertEquals("Action Jackson",q.text);assertTrue(q.filters.genres.isEmpty());assertEquals(1988,q.filters.yearMin)
    }
    @Test fun decadesRangesAndExclusiveYears() {
        assertEquals(1990,CinemaBayGermanQueryParser.parse("Filme aus den 90ern").filters.yearMin)
        assertEquals(1999,CinemaBayGermanQueryParser.parse("Filme aus den 90ern").filters.yearMax)
        assertEquals(2001,CinemaBayGermanQueryParser.parse("Filme nach 2000").filters.yearMin)
        assertEquals(1999,CinemaBayGermanQueryParser.parse("Filme vor 2000").filters.yearMax)
        assertEquals(2015,CinemaBayGermanQueryParser.parse("Filme zwischen 2010 und 2015").filters.yearMax)
    }
    @Test fun timeAndNegationAndGenreOr() {
        val p=CinemaBayGermanQueryParser.parse("Action oder Komödie ohne Horror unter zwei Stunden")
        assertTrue(p.filters.anyGenre);assertEquals(120,p.filters.maxMinutes);assertEquals(setOf("Horror"),p.filters.excludedGenres)
        assertEquals(setOf("Action","Komödie"),p.filters.genres)
    }
    @Test fun languageIsNotConfusedWithDubbing() {
        assertEquals("de",CinemaBayGermanQueryParser.parse("deutsche Filme").filters.originalLanguage)
        val dubbed=CinemaBayGermanQueryParser.parse("Filme auf Deutsch")
        assertEquals("de",dubbed.filters.audioLanguage);assertNull(dubbed.filters.originalLanguage)
    }
    @Test fun personAndSimilarityUseRealSources() {
        assertEquals("tom hanks",CinemaBayGermanQueryParser.parse("Filme mit Tom Hanks").person)
        assertEquals("matrix",CinemaBayGermanQueryParser.parse("Filme wie Matrix").similarTo)
    }
    @Test fun manualFiltersNarrowInsteadOfReplacingRanges() {
        val a=CinemaBaySearchFilters(yearMin=2000,yearMax=2020,maxMinutes=120,minRating=7.0)
        val b=CinemaBaySearchFilters(yearMin=2010,yearMax=2025,maxMinutes=90,minRating=8.0)
        val f=a.and(b);assertEquals(2010,f.yearMin);assertEquals(2020,f.yearMax);assertEquals(90,f.maxMinutes);assertEquals(8.0,f.minRating)
    }
    @Test fun normalizationAndVocabularySupportRealTypos() {
        assertEquals("die grosse flucht",CinemaBaySearchText.normalize("Die große Flucht!"))
        val v=CinemaBayVocabulary();listOf("harry","potter","matrix","interstellar").forEach(v::add)
        assertContains(v.alternatives("poter"),"potter");assertContains(v.alternatives("matirx"),"matrix")
        assertContains(v.alternatives("interstelar"),"interstellar")
        assertFalse(v.alternatives("zzz").contains("matrix"))
    }
    @Test fun crowdedVocabularyKeepsExpectedTitleCorrections() {
        val vocabulary=CinemaBayVocabulary()
        listOf("hard","hare","hark","harm","harp","hart","hazy","mary","harry",
            "pater","poker","power","poet","poser","poser","peter","potter").forEach(vocabulary::add)
        assertContains(vocabulary.alternatives("hary"),"harry")
        assertContains(vocabulary.alternatives("poter"),"potter")
        assertTrue(cinemaBaySearchScore(BrowseItem("1","movie","Harry Potter und der Feuerkelch"),"Hary Poter") >
            cinemaBaySearchScore(BrowseItem("2","movie","Mary & George"),"Hary Poter"))
    }
    @Test fun exactTitlesRankAbovePartialAndFuzzy() {
        fun item(t:String)=BrowseItem(t,"movie",t)
        assertTrue(cinemaBaySearchScore(item("Matrix"),"Matrix") > cinemaBaySearchScore(item("Matrix Reloaded"),"Matrix"))
        assertTrue(cinemaBaySearchScore(item("Harry Potter"),"Harry Poter") > cinemaBaySearchScore(item("Harry Brown"),"Harry Poter"))
    }
    @Test fun newTitlesUseCurrentYearAndAgeIsConservative() {
        assertEquals(2025,CinemaBayGermanQueryParser.parse("neue Serien",2026).filters.yearMin)
        assertEquals(12,cinemaBayAge("FSK 12"));assertNull(cinemaBayAge("unbekannt"))
    }
    @Test fun emptyAndHostileInputRemainData() {
        assertEquals("",CinemaBayGermanQueryParser.parse("").text)
        assertEquals("drop table documents",CinemaBaySearchText.normalize("\"; DROP TABLE documents; --"))
    }
    @Test fun preparedAiContractHasGeneratedSerializer() {
        val value=CinemaBayGermanQueryParser.parse("Thriller ab 2020")
        val encoded=org.siloserver.silo.network.SiloJson.encodeToString(CinemaBaySearchIntent.serializer(),value)
        assertEquals(value,org.siloserver.silo.network.SiloJson.decodeFromString(CinemaBaySearchIntent.serializer(),encoded))
    }
    @Test fun manualOrGroupsAndNaturalGroupsRemainSeparate() {
        val manual=CinemaBaySearchFilters(genres=setOf("Action","Komödie"),anyGenre=true)
        assertTrue(CinemaBaySearchFilters().and(manual).anyGenre)
        val merged=CinemaBaySearchFilters(genres=setOf("Thriller","Horror"),anyGenre=true).and(manual)
        assertEquals(setOf("Thriller","Horror"),merged.genres)
        assertEquals(listOf(CinemaBayGenreGroup(setOf("Action","Komödie"),true)),merged.genreGroups)
    }
}
