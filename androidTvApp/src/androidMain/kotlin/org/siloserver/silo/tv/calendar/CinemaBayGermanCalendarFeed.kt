package org.siloserver.silo.tv.calendar

import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.Duration

internal data class GermanCalendarSource(val id:String,val publisher:String,val url:String,val evidence:String,val checkedAt:Instant)
internal data class GermanCalendarSeries(val id:String,val title:String,val posterUrl:String?)
internal data class GermanCalendarRelease(val id:String,val series:GermanCalendarSeries,val season:Int,val episode:Int,val date:LocalDate,val channel:String,val distribution:String,val source:GermanCalendarSource)
internal data class GermanCalendarFeed(val updatedAt:Instant,val validUntil:Instant,val releases:List<GermanCalendarRelease>,val rejected:Int=0) {
    fun isCurrent(now:Instant)= !now.isBefore(updatedAt.minusSeconds(300)) && now.isBefore(validUntil)
}

internal fun germanCalendarHttps(value:String):Boolean=runCatching {
    val u=URI(value)
    u.scheme=="https" && u.userInfo==null && u.port in listOf(-1,443) && u.host!=null &&
        u.host.contains('.') && !u.host.matches(Regex("[0-9.]+")) && !u.host.endsWith(".local") &&
        u.host!="localhost" && !u.host.contains(':') && value.length<=2000
}.getOrDefault(false)

/** Only editorially confirmed episode premieres; a German title or country alone never qualifies. */
internal fun parseGermanCalendarFeed(text:String,now:Instant=Instant.now()):GermanCalendarFeed {
    require(text.toByteArray().size<=1_000_000)
    val root=JSONObject(text);require(root.get("schema_version") is Int && root.getInt("schema_version")==1)
    val updated=Instant.parse(root.getString("updated_at"));val until=Instant.parse(root.getString("valid_until"))
    require(until>updated && Duration.between(updated,until)<=Duration.ofDays(14))
    require(updated<=now.plusSeconds(300) && until>now){"Kalenderdaten sind abgelaufen."}
    fun id(value:String)=value.matches(Regex("[a-z0-9][a-z0-9-]{0,99}"))
    val sources=mutableMapOf<String,GermanCalendarSource>()
    val sourceArray=root.getJSONArray("sources");require(sourceArray.length()<=500)
    val duplicateSources=(0 until sourceArray.length()).map{sourceArray.getJSONObject(it).optString("id")}.groupingBy{it}.eachCount().filterValues{it>1}.keys
    for(i in 0 until sourceArray.length()) runCatching {
        val o=sourceArray.getJSONObject(i);val key=o.getString("id");val url=o.getString("url")
        val checked=Instant.parse(o.getString("checked_at"));val evidence=o.getString("evidence").trim();val publisher=o.getString("publisher").trim()
        require(id(key) && key !in duplicateSources && o.getString("kind")=="official_publisher")
        require(germanCalendarHttps(url) && checked<=updated.plusSeconds(300) && publisher.length in 2..100 && evidence.length in 20..1200)
        sources[key]=GermanCalendarSource(key,publisher,url,evidence,checked)
    }
    val series=mutableMapOf<String,GermanCalendarSeries>();val seriesArray=root.getJSONArray("series");require(seriesArray.length()<=2000)
    val duplicateSeries=(0 until seriesArray.length()).map{seriesArray.getJSONObject(it).optString("id")}.groupingBy{it}.eachCount().filterValues{it>1}.keys
    for(i in 0 until seriesArray.length()) runCatching {
        val o=seriesArray.getJSONObject(i);val key=o.getString("id");val title=o.getString("title").trim()
        require(id(key) && key !in duplicateSeries && title.length in 1..250)
        val poster=o.optString("poster_url").takeIf{it.isNotBlank() && germanCalendarHttps(it)}
        series[key]=GermanCalendarSeries(key,title,poster)
    }
    val events=root.getJSONArray("releases");require(events.length()<=5000)
    val accepted=mutableListOf<GermanCalendarRelease>()
    for(i in 0 until events.length()) runCatching {
        val o=events.getJSONObject(i);val key=o.getString("id")
        require(id(key) && o.getString("type")=="episode" && o.getString("status")=="confirmed")
        require(o.getString("audio_language")=="de" && o.getString("audio_format")=="dubbed" && o.getString("region")=="DE")
        require(o.getString("release_kind")=="german_premiere")
        val source=sources[o.getString("source_id")] ?: error("Beleg fehlt")
        val show=series[o.getString("series_id")] ?: error("Serienidentität fehlt")
        require(o.get("season") is Int && o.get("episode") is Int)
        val season=o.getInt("season");val episode=o.getInt("episode");val channel=o.getString("provider").trim();val distribution=o.getString("distribution")
        require(season in 0..999 && episode in 1..9999 && channel.length in 2..100 && distribution in listOf("streaming","tv"))
        val dateString=o.getString("german_release_date");require(dateString.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        val date=LocalDate.parse(dateString);require(date.year in 2000..2100)
        accepted+=GermanCalendarRelease(key,show,season,episode,date,channel,distribution,source)
    }
    val duplicates=accepted.groupingBy{it.id}.eachCount().filterValues{it>1}.keys
    val duplicateEpisodes=accepted.groupingBy{Triple(it.series.id,it.season,it.episode)}.eachCount().filterValues{it>1}.keys
    val result=accepted.filter{it.id !in duplicates && Triple(it.series.id,it.season,it.episode) !in duplicateEpisodes}
        .sortedWith(compareBy<GermanCalendarRelease>{it.date}.thenBy{it.series.title}.thenBy{it.season}.thenBy{it.episode})
    return GermanCalendarFeed(updated,until,result,events.length()-result.size)
}
