package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.repository.AddonRepository
import com.squareup.moshi.Moshi
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class DiscoverSearchApiTest {
    private val moshi = Moshi.Builder().build()
    private val request = DiscoverSearchRequest(profileId = 2, type = "series", catalog = "hbo",
        genre = "Drama", keyword = "Test Cast", period = "last_five_years", minRating = 7,
        watch = "watched", sort = "top_rated", hideUnreleased = true,
        watchedMovies = emptyList(), watchedSeries = listOf("tt12", "tmdb:42"))

    @Test fun sendsWholeCriteriaAndExactWatchIdsWithoutProfileOrEndpointInBody() {
        val wire = request.copy(cursor = "snapshot.1:60").wire(2026)
        val json = moshi.adapter(DiscoverRequestBody::class.java).toJsonValue(wire)
        assertEquals(mapOf("type" to "series", "catalog" to "hbo", "genre" to "Drama", "keyword" to "Test Cast",
            "year_min" to 2022.0, "year_max" to 2026.0, "min_rating" to 7.0, "watch" to "watched",
            "sort" to "top_rated", "hide_unreleased" to true, "watched_movies" to emptyList<String>(),
            "watched_series" to listOf("tt12", "tmdb:42"), "offset" to 60.0, "limit" to 60.0,
            "snapshot" to "snapshot.1"), moshi.adapter(Any::class.java).fromJson(
                moshi.adapter(Any::class.java).toJson(json)))
        assertTrue(request.copy(watch = "any").wire(2026).watchedSeries.isEmpty())
        for ((period, bounds) in mapOf("any" to (null to null), "before_2010" to (null to 2009),
            "this_year" to (2026 to 2026), "decade_1980" to (1980 to 1989))) {
            val body = request.copy(period = period).wire(2026)
            assertEquals(bounds, body.yearMin to body.yearMax)
        }
        for (bad in listOf("bad", "url/other:2", "s:0", "s:-1", "s:1:2", "s:2147483648")) {
            assertTrue(runCatching { request.copy(cursor = bad).wire(2026) }.exceptionOrNull() is IOException)
        }
    }

    @Test fun keepsOrderedNullableArtworkAndTypedIdsAcrossSnapshotPages() {
        val wire = request.copy(type = "any").wire(2026).copy(limit = 2)
        val reply = moshi.adapter(DiscoverReply::class.java).fromJson("""
            {"metas":[{"type":"series","name":"B","id":"tt2","poster":null},
                      {"type":"movie","name":"A","id":"tt1","imdbRating":"8.1"}],
             "total":3,"snapshot":"v2","next_offset":2,"coverage":"Catalog scope"}
        """.trimIndent())!!
        val page = reply.page(wire)
        assertEquals(listOf("tt2", "tt1"), page.items.map { it.id })
        assertTrue(page.items.all { it.sourceAddonBaseUrl == null && it.poster == null })
        assertEquals(8.1f, page.items.last().imdbRating)
        assertEquals(3, page.total)
        assertEquals("v2:2", page.nextCursor)
        assertTrue(page.sources.isEmpty())
        val coverage = "US subscription availability from Watchmode. JustWatch Nuvio sample. The source returned empty pages."
        assertEquals(listOf(DiscoverSource.JUSTWATCH), reply.copy(coverage = coverage).page(wire).sources)
        assertTrue(reply.copy(coverage = "US subscription availability from Watchmode.").page(wire).sources.isEmpty())
        val continuation = request.copy(cursor = page.nextCursor).wire(2026)
        val result = DiscoverReply(listOf(MetaPreviewDto(id = "tt3", name = "C", type = "series")),
            3, "v2", null).page(continuation)
        assertNull(result.nextCursor)
        assertEquals(3, result.total)
        assertTrue(DiscoverReply(emptyList(), 0, "empty", null).page(wire).items.isEmpty())
    }

    @Test fun nativeIdsWithoutArtworkKeepTheirOrderAndCount() {
        val reply = DiscoverReply(listOf(
            MetaPreviewDto(id = "watchmode:17", name = "Provider only", type = "movie"),
            MetaPreviewDto(id = "tmdb:42", name = "Catalog only", type = "series")
        ), 2, "s", null)
        val page = reply.page(request.copy(type = "any").wire(2026))
        assertEquals(listOf("watchmode:17", "tmdb:42"), page.items.map { it.id })
        assertTrue(page.items.all { it.poster == null })
        assertEquals(2, page.total)
    }

    @Test fun rejectsChangedSnapshotShortDuplicateAndInvalidRowsInsteadOfShrinkingTheTotal() {
        val wire = request.wire(2026).copy(limit = 1)
        val meta = MetaPreviewDto(id = "tt1", name = "Title", type = "series")
        val reply = DiscoverReply(listOf(meta), 2, "current", 1)
        val expired = runCatching { reply.page(wire.copy(snapshot = "previous")) }.exceptionOrNull()
        assertTrue(expired is DiscoverSnapshotExpiredException)
        for (bad in listOf(reply.copy(metas = emptyList()), reply.copy(nextOffset = null), reply.copy(total = -1),
            reply.copy(snapshot = "invalid/snapshot"), reply.copy(metas = listOf(meta.copy(id = null))),
            reply.copy(metas = listOf(meta.copy(type = "movie"))))) {
            assertTrue(runCatching { bad.page(wire) }.exceptionOrNull() is IOException)
        }
        val duplicate = reply.copy(metas = listOf(meta, meta), nextOffset = null)
        assertTrue(runCatching { duplicate.page(wire.copy(limit = 2)) }.exceptionOrNull() is IOException)
    }

    @Test fun translatesOnlyKnownServiceErrorsIntoDisplayableStates() {
        fun problem(body: String) = body.toResponseBody().discoverFailure(moshi)
        assertEquals(DiscoverFailure.CATALOG, problem("""{"error":"catalog_unavailable"}"""))
        assertEquals(DiscoverFailure.WATCH, problem("""{"error":"watch_unavailable"}"""))
        assertEquals(DiscoverFailure.OTHER, problem("""{"error":"private diagnostics"}"""))
        assertEquals(DiscoverFailure.OTHER, problem("<html>unavailable</html>"))
        assertEquals(DiscoverFailure.OTHER, problem(" ".repeat(257) + """{"error":"catalog_unavailable"}"""))
    }

    @Test fun onlyEnabledMatchingProfileAtExactHouseholdOriginCanBeUsed() = runTest {
        val origin = "http://192.0.2.10:8387/"
        val base = origin + "a".repeat(43)
        val addonId = "dk.mkb.nuvio.discovery.p2"
        assertEquals("$base/discover.json", discoverSearchUrl(2, addonId, base, origin).toString())
        assertNull(discoverSearchUrl(3, addonId, base, origin))
        assertNull(discoverSearchUrl(2, addonId, "$base/../other", origin))
        val ready = MutableStateFlow(false)
        val profile = MutableStateFlow(1)
        val manager = mockk<ProfileManager> {
            every { activeProfileId } returns profile
            every { activeProfileReady } returns ready
        }
        val addons = mockk<AddonRepository> { every { getInstalledAddons() } returns flowOf(emptyList()) }
        val api = DiscoverSearchApi(manager, addons, moshi)
        assertTrue(runCatching { api.search(request) }.exceptionOrNull() is IOException)
        ready.value = true
        assertTrue(runCatching { api.search(request) }.exceptionOrNull() is IOException)
        verify(exactly = 0) { addons.getInstalledAddons() }
        profile.value = 2
        assertTrue(runCatching { api.search(request) }.exceptionOrNull() is IOException)
        verify(exactly = 1) { addons.getInstalledAddons() }
    }
}
