package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.data.remote.dto.CatalogDescriptorDto
import com.nuvio.tv.data.repository.SeasonalHistory
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.catalogsForHomeSettings
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.domain.model.seasonalHomeOrder
import com.nuvio.tv.domain.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonalHomeTest {
    private val addon = AddonManifestDto(id = "dk.mkb.nuvio.discovery.p3", name = "Discovery", version = "1",
        catalogs = listOf(CatalogDescriptorDto("mixed", "seasonal", "Christmas")),
        seasonalOccurrence = "2027-christmas", seasonalValidUntil = 27_000L, configVersion = 1L
    ).toDomain("https://household.invalid/private")

    private fun card(id: String, type: ContentType = ContentType.MOVIE) =
        MetaPreview(id = id, name = id, type = type, poster = null, posterShape = PosterShape.POSTER,
            background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList())

    private val row = CatalogRow(addon.id, addon.name, addon.baseUrl, "seasonal", "Christmas",
        ContentType.UNKNOWN, "mixed", listOf(card("tt1"), card("tt2"), card("tt3", ContentType.SERIES)),
        seasonalOccurrence = addon.seasonalOccurrence, seasonalValidUntil = addon.seasonalValidUntil,
        seasonalRevision = addon.configVersion)
    private val history = SeasonalHistory(3, addon.baseUrl, emptyMap(), emptySet())

    @Test fun sameWindowAndProfileAreRequiredEvenWhenOffline() {
        assertTrue(row.validSeasonalWindow(addon, 3, 26_999L))
        assertFalse(row.validSeasonalWindow(addon, 3, 27_000L))
        assertFalse(row.validSeasonalWindow(addon.copy(seasonalOccurrence = "new-year"), 3, 26_999L))
        assertFalse(row.validSeasonalWindow(addon.copy(baseUrl = "other"), 3, 26_999L))
        assertFalse(row.validSeasonalWindow(addon, 2, 26_999L))
        assertNull(row.excludeSeasonalHistory(null))
        assertNull(row.excludeSeasonalHistory(history.copy(profileId = 2)))
    }

    @Test fun filteringKeepsOneCardThenNoneAndUndoRestoresEligibility() {
        val played = history.copy(watched = mapOf("tt1" to 10L), startedSeries = setOf("tt3"))
        assertEquals(listOf("tt2"), row.excludeSeasonalHistory(played)!!.items.map { it.id })
        assertTrue(row.excludeSeasonalHistory(played.copy(watched = mapOf("tt1" to 10L, "tt2" to 20L)))!!.items.isEmpty())
        assertEquals(row, row.excludeSeasonalHistory(history))
        assertEquals("series", row.items.last().apiType)
    }

    @Test fun rewatchAcceptsOnlyTheRecordedHistoricalWatchUnderEitherAlias() {
        val row = row.copy(items = listOf(card("tt1").copy(seasonalRewatchAt = 10L, seasonalTmdbId = "tmdb:1")))
        assertEquals(row, row.excludeSeasonalHistory(history.copy(watched = mapOf("tmdb:1" to 10L))))
        assertTrue(row.excludeSeasonalHistory(history.copy(watched = mapOf("tmdb:1" to 11L)))!!.items.isEmpty())
    }

    @Test fun removalOrRefillWaitsForFocusReleaseButCalendarExpiryWinsImmediately() {
        val refill = row.copy(items = listOf(card("tt4")))
        assertEquals(row, seasonalSnapshotForDisplay(refill, addon, 3, 25_000L, history, row))
        assertEquals(refill, seasonalSnapshotForDisplay(refill, addon, 3, 25_000L, history, null))
        val exhausted = addon.copy(catalogs = emptyList())
        assertEquals(row, seasonalSnapshotForDisplay(refill, exhausted, 3, 25_000L, history, row))
        assertNull(seasonalSnapshotForDisplay(refill, exhausted, 3, 25_000L, history, null))
        assertNull(seasonalSnapshotForDisplay(refill, addon, 3, 27_000L, history, row))
        assertNull(seasonalSnapshotForDisplay(refill, addon, 4, 25_000L, history, row))
    }

    @Test fun stableIdentitySupportsManualOrderAndSettingsDuringCalendarGaps() {
        val key = row.legacyKey()
        val order = listOf("other", key)
        assertEquals(listOf(key, "other"), seasonalHomeOrder(order))
        assertEquals(listOf(key, "other"), seasonalHomeOrder(order, listOf("other")))
        assertEquals(order, seasonalHomeOrder(order, order))
        assertEquals(row.stableKey(), row.copy(catalogName = "New Year").stableKey())
        val gap = addon.copy(catalogs = emptyList(), seasonalOccurrence = null)
        assertTrue(gap.catalogs.isEmpty())
        assertEquals("seasonal", gap.catalogsForHomeSettings().single().id)
        assertTrue(gap.copy(seasonalValidUntil = null).catalogsForHomeSettings().isEmpty())
    }
}
