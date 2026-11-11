package org.siloserver.silo.tv.search

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.repository.CatalogRepository
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class CinemaBaySearchPrivacyTest {
    private class Harness(val blockFirst: Boolean = false, val peopleCapability: Boolean = false) {
        val paths=java.util.concurrent.ConcurrentLinkedQueue<String>()
        val identities=DefaultIdentityTransitionBarrier()
        val access=AccessChangeSignals()
        val profile=AtomicReference("adult")
        val secretAllowed=AtomicBoolean(true)
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        private val first=AtomicBoolean(true)
        val tokenManager=object:TokenManager by TokenManagerImpl() {
            override suspend fun snapshotCurrentScope()=AuthScopeSnapshot("server",profile.get(),"https://silo.invalid",null,
                identityGeneration=identities.generation.value,isIdentityGenerationStamped=true,credentialEpoch=1,accountId="account")
        }
        val client=HttpClient(MockEngine { request ->
            paths.add(request.url.encodedPath)
            if(request.url.encodedPath=="/api/v2/catalog/search/capabilities")return@MockEngine respond(
                """{"revision":"test","state":"ready","provider":"native","people_media_scope":$peopleCapability}""",
                HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            if(request.url.encodedPath=="/api/v2/catalog/people")return@MockEngine respond(
                """{"items":[{"id":"42","name":"Tom Hanks"}],"page":{"has_more":false}}""",
                HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            if(request.url.encodedPath=="/api/v2/catalog/query")return@MockEngine respond(
                """{"items":[],"page":{"has_more":false},"total":0,"total_exact":true,"window_cursor":"w"}""",
                HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            val requestedProfile=profile.get();val allowed=secretAllowed.get()
            if(blockFirst && first.getAndSet(false)) {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            val item=if(requestedProfile=="adult" && allowed) """{"content_id":"secret","type":"movie","title":"AdultSecret","year":2020}"""
                else """{"content_id":"child","type":"movie","title":"ChildSafe","year":2020}"""
            respond("""{"items":[$item],"page":{"has_more":false},"total":1,"total_exact":true,"window_cursor":"w"}""", HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        val repository=CinemaBaySearchRepository(ApplicationProvider.getApplicationContext(),CatalogRepository(CatalogApi(client)),tokenManager,identities,access)
        suspend fun ready()=withTimeout(15_000) { while(!repository.state.value.complete)delay(20) }
        suspend fun close(){release.complete(Unit);repository.close();client.close()}
    }
    @Test fun profileTransitionCannotReuseAdultTitles()= runBlocking {
        val h=Harness()
        try {
            h.repository.start();h.ready()
            assertTrue(h.repository.search("AdultSecret",CinemaBaySearchFilters(),null,0).items.isNotEmpty())
            h.identities.changing(IdentityTransitionKind.PROFILE_SWITCH) { h.profile.set("child") }
            h.repository.start();h.ready()
            assertTrue(h.repository.search("AdultSecret",CinemaBaySearchFilters(),null,0).items.isEmpty())
            assertEquals("child",h.repository.search("ChildSafe",CinemaBaySearchFilters(),null,0).items.single().contentId)
        } finally { h.close() }
    }
    @Test fun accessChangePurgesSameProfileSnapshot()=runBlocking {
        val h=Harness()
        try {
            h.repository.start();h.ready();val before=h.repository.state.value.epoch
            h.secretAllowed.set(false);h.access.reportAccessChanged()
            withTimeout(15_000){while(h.repository.state.value.epoch==before || !h.repository.state.value.complete)delay(20)}
            assertTrue(h.repository.search("AdultSecret",CinemaBaySearchFilters(),null,0).items.isEmpty())
        }finally{h.close()}
    }
    @Test fun lateCancelledOldProfileResponseCannotRepopulateIndex()=runBlocking {
        val h=Harness(blockFirst=true)
        try {
            h.repository.start();withTimeout(15_000){h.started.await()}
            h.identities.changing(IdentityTransitionKind.PROFILE_SWITCH) { h.profile.set("child") }
            h.repository.start();h.release.complete(Unit);h.ready()
            delay(100)
            assertTrue(h.repository.search("AdultSecret",CinemaBaySearchFilters(),null,0).items.isEmpty())
            assertEquals("child",h.repository.search("ChildSafe",CinemaBaySearchFilters(),null,0).items.single().contentId)
        }finally{h.close()}
    }
    @Test fun olderServerIsNeverAskedForUnscopedPeople()=runBlocking {
        val h=Harness()
        try {
            h.repository.start();h.ready()
            assertTrue(h.repository.search("Filme mit Tom Hanks",CinemaBaySearchFilters(),null,0).items.isEmpty())
            assertFalse(h.paths.contains("/api/v2/catalog/people"))
        }finally{h.close()}
    }
    @Test fun scopedActorSearchUsesActualExistingFilmography()=runBlocking {
        val h=Harness(peopleCapability=true)
        try{
            h.repository.start();h.ready()
            assertEquals("secret",h.repository.search("Filme mit Tom Hanks",CinemaBaySearchFilters(),null,0).items.single().contentId)
            assertTrue(h.paths.contains("/api/v2/catalog/people"))
        }finally{h.close()}
    }
    @Test fun germanAudioRequiresServerConfirmationNotOriginalLanguageGuessing()=runBlocking {
        val h=Harness()
        try{
            h.repository.start();h.ready()
            assertTrue(h.repository.search("Filme auf Deutsch",CinemaBaySearchFilters(),null,0).items.isEmpty())
            assertTrue(h.paths.contains("/api/v2/catalog/query"))
        }finally{h.close()}
    }
}
