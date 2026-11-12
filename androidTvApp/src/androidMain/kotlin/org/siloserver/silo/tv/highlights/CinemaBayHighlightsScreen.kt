package org.siloserver.silo.tv.highlights

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val highlightBringIntoView=object:BringIntoViewSpec {
    override fun calculateScrollDistance(offset:Float,size:Float,containerSize:Float):Float=when {
        offset<0f->offset
        offset+size>containerSize->offset+size-containerSize
        else->0f
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CinemaBayHighlightsScreen(
    onInitialContentFocus:()->Unit={},onMoveUpToMenu:()->Unit={},focusRequest:Int=0,
    onContentUpFallbackChanged:((((Boolean)->Boolean)?)->Unit)?=null,
) {
    val context=LocalContext.current
    val focusManager=LocalFocusManager.current
    val repository=remember{CinemaBayHighlightsRepository(context)}
    val today=remember{YearMonth.now(ZoneId.of("Europe/Berlin"))}
    var monthOffset by rememberSaveable{mutableIntStateOf(0)}
    var kind by rememberSaveable{mutableIntStateOf(0)}
    var refresh by remember{mutableIntStateOf(0)}
    var genre by rememberSaveable{mutableStateOf("")}
    var genreDialog by remember{mutableStateOf(false)}
    var genreIndexLoading by remember{mutableStateOf(false)}
    var genreIndexIncomplete by remember{mutableStateOf(false)}
    val genreFocus=remember{FocusRequester()}
    val scope=rememberCoroutineScope()
    var result by remember{mutableStateOf<HighlightsResult?>(null)}
    var loading by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf<String?>(null)}
    var selected by remember{mutableStateOf<CinemaBayHighlight?>(null)}
    var trailer by remember{mutableStateOf<HighlightTrailer?>(null)}
    var trailerLoading by remember{mutableStateOf(false)}
    var trailerError by remember{mutableStateOf<String?>(null)}
    val trailerProbes=remember{mutableStateMapOf<String,HighlightTrailerProbe>()}
    val trailerAttempts=remember{mutableStateMapOf<String,Int>()}
    LaunchedEffect(selected) {
        val item=selected ?: return@LaunchedEffect
        if(trailer!=null)return@LaunchedEffect
        try{trailer=repository.trailer(item)}catch(cancel:CancellationException){throw cancel}
        catch(e:Exception){trailerError=e.message?.takeIf{it.startsWith("Für diesen Titel")} ?: "Der Trailer konnte nicht geladen werden."}
        finally{trailerLoading=false}
    }
    val month=today.plusMonths(monthOffset.toLong())
    val filterFocus=remember{FocusRequester()}
    val grid=rememberLazyGridState()
    val monthFocus=remember{List(4){FocusRequester()}}
    var focusedCardIndex by remember{mutableStateOf<Int?>(null)}
    var routingCardFocus by remember{mutableStateOf(false)}
    var navigationJob by remember{mutableStateOf<Job?>(null)}
    var navigationGeneration by remember{mutableIntStateOf(0)}
    fun showListStart() {
        focusedCardIndex=null
        if(!routingCardFocus)scope.launch { grid.scrollToItem(0, 0) }
    }
    val buttonFocus=remember{mutableMapOf<String,FocusRequester>()}
    var restoreId by remember{mutableStateOf<String?>(null)}
    fun applyMetadata(page:String,metadata:HighlightMetadata) {
        result=result?.let{current->current.copy(items=current.items.map{old->
            if(old.page!=page)old else old.copy(genres=metadata.genres.ifEmpty{old.genres},
                summary=metadata.summary.ifBlank{old.summary},metadataLoaded=true)
        })}
    }
    LaunchedEffect(month,refresh) {
        loading=true;result=null;error=null;genreIndexLoading=false;genreIndexIncomplete=false
        val base=try{repository.load(month,refresh>0)}catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){error="Die Highlights konnten nicht geladen werden. Bitte erneut versuchen.";null}
        result=base;loading=false
        if(base!=null) {
            val entries=base.items.distinctBy{it.page}
            genreIndexLoading=entries.isNotEmpty()
            val completed=withTimeoutOrNull(45_000) {coroutineScope {
                // Queue only one small batch at a time so visible trailer
                // probes can use the same three network slots promptly.
                for(batch in entries.chunked(3)) {
                    batch.map{item->async {
                        val data=try{repository.details(item,refresh>0)}catch(cancel:CancellationException){throw cancel}
                            catch(_:Exception){HighlightMetadata(emptyList(),"")}
                        if(data.genres.isEmpty())genreIndexIncomplete=true
                        applyMetadata(item.page,data)
                    }}.awaitAll()
                }
            }}
            if(completed==null)genreIndexIncomplete=true
            genreIndexLoading=false
        }
    }
    LaunchedEffect(focusRequest){withFrameNanos{};runCatching{filterFocus.requestFocus()}}
    LaunchedEffect(restoreId,selected){
        if(selected==null)restoreId?.let{id->withFrameNanos{};runCatching{buttonFocus[id]?.requestFocus()};restoreId=null}
    }
    val kindItems=result?.items.orEmpty().filter{kind==0 || kind==1 && it.kind==HighlightKind.Movie || kind==2 && it.kind==HighlightKind.Series}
    val items=kindItems.filter{highlightHasGenre(it,genre)}
    val genreOptions=kindItems.flatMap{it.genres}.distinct().sorted()+if(kindItems.any{it.genres.isEmpty()})listOf("Genre unbekannt")else emptyList()
    fun moveCardFocus(from:Int,direction:Int) {
        if(from !in items.indices)return
        val rowStart=from-from%3
        if(direction>0 && rowStart+3>=items.size)return
        val target=if(direction<0 && rowStart==0)null else (from+direction*3).coerceIn(0,items.lastIndex)
        navigationGeneration++
        val generation=navigationGeneration
        navigationJob?.cancel()
        routingCardFocus=true
        navigationJob=scope.launch {
            try {
                grid.scrollToItem(if(target==null)0 else target-target%3,0)
                repeat(6) {
                    withFrameNanos{}
                    val requester=if(target==null)monthFocus[monthOffset] else buttonFocus[items[target].id]
                    if(requester?.requestFocus()==true)return@launch
                }
            } finally {
                if(generation==navigationGeneration)routingCardFocus=false
            }
        }
    }
    val routeUp by rememberUpdatedState<(Boolean)->Boolean>({ _ ->
        val index=focusedCardIndex
        if(index!=null && index in items.indices) {
            moveCardFocus(index,-1)
            true
        } else focusManager.moveFocus(FocusDirection.Up)
    })
    DisposableEffect(onContentUpFallbackChanged) {
        val handler:(Boolean)->Boolean={repeat->routeUp(repeat)}
        onContentUpFallbackChanged?.invoke(handler)
        onDispose{onContentUpFallbackChanged?.invoke(null)}
    }
    LaunchedEffect(month,kind,genre){focusedCardIndex=null;grid.scrollToItem(0)}
    Column(Modifier.fillMaxSize().background(Color(0xFF10131F)).padding(horizontal=42.dp).padding(top=80.dp)) {
        Text("Film- & Serien-Highlights",fontSize=29.sp,fontWeight=FontWeight.Bold,color=Color.White)
        Text("Kommende Deutschland-Starts · Termine können sich ändern",color=Color(0xFFB5BED1),fontSize=14.sp,modifier=Modifier.padding(top=5.dp,bottom=10.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            listOf("Alle","Filme","Serien").forEachIndexed{index,label->
                Button(onClick={kind=index},modifier=(if(index==0)Modifier.focusRequester(filterFocus) else Modifier)
                    .onFocusChanged{if(it.isFocused){onInitialContentFocus();showListStart()}}
                    .onPreviewKeyEvent{if(it.type==KeyEventType.KeyDown && it.key==Key.DirectionUp){onMoveUpToMenu();true}else false},
                    colors=ButtonDefaults.colors(containerColor=if(kind==index)Color(0xFF743040)else Color(0xFF222B3D))) {Text(label)}
            }
            Button(onClick={genreDialog=true},enabled=result!=null && !genreIndexLoading,modifier=Modifier.focusRequester(genreFocus).onFocusChanged{if(it.isFocused)showListStart()}) {
                Text(if(genreIndexLoading)"Genres werden geladen …"else "Genre: ${genre.ifBlank{"Alle"}}",fontSize=13.sp)
            }
            Spacer(Modifier.weight(1f))
            Button(onClick={refresh++},modifier=Modifier.onFocusChanged{if(it.isFocused)showListStart()}){Text("Aktualisieren")}
        }
        Row(Modifier.padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            (0..3).forEach{offset->
                Button(onClick={monthOffset=offset},modifier=Modifier.focusRequester(monthFocus[offset]).onFocusChanged{if(it.isFocused)showListStart()},colors=ButtonDefaults.colors(containerColor=if(monthOffset==offset)Color(0xFF743040)else Color(0xFF222B3D))) {
                    Text(today.plusMonths(offset.toLong()).format(DateTimeFormatter.ofPattern("MMMM uuuu",Locale.GERMAN)),fontSize=13.sp)
                }
            }
        }
        when {
            loading->Text("Highlights werden geladen …",color=Color.White,modifier=Modifier.padding(top=30.dp))
            error!=null->Text(error!!,color=Color.White,modifier=Modifier.padding(top=30.dp))
            else->{
                if(genreIndexIncomplete && !genreIndexLoading)Text("Bei einigen Titeln sind die Genre-Angaben noch nicht vollständig.",fontSize=11.sp,color=Color(0xFFFFD296))
                if(result!!.notices.isNotEmpty())Text(result!!.notices.joinToString(" · "),fontSize=12.sp,color=Color(0xFFFFD296))
                Text("${items.size} angekündigte Starts · Trailer starten nur nach deinem Klick",fontSize=12.sp,color=Color(0xFFB5BED1),modifier=Modifier.padding(bottom=4.dp))
                if(items.isEmpty())Text("Für diese Auswahl sind aktuell keine Starttermine geladen.",color=Color.White,modifier=Modifier.padding(top=24.dp))
                CompositionLocalProvider(LocalBringIntoViewSpec provides highlightBringIntoView) {
                LazyVerticalGrid(columns=GridCells.Fixed(3),state=grid,
                    contentPadding=PaddingValues(top=8.dp,bottom=32.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(items,key={_,item->item.id}){index,item->
                        val attempt=trailerAttempts[item.page] ?: 0
                        val probe=trailerProbes[item.page]?.takeIf{it.revision==refresh && it.attempt==attempt}
                        val trailerStatus=probe?.status ?: HighlightTrailerStatus.Checking
                        LaunchedEffect(item.page,refresh,attempt) {
                            val previous=trailerProbes[item.page]
                            if(previous?.revision==refresh && previous.attempt==attempt && previous.status!=HighlightTrailerStatus.Checking)return@LaunchedEffect
                            trailerProbes[item.page]=HighlightTrailerProbe(refresh,attempt,HighlightTrailerStatus.Checking)
                            val status=try {HighlightTrailerStatus.Available(repository.trailer(item))}
                                catch(cancel:CancellationException){throw cancel}
                                catch(_:HighlightTrailerUnavailableException){HighlightTrailerStatus.Missing}
                                catch(_:Exception){HighlightTrailerStatus.Failed}
                            trailerProbes[item.page]=HighlightTrailerProbe(refresh,attempt,status)
                        }
                        val activate:(()->Unit)?=when(trailerStatus) {
                            is HighlightTrailerStatus.Available -> ({
                                restoreId=item.id;trailer=trailerStatus.trailer;trailerLoading=false;trailerError=null;selected=item
                            })
                            HighlightTrailerStatus.Failed -> ({trailerAttempts[item.page]=attempt+1})
                            else -> null
                        }
                        val requester=remember(item.id){buttonFocus.getOrPut(item.id){FocusRequester()}}
                        val revealCard=remember(item.id){BringIntoViewRequester()}
                        var cardFocused by remember(item.id){mutableStateOf(false)}
                        LaunchedEffect(cardFocused,index,routingCardFocus) {
                            if(cardFocused && !routingCardFocus) {
                                // Let focus search finish before moving the parent card.
                                // Forcing a grid remeasure during focus acquisition can
                                // discard the selected column's beyond-bounds target.
                                withFrameNanos{}
                                revealCard.bringIntoView()
                                if(index<3)grid.scrollToItem(0,0)
                            }
                        }
                        Column(Modifier.bringIntoViewRequester(revealCard).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C2334)).padding(12.dp)) {
                            Row(Modifier.heightIn(min=184.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                AsyncImage(item.poster,item.title,Modifier.width(94.dp).height(160.dp).clip(RoundedCornerShape(6.dp)),contentScale=ContentScale.Crop)
                                Column(Modifier.weight(1f)) {
                                    Text(if(item.kind==HighlightKind.Movie)"FILM"else "SERIE",color=Color(0xFFEA8E9D),fontSize=11.sp,fontWeight=FontWeight.Bold)
                                    Text(item.title,color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=3,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(vertical=5.dp))
                                    Text(item.date.format(DateTimeFormatter.ofPattern("dd.MM.uuuu")),fontSize=13.sp,color=Color.White)
                                    Text(listOf(item.provider,item.season).filter{it.isNotBlank()}.joinToString(" · "),fontSize=12.sp,color=Color(0xFFB5BED1),maxLines=2,overflow=TextOverflow.Ellipsis)
                                    if(item.genres.isNotEmpty())Text(item.genres.joinToString(" · "),fontSize=10.sp,color=Color(0xFFEA8E9D),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=3.dp))
                                    Text(item.summary.ifBlank{if(item.metadataLoaded)"Noch keine Kurzbeschreibung verfügbar."else "Kurzbeschreibung wird geladen …"},
                                        fontSize=11.sp,lineHeight=14.sp,color=Color(0xFFD5DAE4),maxLines=4,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=6.dp))
                                }
                            }
                            CinemaBayHighlightTrailerLink(trailerStatus,activate,
                                modifier=Modifier.fillMaxWidth().padding(top=12.dp).focusRequester(requester)
                                    .onFocusChanged { focus ->
                                        cardFocused=focus.isFocused
                                        if(focus.isFocused)focusedCardIndex=index
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if(event.key==Key.DirectionDown) {
                                            if(event.type==KeyEventType.KeyDown)moveCardFocus(index,1)
                                            true
                                        } else false
                                    },
                            )
                        }
                    }
                }
                }
            }
        }
    }
    if(genreDialog)CinemaBayHighlightGenreDialog(genre,genreOptions,{value->genre=value;genreDialog=false
        scope.launch{withFrameNanos{};runCatching{genreFocus.requestFocus()}}
    },{genreDialog=false;scope.launch{withFrameNanos{};runCatching{genreFocus.requestFocus()}}})
    selected?.let{item->CinemaBayHighlightTrailerDialog(item.title,trailer,trailerLoading,trailerError){selected=null;trailer=null}}
}
