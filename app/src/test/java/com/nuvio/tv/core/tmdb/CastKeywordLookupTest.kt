package com.nuvio.tv.core.tmdb

import com.nuvio.tv.domain.model.ContentType
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastKeywordLookupTest {
    private fun lookup(respond: (Request) -> Pair<Int, String>): CastKeywordLookup {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, text) = respond(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").header("Retry-After", "120")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return CastKeywordLookup(client)
    }

    @Test
    fun `an exact name resolves cast and voice credits with playable ids and is cached across screens`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val service = lookup { request ->
            requests.add(request)
            when (request.url.host) {
                "www.wikidata.org" ->
                    200 to """{"search":[{"id":"Q2263","label":"Tom Hanks"},{"id":"Q99","label":"Tom Hanks Jr."}]}"""
                else -> {
                    200 to """{"results":{"bindings":[
                        {"imdb":{"value":"tt0109830"},"workLabel":{"value":"Forrest Gump"},"date":{"value":"1994-06-23"},"series":{"value":"false"}},
                        {"imdb":{"value":"tt0080202"},"workLabel":{"value":"Bosom Buddies"},"series":{"value":"true"}},
                        {"imdb":{"value":"invalid"},"workLabel":{"value":"Invalid"},"series":{"value":"false"}}
                    ]}}"""
                }
            }
        }
        val found = service.search("tom hanks")
        assertEquals(listOf("Forrest Gump", "Bosom Buddies"), found.items.map { it.name })
        assertEquals(listOf(ContentType.MOVIE, ContentType.SERIES), found.items.map { it.type })
        assertEquals("1994", found.items.first().releaseInfo)
        assertFalse(found.truncated)
        assertEquals(found, service.search(" Tom   Hanks "))
        assertEquals(2, requests.size)
        assertEquals("tom hanks", requests.first().url.queryParameter("search"))
        val query = requests.last().url.queryParameter("query").orEmpty()
        assertTrue("wd:Q2263" in query)
        assertFalse("wd:Q99" in query)
        assertTrue("wdt:P161|wdt:P725" in query)
    }

    @Test
    fun `partial name is not a cast match and skips the credit request`() = runBlocking {
        val requests = AtomicInteger()
        val service = lookup {
            requests.incrementAndGet()
            200 to """{"search":[{"id":"Q2263","label":"Tom Hanks","match":{"text":"Tom Hanks"}}]}"""
        }
        assertEquals(CastKeywordResults(), service.search("Tom Ha"))
        assertEquals(CastKeywordResults(), service.search("Tom Ha"))
        assertEquals(1, requests.get())
    }

    @Test
    fun `rate limit is an error and suppresses repeat requests during Retry-After`() = runBlocking {
        val requests = AtomicInteger()
        val service = lookup {
            requests.incrementAndGet()
            429 to "{}"
        }
        for (name in listOf("Tom Hanks", "Bryan Cranston")) {
            assertTrue(runCatching { service.search(name) }.exceptionOrNull() is IOException)
        }
        assertEquals(1, requests.get())
    }
}
