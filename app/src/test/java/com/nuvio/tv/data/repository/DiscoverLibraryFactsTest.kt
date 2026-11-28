package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.LibraryEntry
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverLibraryFactsTest {
    private fun entry(id: String, type: String = "movie") = LibraryEntry(
        id = id, type = type, name = "Title $id", poster = null, background = null, logo = null,
        description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null
    )

    @Test fun everySavedIdentityIsFetchedOnceAcrossBatchesWithItsTypeAndKeyword() = runTest {
        val movie = entry("tmdb:25").copy(imdbId = "tt25")
        val series = entry("tt25", "series")
        val custom = entry("custom-id", "channel")
        val entries = (100..305).map { entry("tt$it") } + listOf(movie, movie, series, custom)
        val batches = mutableListOf<DiscoverLibraryRequest>()
        val result = collectDiscoverLibraryFacts(entries, "actor name") { request ->
            batches += request
            DiscoverLibraryReply(
                facts = request.titles.reversed().map { DiscoverLibraryFact(it, year = 2009) },
                availableCatalogs = setOf("popular"), releaseAvailable = batches.size != 2, snapshot = "generation"
            )
        }
        assertEquals(listOf(100, 100, 8), batches.map { it.titles.size })
        assertTrue(batches.all { it.keyword == "actor name" })
        assertEquals(DiscoverLibraryId("movie", "tt25", 25), result[movie]?.identity)
        assertEquals(DiscoverLibraryId("series", "tt25"), result[series]?.identity)
        assertEquals(2009, result[entries[205]]?.year)
        assertNull(result[custom])
        assertFalse(result.releaseAvailable)
        assertEquals(setOf("popular"), result.availableCatalogs)
    }

    @Test fun watchedOnlyNativeIdsResolveInTypeScopedBatches() = runTest {
        val result = collectDiscoverLibraryFacts(listOf(entry("tt100")), "", setOf("tmdb:42", "private-id"),
            setOf("tmdb:42", "tmdb:99")) { request ->
            assertEquals(4, request.titles.size)
            val facts = request.titles.map {
                val known = when (it) {
                    DiscoverLibraryId("movie", "tmdb:42") -> "tt100"
                    DiscoverLibraryId("series", "tmdb:42") -> "tt200"
                    else -> null
                }
                DiscoverLibraryFact(it, imdbId = known)
            }
            DiscoverLibraryReply(facts, emptySet(), true, "generation")
        }
        assertEquals(setOf("tmdb:42", "tt100"), result.watchedIds("movie", setOf("tmdb:42")))
        assertEquals(setOf("tmdb:42", "tt200"), result.watchedIds("series", setOf("tmdb:42")))
        assertTrue(result.missingWatchedIds("series", setOf("tmdb:99")))
    }

    @Test fun generationOrMembershipChangesCannotYieldAPartialLibrary() = runTest {
        val entries = (1..101).map { entry("tt$it") }
        var batch = 0
        val changed = runCatching {
            collectDiscoverLibraryFacts(entries, "") { request ->
                DiscoverLibraryReply(request.titles.map { DiscoverLibraryFact(it) }, emptySet(), true, (++batch).toString())
            }
        }
        assertTrue(changed.exceptionOrNull() is DiscoverSnapshotExpiredException)
        val unrelated = DiscoverLibraryFact(DiscoverLibraryId("series", "tt100"))
        val incomplete = runCatching {
            collectDiscoverLibraryFacts(entries, "") { request ->
                DiscoverLibraryReply(request.titles.map { unrelated }, emptySet(), true, "same")
            }
        }
        assertTrue(incomplete.exceptionOrNull() is IOException)
        assertEquals(DiscoverLibrarySnapshot.EMPTY, collectDiscoverLibraryFacts(listOf(entry("custom")), "") {
            error("Unsupported IDs must stay on the device")
        })
    }

    @Test fun chartsLeadRankingsAndRemainingTitlesUseDiscoverVoteAndBayesianOrdering() {
        val entries = (1..5).map { entry("tt$it") }
        fun fact(n: Int, votes: Int, score: Double, vararg ranks: Pair<String, Int>) = DiscoverLibraryFact(
            identity = entries[n - 1].discoverLibraryId()!!, imdbId = "tt$n", votes = votes,
            rating = score, weightedRating = score, ranks = mapOf(*ranks)
        )
        val all = DiscoverLibrarySnapshot(listOf(
            fact(1, 95000, 7.1), fact(2, 30, 6.5, "popular" to 1, "trending" to 5),
            fact(3, 50, 8.5, "trending" to 1, "imdb-top-250" to 2),
            fact(4, 60, 9.0, "imdb-top-250" to 2), fact(5, 75000, 8.2)
        ), setOf("trending", "popular", "imdb-top-250"), true)
        fun order(sort: String) = entries.sortedWith(all.comparator(sort)).map { it.id }
        assertEquals(listOf("tt3", "tt2", "tt1", "tt5", "tt4"), order("trending"))
        assertEquals(listOf("tt2", "tt1", "tt5", "tt4", "tt3"), order("popular"))
        assertEquals(listOf("tt4", "tt3", "tt5", "tt1", "tt2"), order("top_rated"))
    }
}
