package org.siloserver.silo.tv.highlights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text

@Composable
internal fun CinemaBayHighlightGenreDialog(selected:String,genres:List<String>,onSelect:(String)->Unit,onClose:()->Unit) {
    val options=remember(genres,selected){listOf("")+(genres+listOf(selected).filter{it.isNotBlank()}).distinct()}
    val requester=remember{FocusRequester()}
    val list=rememberLazyListState()
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.width(480.dp).background(Color(0xFF1C2334),RoundedCornerShape(16.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Genre auswählen",fontSize=24.sp,color=Color.White)
            LazyColumn(state=list,modifier=Modifier.heightIn(max=320.dp),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(6.dp)) {
                items(options,key={it}){genre->
                    Button(onClick={onSelect(genre)},modifier=Modifier.fillMaxWidth().then(if(genre==selected)Modifier.focusRequester(requester)else Modifier),
                        colors=ButtonDefaults.colors(containerColor=if(genre==selected)Color(0xFF743040)else Color(0xFF222B3D))) {
                        Text(genre.ifBlank{"Alle Genres"})
                    }
                }
            }
            Button(onClick=onClose){Text("Abbrechen")}
        }
        LaunchedEffect(Unit){list.scrollToItem(options.indexOf(selected).coerceAtLeast(0));withFrameNanos{};runCatching{requester.requestFocus()}}
    }
}
