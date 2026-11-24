package com.nuvio.tv.core.tmdb

import com.nuvio.tv.domain.model.MetaPreview
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class CastKeywordResults(val items: List<MetaPreview> = emptyList(), val truncated: Boolean = false)

/** Resolve an exact person name or alias, then search actual cast and voice credits on Wikidata. */
@Singleton
class CastKeywordLookup @Inject constructor(client: OkHttpClient) {
    private val http = client.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "MkbTV/1.0 (Android TV; public person metadata)").build())
        }
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = object : LinkedHashMap<String, CastKeywordResults>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CastKeywordResults>?) = size > 32
    }
    private val locks = Array(16) { Mutex() }
    private val retryAtByHost = mutableMapOf<String, Long>()

    suspend fun search(query: String): CastKeywordResults = withContext(Dispatchers.IO) {
        val name = query.trim().replace(WHITESPACE, " ")
        if (name.length !in 2..120 || name.none { it.isLetter() } || name.any { it.isISOControl() }) {
            return@withContext CastKeywordResults()
        }
        val key = name.lowercase(Locale.ROOT)
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            synchronized(cache) { cache[key] }?.let { return@withLock it }
            val result = try {
                val people = findPeople(name)
                if (people.isEmpty()) CastKeywordResults() else findCredits(people)
            } catch (e: SerializationException) {
                throw IOException("Invalid cast search response", e)
            }
            synchronized(cache) { cache[key] = result }
            result
        }
    }

    private suspend fun findPeople(name: String): List<String> {
        val url = "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "wbsearchentities")
            .addQueryParameter("format", "json")
            .addQueryParameter("language", "en")
            .addQueryParameter("limit", "8")
            .addQueryParameter("search", name).build()
        val matches = json.decodeFromString<NameResponse>(read(url, "application/json")).search.filter {
            ENTITY.matches(it.id) && (it.label.equals(name, true) || it.match?.text.equals(name, true))
        }
        val labels = matches.filter { it.label.equals(name, true) }
        return labels.ifEmpty { matches }.map { it.id }.distinct()
    }

    private suspend fun findCredits(people: List<String>): CastKeywordResults {
        // Only validated entity IDs enter SPARQL. Count the extra row to report truncation.
        val query = """
            PREFIX wdt: <http://www.wikidata.org/prop/direct/>
            PREFIX wd: <http://www.wikidata.org/entity/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX wikibase: <http://wikiba.se/ontology#>
            SELECT ?imdb ?series (MAX(IF(REGEX(STR(?label), "^Q[0-9]+$"), "", STR(?label))) AS ?workLabel) (MIN(?publication) AS ?date)
            WHERE {
              VALUES ?person { ${people.joinToString(" ") { "wd:$it" }} }
              ?person wdt:P31 wd:Q5 .
              ?work (wdt:P161|wdt:P725) ?person ; wdt:P345 ?imdb .
              { ?work wdt:P31/wdt:P279* wd:Q5398426 . BIND(true AS ?series) }
              UNION { ?work wdt:P31/wdt:P279* wd:Q11424 . BIND(false AS ?series) }
              OPTIONAL { ?work rdfs:label ?english . FILTER(LANG(?english) = "en") }
              OPTIONAL { ?work rdfs:label ?shared . FILTER(LANG(?shared) = "mul") }
              BIND(COALESCE(?english,?shared) AS ?label)
              OPTIONAL { ?work wdt:P577 ?publication }
              OPTIONAL { ?work wikibase:sitelinks ?links }
            } GROUP BY ?imdb ?series ORDER BY DESC(MAX(?links)) ?imdb LIMIT ${MAX_CREDITS + 1}
        """.trimIndent()
        val url = "https://qlever.dev/api/wikidata".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).build()
        val rows = json.decodeFromString<CreditResponse>(read(url, "application/sparql-results+json")).results.bindings
        return CastKeywordResults(
            items = rows.take(MAX_CREDITS).mapNotNull { row ->
                publicCreditPreview(row.imdb.value, row.workLabel?.value, row.series.value == "true", row.date?.value)
            }.distinctBy { it.id },
            truncated = rows.size > MAX_CREDITS
        )
    }

    private suspend fun read(url: HttpUrl, accept: String): String {
        val now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
        if (synchronized(retryAtByHost) { retryAtByHost[url.host]?.let { it > now } } == true) {
            throw IOException("Cast search temporarily unavailable")
        }
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(Request.Builder().url(url).header("Accept", accept).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (it.code == 429 || (it.code == 503 && it.header("Retry-After") != null)) {
                                val delay = publicPersonRetryDelayMillis(it.header("Retry-After"), it.headers.getDate("Date")?.time ?: System.currentTimeMillis())
                                val deadline = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + delay
                                synchronized(retryAtByHost) { retryAtByHost[url.host] = maxOf(retryAtByHost[url.host] ?: deadline, deadline) }
                            }
                            if (!it.isSuccessful) throw IOException("Cast search returned HTTP ${it.code}")
                            val source = it.body?.source() ?: throw IOException("Missing cast search response")
                            if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("Cast search response too large")
                            source.readUtf8()
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    @Serializable private data class NameResponse(val search: List<Candidate>)
    @Serializable private data class Candidate(val id: String, val label: String? = null, val match: Match? = null)
    @Serializable private data class Match(val text: String? = null)
    @Serializable private data class CreditResponse(val results: Bindings)
    @Serializable private data class Bindings(val bindings: List<Credit>)
    @Serializable private data class Credit(val imdb: Value, val series: Value, val workLabel: Value? = null, val date: Value? = null)
    @Serializable private data class Value(val value: String)

    private companion object {
        val ENTITY = Regex("Q[1-9][0-9]*")
        val WHITESPACE = Regex("\\s+")
        const val MAX_CREDITS = 1000
        const val MAX_RESPONSE_BYTES = 1024L * 1024L
    }
}
