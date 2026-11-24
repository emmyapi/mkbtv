package com.nuvio.tv.core.tmdb

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Public, name-based fallback when native person metadata is unavailable. No account or API key. */
@Singleton
class PublicPersonService internal constructor(client: OkHttpClient) {
    @Inject constructor(client: OkHttpClient, diskCache: PublicPersonDiskCache) : this(client) {
        this.diskCache = diskCache
    }
    private var diskCache: PublicPersonDiskCache? = null
    private val http = client.newBuilder()
        .callTimeout(25, TimeUnit.SECONDS)
        // The base client's application interceptor sets a generic User-Agent.
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "MkbTV/1.0 (Android TV; public person metadata)").build())
        }.build()
    private val retryAtByHost = mutableMapOf<String, Long>()
    private data class Summary(val entity: String, val biography: String, val page: String, val description: String)
    private val summaries = object : LinkedHashMap<Pair<String, Boolean>, List<Summary>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Boolean>, List<Summary>>?) = size > 64
    }
    private val cache = object : LinkedHashMap<String, PersonDetail>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PersonDetail>?) = size > 64
    }

    // Concurrent opens of the same person share the completed lookup and its provider cooldown.
    private val lookupLocks = Array(32) { Mutex() }

    suspend fun fetch(name: String, preferCrew: Boolean, sourceContentId: String): PersonDetail? = withContext(Dispatchers.IO) {
        val normalized = name.trim().takeIf { it.isNotEmpty() && it.length <= 200 } ?: return@withContext null
        val sourceImdb = sourceContentId.takeIf { Regex("tt[0-9]+").matches(it) }
        val key = "$normalized:$preferCrew:${sourceImdb.orEmpty()}"
        lookupLocks[(key.hashCode() and Int.MAX_VALUE) % lookupLocks.size].withLock {
            lookup(normalized, preferCrew, sourceImdb, key)
        }
    }

    private suspend fun lookup(normalized: String, preferCrew: Boolean, sourceImdb: String?, key: String): PersonDetail? {
        synchronized(cache) { cache[key] }?.let { return it }
        diskCache?.get(key)?.let { detail ->
            synchronized(cache) { cache[key] = detail }
            return detail
        }
        return try {
            fetchPrimary(normalized, preferCrew, sourceImdb)?.also { rememberComplete(key, it) }
        } catch (error: PublicPersonRateLimited) {
            if (sourceImdb == null) throw error
            val result = try {
                fetchReplicaCredits(normalized, preferCrew, sourceImdb)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: throw error
            val summary = synchronized(summaries) { summaries[normalized to preferCrew] }?.singleOrNull { it.entity == result.person }
            buildDetail(normalized, result, summary).also { rememberComplete(key, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchPrimary(normalized: String, preferCrew: Boolean, sourceImdb: String?): PersonDetail? {
        var candidates = findSummaries(normalized, preferCrew)
        if (sourceImdb == null) {
            // Without an originating title, only accept one explicitly matching role.
            val roles = if (preferCrew) Regex("director|filmmaker|screenwriter|writer|producer|showrunner", RegexOption.IGNORE_CASE)
                else Regex("actor|actress", RegexOption.IGNORE_CASE)
            candidates = candidates.filter { roles.containsMatchIn(it.description) }
            if (candidates.size != 1) return null
        }
        if (candidates.isEmpty()) return null
        val result = fetchCredits(candidates.map { it.entity }, preferCrew, sourceImdb)
            ?: return null
        val summary = candidates.singleOrNull { it.entity == result.person } ?: return null
        return buildDetail(normalized, result, summary)
    }

    private fun rememberComplete(key: String, detail: PersonDetail) {
        if (detail.partialFilmography) return
        synchronized(cache) { cache[key] = detail }
        diskCache?.put(key, detail)
    }

    private fun buildDetail(name: String, result: CreditResult, summary: Summary?): PersonDetail {
        val creditSource = "Available credits: Wikidata (CC0), https://www.wikidata.org/wiki/${result.person}"
        return PersonDetail(
            tmdbId = 0, name = name, biography = summary?.let {
                "${it.biography}\n\nWikipedia contributors (CC BY-SA 4.0): ${it.page}\n" +
                    "https://creativecommons.org/licenses/by-sa/4.0/\n$creditSource"
            } ?: creditSource,
            birthday = null, deathday = null, placeOfBirth = null, profilePhoto = null,
            knownFor = summary?.description?.takeIf { it.isNotBlank() },
            movieCredits = result.credits.filter { it.type == ContentType.MOVIE },
            tvCredits = result.credits.filter { it.type == ContentType.SERIES },
            partialFilmography = result.partial
        )
    }

    private fun findSummaries(name: String, preferCrew: Boolean): List<Summary> {
        val key = name to preferCrew
        synchronized(summaries) { summaries[key] }?.let { return it }
        // MediaWiki uses a pipe to separate page titles. Never treat user-supplied text as extra titles.
        if ('|' in name || '\u001f' in name) return emptyList()
        val titles = if (preferCrew) listOf(name, "$name (director)", "$name (writer)")
            else listOf(name, "$name (actor)", "$name (actress)")
        val url = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("prop", "extracts|pageprops|info|description")
            .addQueryParameter("ppprop", "wikibase_item|disambiguation")
            .addQueryParameter("inprop", "url")
            .addQueryParameter("exintro", "1")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("exlimit", titles.size.toString())
            .addQueryParameter("titles", titles.joinToString("|")).build()
        val pages = readJson(url)?.optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        val found = (0 until pages.length()).mapNotNull { i ->
            val item = pages.getJSONObject(i)
            val props = item.optJSONObject("pageprops") ?: return@mapNotNull null
            val entity = props.optString("wikibase_item")
            val page = item.optString("canonicalurl")
            val extract = item.optString("extract")
            if (props.has("disambiguation") || !Regex("Q[1-9][0-9]*").matches(entity) ||
                !page.startsWith("https://en.wikipedia.org/wiki/") || extract.isBlank()) return@mapNotNull null
            Summary(entity, extract, page, item.optString("description"))
        }.distinctBy { it.entity }
        if (found.isNotEmpty()) synchronized(summaries) { summaries[key] = found }
        return found
    }

    private data class CreditResult(val person: String, val credits: List<MetaPreview>, val partial: Boolean)

    private suspend fun fetchCredits(entities: List<String>, preferCrew: Boolean, sourceImdb: String?): CreditResult? {
        val relation = if (preferCrew) "(wdt:P57|wdt:P58|wdt:P170)" else "(wdt:P161|wdt:P725)"
        val people = entities.joinToString(" ") { "wd:$it" }
        val origin = sourceImdb?.let { "?source wdt:P345 \"$it\" ; $relation ?person ." }.orEmpty()
        val uniquePerson = sourceImdb?.let {
            "FILTER NOT EXISTS { VALUES ?other { $people } FILTER (?other != ?person) " +
                "?other wdt:P31 wd:Q5 . ?otherSource wdt:P345 \"$it\" ; $relation ?other . }"
        }.orEmpty()
        // Each page both verifies the person's role in the originating title and returns credits.
        // It must identify exactly one human; ambiguous or incomplete public data is rejected.
        val query = """
            SELECT ?person ?work ?workLabel ?imdb ?series (MIN(?publication) AS ?date) WHERE {
              VALUES ?person { $people }
              ?person wdt:P31 wd:Q5 .
              $origin
              $uniquePerson
              OPTIONAL {
                ?work $relation ?person ; wdt:P345 ?imdb .
                FILTER(STRSTARTS(?imdb, "tt"))
                FILTER(EXISTS { ?work wdt:P31/wdt:P279* wd:Q11424 } ||
                       EXISTS { ?work wdt:P31/wdt:P279* wd:Q5398426 })
                BIND(EXISTS { ?work wdt:P31/wdt:P279* wd:Q5398426 } AS ?series)
                OPTIONAL { ?work wdt:P577 ?publication }
              }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en,mul". }
            } GROUP BY ?person ?work ?workLabel ?imdb ?series
              ORDER BY ?person ?work ?imdb ?series ?workLabel
        """.trimIndent()
        return readCreditPages("https://query.wikidata.org/sparql", query, entities)
    }

    private suspend fun fetchReplicaCredits(name: String, preferCrew: Boolean, sourceImdb: String): CreditResult? {
        // On Wikimedia backoff, resolve identity and credits together on the independent QLever index.
        // A title and credited role are required, and namesakes must resolve to exactly one human.
        if (name.any { it.isISOControl() }) return null
        val literal = name.replace("\\", "\\\\").replace("\"", "\\\"")
        val relation = if (preferCrew) "(wdt:P57|wdt:P58|wdt:P170)" else "(wdt:P161|wdt:P725)"
        val query = """
            PREFIX wdt: <http://www.wikidata.org/prop/direct/>
            PREFIX wd: <http://www.wikidata.org/entity/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            SELECT ?person ?work ?imdb ?workLabel ?series (MIN(?published) AS ?date) WHERE {
              { SELECT (SAMPLE(?candidate) AS ?person) WHERE {
                  ?source wdt:P345 "$sourceImdb" ; $relation ?candidate .
                  ?candidate wdt:P31 wd:Q5 ; (rdfs:label|skos:altLabel) "$literal"@en .
              } HAVING(COUNT(DISTINCT ?candidate) = 1) }
              ?work $relation ?person ; wdt:P345 ?imdb .
              { ?work wdt:P31/wdt:P279* wd:Q5398426 . BIND(true AS ?series) }
              UNION { ?work wdt:P31/wdt:P279* wd:Q11424 . BIND(false AS ?series) }
              OPTIONAL { ?work rdfs:label ?english . FILTER(LANG(?english) = "en") }
              OPTIONAL { ?work rdfs:label ?shared . FILTER(LANG(?shared) = "mul") }
              BIND(COALESCE(?english,?shared) AS ?workLabel)
              OPTIONAL { ?work wdt:P577 ?published }
            } GROUP BY ?person ?work ?imdb ?workLabel ?series ORDER BY ?person ?work ?imdb ?series ?workLabel
        """.trimIndent()
        return readCreditPages("https://qlever.dev/api/wikidata", query, null)
            ?.takeIf { it.credits.isNotEmpty() }
    }

    private suspend fun readCreditPages(endpoint: String, query: String, entities: List<String>?): CreditResult? {
        val credits = mutableListOf<MetaPreview>()
        var person: String? = null
        var partial = false
        val seenPageEnds = mutableSetOf<Pair<String?, String?>>()
        var offset = 0
        val pageSize = 200
        do {
            currentCoroutineContext().ensureActive()
            val url = endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("query", "$query LIMIT $pageSize OFFSET $offset")
                .addQueryParameter("format", "json").build()
            val rows = try {
                readJson(url)?.optJSONObject("results")?.optJSONArray("bindings")
            } catch (e: PublicPersonRateLimited) {
                if (credits.isEmpty()) throw e
                null
            } catch (e: IOException) {
                if (credits.isEmpty()) throw e
                null
            } catch (e: org.json.JSONException) {
                if (credits.isEmpty()) throw e
                null
            }
            if (rows == null) {
                if (credits.isEmpty()) return null
                partial = true
                break
            }
            if (rows.length() == 0) break
            val pagePerson = (0 until rows.length()).map {
                rows.getJSONObject(it).optJSONObject("person")?.optString("value")?.substringAfterLast('/')
            }.toSet().singleOrNull() ?: return null
            if (!Regex("Q[1-9][0-9]*").matches(pagePerson) || (entities != null && pagePerson !in entities) ||
                (person != null && pagePerson != person)) return null
            if (rows.length() == pageSize) {
                val last = rows.getJSONObject(pageSize - 1)
                val pageEnd = last.optJSONObject("work")?.optString("value") to
                    last.optJSONObject("imdb")?.optString("value")
                if (!seenPageEnds.add(pageEnd)) {
                    partial = true
                    break
                }
            }
            person = pagePerson
            credits += parseCredits(rows)
            offset += rows.length()
        } while (rows.length() == pageSize)
        return person?.let { CreditResult(it, credits.distinctBy { credit -> credit.id }, partial) }
    }

    private fun parseCredits(rows: JSONArray): List<MetaPreview> =
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val imdb = row.optJSONObject("imdb")?.optString("value") ?: return@mapNotNull null
            val title = row.optJSONObject("workLabel")?.optString("value") ?: return@mapNotNull null
            publicCreditPreview(imdb, title, row.optJSONObject("series")?.optString("value") == "true",
                row.optJSONObject("date")?.optString("value"))
        }

    private fun readJson(url: okhttp3.HttpUrl): JSONObject? {
        synchronized(retryAtByHost) {
            val until = retryAtByHost[url.host]
            if (until != null && android.os.SystemClock.elapsedRealtime() < until) throw PublicPersonRateLimited(until)
        }
        val request = Request.Builder().url(url)
            .header("Accept", if (url.host == "qlever.dev") "application/sparql-results+json" else "application/json").build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 429 || (response.code == 503 && response.header("Retry-After") != null)) {
                val delay = publicPersonRetryDelayMillis(response.header("Retry-After"), response.headers.getDate("Date")?.time ?: System.currentTimeMillis())
                val retryAt = android.os.SystemClock.elapsedRealtime() + delay
                val until = synchronized(retryAtByHost) {
                    maxOf(retryAtByHost[url.host] ?: 0L, retryAt).also { retryAtByHost[url.host] = it }
                }
                throw PublicPersonRateLimited(until)
            }
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            if (body.contentLength() > 2 * 1024 * 1024) return@use null
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val input = body.byteStream()
            while (output.size() <= 2 * 1024 * 1024) {
                val count = input.read(buffer, 0, minOf(buffer.size, 2 * 1024 * 1024 + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            if (bytes.size > 2 * 1024 * 1024) return@use null
            JSONObject(String(bytes, Charsets.UTF_8))
        }
    }
}

internal fun publicCreditPreview(imdb: String, title: String?, series: Boolean, date: String?): MetaPreview? {
    if (!Regex("tt[0-9]+").matches(imdb) || title.isNullOrBlank() || title.startsWith("http") || Regex("Q[0-9]+").matches(title)) return null
    return MetaPreview(
        id = imdb, imdbId = imdb, type = if (series) ContentType.SERIES else ContentType.MOVIE,
        name = title, poster = "https://images.metahub.space/poster/small/$imdb/img", posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = date?.take(4),
        imdbRating = null, genres = emptyList()
    )
}

/** Deadline is monotonic so wall-clock changes cannot trigger an early retry. */
class PublicPersonRateLimited(val retryAtElapsedMillis: Long) : Exception()

internal fun publicPersonRetryDelayMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long {
    value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { return it.coerceIn(1, 31_536_000) * 1000 }
    val date = value?.let {
        runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(it)?.time }.getOrNull()
    }
    return date?.let { (it - nowMillis).coerceAtLeast(1000) } ?: 60_000
}
