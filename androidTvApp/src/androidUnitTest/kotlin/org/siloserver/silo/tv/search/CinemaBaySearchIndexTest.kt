package org.siloserver.silo.tv.search

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.model.catalog.*
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class CinemaBaySearchIndexTest {
    private fun withIndex(block:(CinemaBaySearchIndex)->Unit) {
        val index=CinemaBaySearchIndex(ApplicationProvider.getApplicationContext())
        try { index.clear();block(index) } finally { index.clear();index.close() }
    }
    private fun film(id:String,title:String,year:Int=2020)=BrowseItem(id,"movie",title,year=year,genres=listOf("Thriller"),ratingImdb=8.0,runtime=100,originalLanguage="en",contentRating="FSK 12")
    @Test fun actualSQLiteFtsHandlesMultiWordTyposAndLivePrefixes()=withIndex { index ->
        index.upsert(listOf(film("hp","Harry Potter"),film("m","Matrix"),film("x","Another Movie")))
        val diagnostic=index.readableDatabase.rawQuery("SELECT docid,title FROM terms",null).use { c -> buildList { while(c.moveToNext())add("${c.getLong(0)}:${c.getString(1)}") } }
        val direct=index.readableDatabase.rawQuery("SELECT docid FROM terms WHERE terms MATCH ?",arrayOf("harry* potter*")).use{c->buildList{while(c.moveToNext())add(c.getLong(0))}}
        assertEquals(listOf("hp"),index.search(CinemaBaySearchIntent("Harry Poter"),null).items.map { it.contentId }, "FTS rows=$diagnostic direct=$direct")
        assertEquals(listOf("m"),index.search(CinemaBaySearchIntent("Matirx"),null).items.map { it.contentId })
        assertEquals(listOf("hp"),index.search(CinemaBaySearchIntent("Har Po"),null).items.map { it.contentId })
    }
    @Test fun filtersAreAndCombinedAndUnknownMetadataCannotPass()=withIndex { index ->
        index.upsert(listOf(film("good","Good"),film("old","Old",1990),film("long","Long").copy(runtime=180),film("unknown","Unknown").copy(runtime=null)))
        val p=CinemaBayGermanQueryParser.parse("Thriller ab 2015 unter 120 Minuten mindestens 7")
        assertEquals(listOf("good"),index.search(p,null).items.map { it.contentId })
    }
    @Test fun updatesRemoveOldFtsTermsAndClearingRemovesVocabulary()=withIndex { index ->
        index.upsert(listOf(film("one","Old Name")));index.upsert(listOf(film("one","New Title")))
        assertEquals(1,index.count());assertTrue(index.search(CinemaBaySearchIntent("Old Name"),null).items.isEmpty())
        assertEquals(1,index.search(CinemaBaySearchIntent("New Title"),null).items.size)
        index.clear();assertEquals(0,index.count());assertTrue(index.search(CinemaBaySearchIntent("New Title"),null).items.isEmpty())
    }
    @Test fun negativeGenreAndOriginalLanguageDoNotClaimAudio()=withIndex { index ->
        index.upsert(listOf(film("de","Deutsch").copy(originalLanguage="de"),film("en","English"),film("h","Horror").copy(genres=listOf("Horror"))))
        val p=CinemaBayGermanQueryParser.parse("deutsche Filme ohne Horror")
        assertEquals(listOf("de"),index.search(p,null).items.map { it.contentId })
    }
    @Test fun qualityAndPersonalFiltersAreApplied()=withIndex { index ->
        index.upsert(listOf(film("yes","Yes").copy(overlaySummary=OverlaySummary(resolution="2160p",hdr="HDR10"),userState=MediaItemUserState(isFavorite=true)),film("no","No")))
        assertEquals(listOf("yes"),index.search(CinemaBaySearchIntent(filters=CinemaBaySearchFilters(uhd=true,hdr=true,favorites=true)),null).items.map { it.contentId })
    }
    @Test fun maliciousFtsInputCannotModifySchema()=withIndex { index ->
        index.upsert(listOf(film("one","Safe")))
        index.search(CinemaBaySearchIntent("\"; DROP TABLE documents;--"),null)
        assertEquals(1,index.count())
    }
    @Test fun joinedAndSeparatedTitlesWorkInBothDirections()=withIndex { index ->
        index.upsert(listOf(film("spider","Spiderman"),film("hp","Harry Potter")))
        assertEquals("spider",index.search(CinemaBaySearchIntent("Spider Man"),null).items.single().contentId)
        assertEquals("hp",index.search(CinemaBaySearchIntent("HaryPoter"),null).items.single().contentId)
    }
}
