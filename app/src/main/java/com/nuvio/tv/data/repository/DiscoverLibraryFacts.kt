package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.LibraryEntry
import com.squareup.moshi.JsonClass
import java.io.IOException
import java.util.Locale

internal const val LIBRARY_FACT_BATCH_LIMIT = 100
private val discoverIdPattern = Regex("tt[0-9]{1,12}|(?:tmdb|watchmode):[0-9]{1,12}")

@JsonClass(generateAdapter = true)
data class DiscoverLibraryId(val type: String, val id: String, val tmdbId: Int? = null)

internal fun LibraryEntry.discoverLibraryId(): DiscoverLibraryId? {
    val kind = when (type.trim().lowercase(Locale.ROOT)) {
        "movie" -> "movie"
        "series", "tv", "show" -> "series"
        else -> return null
    }
    val imdb = sequenceOf(id, imdbId).filterNotNull().firstOrNull { it.startsWith("tt") && discoverIdPattern.matches(it) }
    val native = id.takeIf { it.startsWith("tmdb:") && discoverIdPattern.matches(it) }
    val number = native?.substringAfter(':')?.toIntOrNull()?.takeIf { it > 0 } ?: tmdbId?.takeIf { it > 0 }
    if (imdb != null) return DiscoverLibraryId(kind, imdb, number)
    val uid = native ?: number?.let { "tmdb:$it" } ?: id.takeIf(discoverIdPattern::matches) ?: return null
    return DiscoverLibraryId(kind, uid)
}

@JsonClass(generateAdapter = true)
data class DiscoverLibraryFact(
    val identity: DiscoverLibraryId,
    val imdbId: String? = null,
    val canonicalId: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val ranks: Map<String, Int> = emptyMap(),
    val votes: Int? = null,
    val weightedRating: Double? = null,
    val keywordMatch: Boolean = false,
    val release: MovieRelease? = null,
    /** Public aliases verified for the media type in identity. */
    val aliases: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
internal data class DiscoverLibraryRequest(val titles: List<DiscoverLibraryId>, val keyword: String)

@JsonClass(generateAdapter = true)
internal data class DiscoverLibraryReply(
    val facts: List<DiscoverLibraryFact>,
    val availableCatalogs: Set<String>,
    val releaseAvailable: Boolean,
    val snapshot: String
)

class DiscoverLibrarySnapshot(
    facts: List<DiscoverLibraryFact>,
    val availableCatalogs: Set<String>,
    val releaseAvailable: Boolean
) {
    private val factsById = facts.associateBy { it.identity }

    operator fun get(entry: LibraryEntry): DiscoverLibraryFact? = factsById[entry.discoverLibraryId()]

    fun watchedIds(type: String, rawIds: Set<String>): Set<String> = buildSet {
        addAll(rawIds)
        for (id in rawIds) {
            if (id.startsWith("tmdb:")) factsById[DiscoverLibraryId(type, id)]?.imdbId?.let(::add)
        }
    }

    fun missingWatchedIds(type: String, rawIds: Set<String>): Boolean = rawIds.any {
        it.startsWith("tmdb:") && factsById[DiscoverLibraryId(type, it)]?.imdbId == null
    }

    fun comparator(sort: String): Comparator<LibraryEntry> {
        require(sort in setOf("trending", "popular", "top_rated"))
        val topRated = sort == "top_rated"
        val chart = if (topRated) "imdb-top-250" else sort
        return Comparator { first, second ->
            val a = get(first)
            val b = get(second)
            val aRank = a?.ranks?.get(chart)
            val bRank = b?.ranks?.get(chart)
            var order = nullsLast<Int>().compare(aRank, bRank)
            if (topRated && order == 0) {
                order = if (aRank != null) compareValues(b?.rating, a?.rating)
                        else compareValues(b?.weightedRating, a?.weightedRating)
            }
            if (order == 0 && (topRated || aRank == null)) order = compareValues(b?.votes, a?.votes)
            if (order != 0) order else compareDiscoverIdentity(first, a, second, b)
        }
    }

    companion object {
        val EMPTY = DiscoverLibrarySnapshot(emptyList(), emptySet(), false)
    }
}

private fun compareDiscoverIdentity(
    first: LibraryEntry, a: DiscoverLibraryFact?, second: LibraryEntry, b: DiscoverLibraryFact?
): Int {
    // Discover orders tied IMDb records by their numeric primary key. Source-only
    // rows have negative keys assigned in reverse (type, content ID) order.
    val firstIndexed = a?.votes != null
    val secondIndexed = b?.votes != null
    if (firstIndexed && secondIndexed) {
        return compareValues(a?.imdbId?.drop(2)?.toLongOrNull(), b?.imdbId?.drop(2)?.toLongOrNull())
    }
    if (firstIndexed != secondIndexed) return if (firstIndexed) 1 else -1
    val typeOrder = (b?.identity?.type ?: second.type).compareTo(a?.identity?.type ?: first.type)
    return if (typeOrder != 0) typeOrder else (b?.canonicalId ?: b?.identity?.id ?: second.id)
        .compareTo(a?.canonicalId ?: a?.identity?.id ?: first.id)
}

internal suspend fun collectDiscoverLibraryFacts(
    entries: List<LibraryEntry>,
    keyword: String,
    watchedMovies: Set<String> = emptySet(),
    watchedSeries: Set<String> = emptySet(),
    loadBatch: suspend (DiscoverLibraryRequest) -> DiscoverLibraryReply
): DiscoverLibrarySnapshot {
    val ids = buildSet {
        entries.mapNotNullTo(this) { it.discoverLibraryId() }
        // The existing metadata cache resolves TMDB history IDs in the forward direction.
        for ((kind, watched) in listOf("movie" to watchedMovies, "series" to watchedSeries)) {
            watched.filter { it.startsWith("tmdb:") && discoverIdPattern.matches(it) }
                .mapTo(this) { DiscoverLibraryId(kind, it) }
        }
    }
    if (ids.isEmpty()) return DiscoverLibrarySnapshot.EMPTY
    val facts = mutableListOf<DiscoverLibraryFact>()
    var snapshot: String? = null
    var catalogs: Set<String> = emptySet()
    var releaseAvailable = true
    for (batch in ids.chunked(LIBRARY_FACT_BATCH_LIMIT)) {
        val reply = loadBatch(DiscoverLibraryRequest(batch, keyword))
        if (reply.snapshot.isBlank() || reply.facts.size != batch.size ||
            reply.facts.mapTo(hashSetOf()) { it.identity } != batch.toSet()) {
            throw IOException("Incomplete Discover library response")
        }
        if (snapshot != null && snapshot != reply.snapshot) throw DiscoverSnapshotExpiredException()
        snapshot = reply.snapshot
        catalogs = reply.availableCatalogs
        releaseAvailable = releaseAvailable && reply.releaseAvailable
        facts.addAll(reply.facts)
    }
    return DiscoverLibrarySnapshot(facts, catalogs, releaseAvailable)
}
