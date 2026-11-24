package com.nuvio.tv.core.tmdb

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PublicPersonReplicaLiveTest {
    @Test fun coldFilmographyDuringWikipediaBackoff() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCastReplica") == "true")
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            hosts += request.url.host
            if (request.url.host == "en.wikipedia.org") {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(429).message("Test backoff").header("Retry-After", "120")
                    .body("".toResponseBody()).build()
            } else {
                assertEquals("qlever.dev", request.url.host)
                chain.proceed(request)
            }
        }.build()
        val service = PublicPersonService(client)
        val page = requireNotNull(service.fetch("Tom Hanks", false, "tt0114709"))
        assertTrue(page.movieCredits.any { it.id == "tt0114709" }) // Toy Story
        assertTrue(page.movieCredits.any { it.id == "tt0109830" }) // Forrest Gump
        assertEquals(page, service.fetch("Tom Hanks", false, "tt0114709"))
        assertEquals(1, hosts.count { it == "en.wikipedia.org" })
        assertTrue(hosts.count { it == "qlever.dev" } >= 1)
    }
}
