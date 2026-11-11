package org.siloserver.silo.tv.search

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.encodeToString
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.tv.data.preferences.TvLibraryScopeStore
import org.siloserver.silo.tv.ui.screens.search.TvSearchViewModel
import org.siloserver.silo.tv.ui.screens.search.TvSearchMediaType
import java.util.concurrent.Executors
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class CinemaBaySearchViewModelTest {
    private suspend fun await(predicate:()->Boolean)=withTimeout(15_000){while(!predicate())delay(10)}
    private fun checkSearch(block:suspend (TvSearchViewModel,CinemaBaySearchRepository)->Unit)=runBlocking {
        val main=Executors.newSingleThreadExecutor().asCoroutineDispatcher();Dispatchers.setMain(main)
        val identities=DefaultIdentityTransitionBarrier()
        val tokens=object:TokenManager by TokenManagerImpl(){override suspend fun snapshotCurrentScope()=
            AuthScopeSnapshot("server","profile","https://silo.invalid",null,identityGeneration=identities.generation.value,isIdentityGenerationStamped=true,accountId="account")}
        val all=listOf(
            BrowseItem("hp","movie","Harry Potter",year=2001,genres=listOf("Adventure"),runtime=150,ratingImdb=8.0),
            BrowseItem("night","movie","Night Chase",year=2020,genres=listOf("Thriller"),runtime=100,ratingImdb=8.0),
            BrowseItem("old","movie","Old Night",year=1980,genres=listOf("Thriller"),runtime=100,ratingImdb=9.0),
            BrowseItem("show","series","Star Show",year=2022,genres=listOf("Science Fiction"),runtime=45,ratingImdb=8.5),
        )
        val client=HttpClient(MockEngine { request ->
            val body=when(request.url.encodedPath){
                "/api/v2/user/libraries" -> """{"items":[{"id":"1","name":"Video","type":"movies","sort_order":0}],"page":{"has_more":false}}"""
                "/api/v2/catalog", "/api/v2/catalog/query" -> {
                    val type=request.url.parameters["type"]
                    val q=request.url.parameters["q"]
                    val items=all.filter{(type==null || it.type==type) && (q.isNullOrBlank() || it.title.contains(q,true))}
                    """{"items":${SiloJson.encodeToString(items)},"page":{"has_more":false},"total":${items.size},"total_exact":true,"window_cursor":"w"}"""
                }
                "/api/v2/search/capabilities" -> """{"revision":"test","state":"ready","provider":"native","people_media_scope":true}"""
                else -> """{"items":[]}"""
            }
            respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        }){install(ContentNegotiation){json(SiloJson)}}
        val catalog=CatalogRepository(CatalogApi(client))
        val app=ApplicationProvider.getApplicationContext<Application>()
        val repository=CinemaBaySearchRepository(app,catalog,tokens,identities,AccessChangeSignals())
        val vm=withContext(main){TvSearchViewModel(catalog,PersonalDataRepository(PersonalDataApi(client)),TvLibraryScopeStore(app,tokens),repository)}
        try {
            await{repository.state.value.complete && TvSearchMediaType.Movies in vm.uiState.value.availableMediaTypes}
            block(vm,repository)
        }finally{
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin();repository.close();client.close();Dispatchers.resetMain();main.close()
        }
    }
    @Test fun typoQueryTravelsThroughActualAiViewModelPath()=checkSearch { vm,_ ->
        withContext(Dispatchers.Main){vm.onQueryChanged("Hary Poter");vm.submitSearch()}
        await{!vm.uiState.value.isLoading && vm.uiState.value.items.any{it.contentId=="hp"}}
        assertTrue(vm.uiState.value.aiEnabled);assertEquals(listOf("hp"),vm.uiState.value.items.map{it.contentId})
    }
    @Test fun blankQueryWithCombinedManualFiltersIsSearchable()=checkSearch { vm,_ ->
        withContext(Dispatchers.Main){vm.onAiFiltersChanged(CinemaBaySearchFilters(genres=setOf("Thriller"),yearMin=2015,maxMinutes=120))}
        await{!vm.uiState.value.isLoading && vm.uiState.value.items.any{it.contentId=="night"}}
        assertEquals(listOf("night"),vm.uiState.value.items.map{it.contentId})
        assertEquals(2015,vm.uiState.value.aiIntent?.filters?.yearMin)
    }
    @Test fun clearingQueryAndTypeDoesNotLeavePreviousResults()=checkSearch { vm,_ ->
        withContext(Dispatchers.Main){vm.onMediaTypeChanged(TvSearchMediaType.Movies)}
        await{vm.uiState.value.items.isNotEmpty()}
        withContext(Dispatchers.Main){vm.onMediaTypeChanged(TvSearchMediaType.All)}
        assertTrue(vm.uiState.value.items.isEmpty());assertNull(vm.uiState.value.aiIntent)
    }
    @Test fun naturalGermanRequestCombinesAllConstraints()=checkSearch { vm,_ ->
        withContext(Dispatchers.Main){vm.onQueryChanged("ungesehene Thriller ab 2015 unter 120 Minuten mindestens 7,5");vm.submitSearch()}
        await{!vm.uiState.value.isLoading && vm.uiState.value.items.any{it.contentId=="night"}}
        assertEquals(listOf("night"),vm.uiState.value.items.map{it.contentId})
    }
}
