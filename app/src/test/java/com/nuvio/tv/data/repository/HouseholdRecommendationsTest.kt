package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HouseholdRecommendationsTest {
    private val origin = "http://192.0.2.10:8387/"
    private val token = "A".repeat(43)
    private val addonId = "dk.mkb.nuvio.discovery.p3"

    @Test fun onlyInstalledProfileAtExactPrivateOriginCanReceiveHistory() {
        val base = origin + token
        assertEquals("$base/session.json", householdSessionUrl(3, addonId, base, origin).toString())
        assertNull(householdSessionUrl(1, addonId, base, origin))
        assertNull(householdSessionUrl(3, "community.addon", base, origin))
        assertNull(householdSessionUrl(3, addonId, base, ""))
        assertNull(householdSessionUrl(3, addonId, base, origin + "subdir/"))
        for (bad in listOf(
            "http://example.org:8387/$token", "http://192.0.2.10:8000/$token",
            "http://user@192.0.2.10:8387/$token", "https://192.0.2.10:8387/$token",
            "$base?dest=other", "$base#fragment", "$base/manifest.json",
            origin + "extra/../" + token, origin + "small"
        )) assertNull(bad, householdSessionUrl(3, addonId, bad, origin))
    }

    private fun watch(type: String, id: String, at: Long, episode: Int? = null) =
        WatchedItem(contentId = id, contentType = type, title = "", watchedAt = at, episode = episode)

    @Test fun latestEpisodeRepresentsSeriesAndUnrelatedMetadataNeverCreatesASeed() {
        val input = listOf(
            watch("series", "tt1", 100, 1), watch("movie", "tmdb:2", 130),
            watch("tv", "tt1", 150, 2), watch("series", "unknown", 200),
            watch("channel", "tt3", 170), watch("movie", "tt4", 0),
            watch("movie", "tt5", Long.MAX_VALUE)
        )
        val actual = recentHouseholdWatches(input, 1000)
        assertEquals(listOf("tt1", "tmdb:2"), actual.map { it.contentId })
        assertEquals(2, actual.first().episode)
        assertEquals("series", actual.first().contentType)
        assertEquals(150L, actual.first().watchedAt)
    }

    @Test fun sendsAtMostOneHundredDistinctTitlesNewestFirst() {
        val items = (1L..110L).flatMap { listOf(watch("movie", "tt$it", it), watch("movie", "tt$it", 1)) }
        val rows = recentHouseholdWatches(items, 1000)
        assertEquals((110L downTo 11L).map { "tt$it" }, rows.map { it.contentId })
        assertEquals(rows, recentHouseholdWatches(items.reversed(), 1000))
    }
}
