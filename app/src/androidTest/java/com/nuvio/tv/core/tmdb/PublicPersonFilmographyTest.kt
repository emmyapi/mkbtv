package com.nuvio.tv.core.tmdb

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicPersonFilmographyTest {
    private fun biography(name: String) = """{"query":{"pages":[{"pageprops":{"wikibase_item":"Q1"},
        "extract":"Example biography","description":"American actor","canonicalurl":"https://en.wikipedia.org/wiki/$name"}]}}"""

    private val oneCredit = """{"results":{"bindings":[{"person":{"value":"http://www.wikidata.org/entity/Q1"},
        "workLabel":{"value":"Example Film"},"imdb":{"value":"tt123"}}]}}"""

    @Test fun preservesCompletedPagesAndRetriesPartialFilmographyOnReopen() = runBlocking {
        val offsets = mutableListOf<Int>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            var code = 200
            val body = when {
                request.url.host == "en.wikipedia.org" -> {
                    assertEquals("Example|Example (actor)|Example (actress)", request.url.queryParameter("titles"))
                    biography("Example")
                }
                request.url.host == "query.wikidata.org" -> {
                    val query = requireNotNull(request.url.queryParameter("query"))
                    assertTrue(query.contains("""?source wdt:P345 "tt123" ; (wdt:P161|wdt:P725) ?person"""))
                    assertTrue(query.contains("""wikibase:language "en,mul""""))
                    val offset = query.substringAfterLast("OFFSET ").toInt()
                    offsets += offset
                    if (offset == 200 && offsets.size == 2) {
                        code = 503
                        ""
                    } else {
                        val rows = (offset until minOf(offset + 200, 201)).joinToString(",") {
                            """{"person":{"value":"http://www.wikidata.org/entity/Q1"},
                                "work":{"value":"http://www.wikidata.org/entity/Q${it + 10}"},
                                "workLabel":{"value":"Example $it"},"imdb":{"value":"tt${it + 10}"}}"""
                        }
                        """{"results":{"bindings":[$rows]}}"""
                    }
                }
                else -> { code = 404; "" }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val service = PublicPersonService(client)
        val partial = requireNotNull(service.fetch("Example", false, "tt123"))
        assertTrue(partial.partialFilmography)
        assertEquals(200, partial.movieCredits.size)
        val complete = requireNotNull(service.fetch("Example", false, "tt123"))
        assertFalse(complete.partialFilmography)
        assertEquals(201, complete.movieCredits.distinctBy { it.id }.size)
        assertEquals(complete, service.fetch("Example", false, "tt123"))
        assertEquals(listOf(0, 200, 0, 200), offsets)
    }

    @Test fun wikipediaBackoffStillAllowsCachedCandidatesToLoadFreshCredits() = runBlocking {
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            hosts += url.host
            val limited = url.queryParameter("titles")?.startsWith("Other|") == true
            val body = when (url.host) {
                "en.wikipedia.org" -> biography("Example")
                "query.wikidata.org" -> oneCredit
                else -> """{"results":{"bindings":[]}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (limited) 429 else 200).message("Fixture")
                .header("Retry-After", "120").body(body.toResponseBody()).build()
        }.build()
        val service = PublicPersonService(client)
        assertEquals(1, requireNotNull(service.fetch("Example", false, "tt123")).movieCredits.size)
        val start = android.os.SystemClock.elapsedRealtime()
        val limited = runCatching { service.fetch("Other", false, "tt123") }.exceptionOrNull()
        assertTrue(limited is PublicPersonRateLimited)
        assertTrue((limited as PublicPersonRateLimited).retryAtElapsedMillis >= start + 120_000)
        val alsoLimited = runCatching { service.fetch("Another", false, "tt123") }.exceptionOrNull()
        assertEquals(limited.retryAtElapsedMillis, (alsoLimited as PublicPersonRateLimited).retryAtElapsedMillis)
        assertEquals(1, requireNotNull(service.fetch("Example", false, "tt456")).movieCredits.size)
        assertEquals(listOf("en.wikipedia.org", "query.wikidata.org", "en.wikipedia.org", "qlever.dev", "qlever.dev", "query.wikidata.org"), hosts)
    }

    @Test fun wikidataBackoffAllowsBiographyLookupAndSkipsBlockedCreditRequests() = runBlocking {
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            hosts += url.host
            val limited = url.host == "query.wikidata.org"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (limited) 503 else 200).message("Fixture").header("Retry-After", "120")
                .body(biography("Example").toResponseBody()).build()
        }.build()
        val service = PublicPersonService(client)
        val first = runCatching { service.fetch("Example", false, "tt123") }.exceptionOrNull() as PublicPersonRateLimited
        val second = runCatching { service.fetch("Other", false, "tt234") }.exceptionOrNull() as PublicPersonRateLimited
        assertEquals(first.retryAtElapsedMillis, second.retryAtElapsedMillis)
        assertEquals(listOf("en.wikipedia.org", "query.wikidata.org", "qlever.dev", "en.wikipedia.org", "qlever.dev"), hosts)
    }
    @Test fun newCastPageGetsOtherFilmsDuring120SecondBackoffWithoutDuplicateLookups() = runBlocking {
        val hosts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            hosts += request.url.host
            val replica = request.url.host == "qlever.dev"
            if (replica) {
                val query = requireNotNull(request.url.queryParameter("query"))
                assertTrue(query.contains("tt123"))
                assertTrue(query.contains("\"Example\"@en"))
                assertTrue(query.contains("COUNT(DISTINCT ?candidate) = 1"))
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (replica) 200 else 429).message("Fixture").header("Retry-After", "120")
                .body(if (replica) oneCredit.replace("tt123", "tt789").toResponseBody() else "".toResponseBody()).build()
        }.build()
        val service = PublicPersonService(client)
        val pages = List(3) { async { service.fetch("Example", false, "tt123") } }.awaitAll()
        assertTrue(pages.all { it?.movieCredits?.singleOrNull()?.id == "tt789" })
        assertEquals(listOf("en.wikipedia.org", "qlever.dev"), hosts)
    }

}
