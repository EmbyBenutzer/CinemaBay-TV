package org.siloserver.silo.tv.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.siloserver.silo.viewmodel.CalendarUiState
import org.siloserver.silo.model.calendar.CalendarItem
import org.siloserver.silo.model.calendar.CalendarDay
import org.siloserver.silo.model.calendar.CalendarFilter
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.util.IsoDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class CinemaBayGermanCalendarViewModel(private val repository:CinemaBayGermanCalendarRepository):ViewModel() {
    private fun today()=LocalDate.now(ZoneId.of("Europe/Berlin")).toString()
    private val mutable=MutableStateFlow(CalendarUiState(today=today(),weekStart=IsoDate.weekStart(today()),selectedDay=today()))
    val uiState=mutable.asStateFlow()
    private val status=MutableStateFlow("Nur belegte deutsche Synchro-Premieren · keine OmU-Termine")
    val sourceStatus=status.asStateFlow()
    private var snapshot:GermanCalendarSnapshot?=null
    private var generation=0
    private var refreshJob:Job?=null
    init {load()}
    fun load()=request(false)
    fun refresh()=request(true)
    private fun request(force:Boolean) {
        refreshJob?.cancel()
        val token=++generation
        val safe=snapshot?.feed?.isCurrent(Instant.now())==true
        mutable.update{it.copy(isLoading=!safe,isRefreshing=safe,error=null,days=if(safe)it.days else emptyList(),today=today())}
        viewModelScope.launch {
            try {
                val data=repository.load(force)
                if(token!=generation)return@launch
                snapshot=data;render()
                if(snapshot===data) {
                    val remaining=java.time.Duration.between(Instant.now(),data.feed.validUntil).toMillis().coerceAtLeast(1)
                    refreshJob=viewModelScope.launch {delay(minOf(remaining,6*60*60*1000L));load()}
                }
            }catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){
                if(token!=generation)return@launch
                snapshot=null;status.value="Keine Originaltermine oder Vermutungen als Ersatz."
                mutable.update{it.copy(days=emptyList(),isLoading=false,isRefreshing=false,error=UiText.Raw("Bestätigte deutsche Synchrotermine sind derzeit nicht verfügbar. Bitte aktualisieren."))}
            }
        }
    }
    private fun render() {
        val data=snapshot ?: return
        if(!data.feed.isCurrent(Instant.now())){snapshot=null;request(true);return}
        val s=mutable.value
        val items=data.feed.releases.filter {
            it.date.toString()>=s.weekStart && it.date.toString()<=s.weekEnd && when(s.filter){CalendarFilter.Trending -> it.distribution=="streaming";CalendarFilter.All -> it.distribution=="tv";else->true}
        }
        val grouped=items.groupBy{it.date.toString()}.map{(date,releases)->CalendarDay(date,releases.map {
            CalendarItem(contentId="cinemabay-de:${it.id}",type="episode",title=it.series.title,episodeTitle="Folge ${it.episode} · ${it.channel}",
                seasonNumber=it.season,episodeNumber=it.episode,airDate=date,localAirDate=date,posterUrl=it.series.posterUrl)
        })}
        val stamp=data.feed.updatedAt.atZone(ZoneId.of("Europe/Berlin")).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        status.value="${if(data.offline)"Offline gespeicherte" else "Ausgewählte belegte"} Termine · Stand $stamp"+
            if(data.feed.rejected>0)" · ${data.feed.rejected} ungeprüfte Einträge ausgeblendet" else ""
        mutable.update{it.copy(days=grouped,isLoading=false,isRefreshing=false,error=null)}
    }
    fun selectDay(date:String){if(date in mutable.value.weekDates)mutable.update{it.copy(selectedDay=date)}}
    fun setFilter(filter:String){if(filter !in listOf(CalendarFilter.Following,CalendarFilter.Trending,CalendarFilter.All))return;mutable.update{it.copy(filter=filter)};render()}
    fun prevWeek()=move(-7)
    fun nextWeek()=move(7)
    private fun move(days:Long){val week=IsoDate.plusDays(mutable.value.weekStart,days);mutable.update{it.copy(weekStart=week,selectedDay=if(week==IsoDate.weekStart(today()))today() else week)};render()}
    fun goToToday(){mutable.update{it.copy(today=today(),weekStart=IsoDate.weekStart(today()),selectedDay=today())};render()}
    fun entry(contentId:String):GermanCalendarRelease?=snapshot?.feed?.takeIf{it.isCurrent(Instant.now())}?.releases?.firstOrNull{"cinemabay-de:${it.id}"==contentId}
}
