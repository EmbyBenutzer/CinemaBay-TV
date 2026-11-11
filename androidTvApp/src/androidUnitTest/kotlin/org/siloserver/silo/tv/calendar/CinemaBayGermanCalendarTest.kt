package org.siloserver.silo.tv.calendar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class CinemaBayGermanCalendarTest {
    private val now=Instant.parse("2026-11-11T12:00:00Z")
    private fun fixture()=JSONObject("""{
      "schema_version":1,"updated_at":"2026-11-11T10:00:00Z","valid_until":"2026-11-24T10:00:00Z",
      "sources":[{"id":"proof","kind":"official_publisher","publisher":"Test Publisher","url":"https://example.org/official-date","checked_at":"2026-11-11T09:00:00Z","evidence":"Synthetic test proof for a specific German dubbed episode premiere."}],
      "series":[{"id":"show","title":"Test series"}],
      "releases":[{"id":"show-s1-e1","type":"episode","series_id":"show","season":1,"episode":1,"german_release_date":"2026-11-14","audio_language":"de","audio_format":"dubbed","region":"DE","status":"confirmed","release_kind":"german_premiere","provider":"Test Publisher","distribution":"streaming","source_id":"proof"}]
    }""")
    private fun parse(o:JSONObject)=parseGermanCalendarFeed(o.toString(),now)
    @Test fun acceptsOnlyFullyProvenEpisodeAndUsesGermanDate() {
        val item=parse(fixture()).releases.single()
        assertEquals("2026-11-14",item.date.toString());assertEquals(1,item.episode)
        assertEquals("https://example.org/official-date",item.source.url)
    }
    @Test fun germanTitleOrSubtitlesAreNotDubProof() {
        for((field,value) in listOf("audio_language" to "en","audio_format" to "subtitled","audio_format" to "original","status" to "announced","region" to "AT")) {
            val data=fixture();data.getJSONArray("releases").getJSONObject(0).put(field,value)
            assertTrue(parse(data).releases.isEmpty(),"$field=$value")
        }
    }
    @Test fun excludesMoviesRepeatsAndUnknownSources() {
        for((field,value) in listOf("type" to "movie","release_kind" to "repeat","source_id" to "missing")) {
            val data=fixture();data.getJSONArray("releases").getJSONObject(0).put(field,value)
            assertTrue(parse(data).releases.isEmpty())
        }
    }
    @Test fun rejectsAbsentEvidenceAndUnofficialOrUnsafeSources() {
        for((field,value) in listOf("kind" to "rumour","evidence" to "","url" to "http://example.org/news","url" to "https://127.0.0.1/news")) {
            val data=fixture();data.getJSONArray("sources").getJSONObject(0).put(field,value)
            assertTrue(parse(data).releases.isEmpty())
        }
    }
    @Test fun neverInfersDateOrAcceptsInvalidEpisodeCoordinates() {
        for((field,value) in listOf("german_release_date" to "2026-02-30","german_release_date" to "2026-11-14T20:00:00Z","episode" to "1","episode" to 1.5,"episode" to 0,"season" to -1)) {
            val data=fixture();data.getJSONArray("releases").getJSONObject(0).put(field,value)
            assertTrue(parse(data).releases.isEmpty())
        }
        val missing=fixture();missing.getJSONArray("releases").getJSONObject(0).remove("german_release_date")
        assertTrue(parse(missing).releases.isEmpty())
    }
    @Test fun expiredFutureOrIndefiniteFeedsFailClosed() {
        for((field,value) in listOf("valid_until" to "2026-11-10T12:00:00Z","updated_at" to "2026-11-12T12:00:00Z","valid_until" to "2030-11-24T10:00:00Z","schema_version" to 2)) {
            val data=fixture();data.put(field,value);assertFails{parse(data)}
        }
    }
    @Test fun ambiguousEpisodeRecordsAreAllExcluded() {
        val data=fixture();val array=data.getJSONArray("releases")
        val second=JSONObject(array.getJSONObject(0).toString()).put("id","other-id").put("german_release_date","2026-11-15")
        array.put(second);assertTrue(parse(data).releases.isEmpty());assertEquals(2,parse(data).rejected)
    }
    @Test fun duplicateSourceIdentitiesAndMissingSeriesAreExcluded() {
        val data=fixture();val sources=data.getJSONArray("sources");sources.put(JSONObject(sources.getJSONObject(0).toString()))
        assertTrue(parse(data).releases.isEmpty())
        val missing=fixture();missing.getJSONArray("releases").getJSONObject(0).put("series_id","missing")
        assertTrue(parse(missing).releases.isEmpty())
    }
    @Test fun shippedFeedHasRealSourceLinksAndThirtySixExplicitDates() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val text=context.assets.open("cinemabay/de-synchro.json").bufferedReader().use{it.readText()}
        val stamp=Instant.parse(JSONObject(text).getString("updated_at")).plusSeconds(60)
        val feed=parseGermanCalendarFeed(text,stamp)
        assertEquals(36,feed.releases.size);assertEquals(0,feed.rejected)
        assertEquals(3,feed.releases.map{it.series.id}.distinct().size)
        assertTrue(feed.releases.all{it.source.url.startsWith("https://news.animationdigitalnetwork.com/")})
        assertEquals("2026-08-31",feed.releases.first{it.series.id=="kill-blue" && it.episode==5}.date.toString())
        assertEquals("2026-11-02",feed.releases.first{it.series.id=="i-parry-everything"}.date.toString())
    }
}
