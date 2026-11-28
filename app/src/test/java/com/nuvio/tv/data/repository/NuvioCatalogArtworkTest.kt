package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NuvioCatalogArtworkTest {
    private val catalogUrl = "https://catalog.example"
    private val addon = mockk<Addon> {
        every { id } returns NUVIO_CATALOG_ID
        every { enabled } returns true
        every { baseUrl } returns catalogUrl
    }
    private val addons = mockk<AddonRepository> { every { getInstalledAddons() } returns flowOf(listOf(addon)) }
    private val metadata = mockk<MetaRepository>()
    private val artwork = NuvioCatalogArtwork(addons, metadata)
    private val suggestion = MetaPreview(id = "library:99", type = ContentType.MOVIE,
        name = "The Handmaiden", poster = "small.jpg", posterShape = PosterShape.POSTER, background = null,
        logo = null, description = null, releaseInfo = "2016", imdbRating = null, genres = emptyList(),
        imdbId = "tt4016934", sourceAddonBaseUrl = "https://library.example")

    @Test fun catalogHitUsesSamePosterAndPreservesTargetWithoutMetadataLookup() = runTest {
        val search = suggestion.copy(id = "tt4016934", poster = "w500.jpg", background = "original.jpg", logo = "logo.png")
        artwork.record(catalogUrl, listOf(search))
        val result = requireNotNull(artwork.resolve(suggestion))
        assertEquals(search.poster, result.poster)
        assertEquals(search.background, result.background)
        assertEquals(search.logo, result.logo)
        assertEquals(suggestion.id, result.id)
        assertEquals(suggestion.sourceAddonBaseUrl, result.sourceAddonBaseUrl)
        verify(exactly = 0) { metadata.getMeta(any(), any(), any()) }
    }

    @Test fun visibleIndexedTitleWaitsForTheSearchArtworkManifestAndKeepsItsDetailId() = runTest {
        val indexed = suggestion.copy(id = "tt4016934", poster = null, imdbId = null, sourceAddonBaseUrl = null)
        artwork.record(catalogUrl, listOf(indexed.copy(poster = "search-poster.jpg")))
        every { addons.getInstalledAddons() } returns flow {
            emit(emptyList())
            delay(100)
            emit(listOf(addon))
        }
        val result = requireNotNull(artwork.resolve(indexed))
        assertEquals("search-poster.jpg", result.poster)
        assertEquals(result.poster, result.rawPosterUrl)
        assertEquals(indexed.id, result.id)
        assertEquals(indexed.sourceAddonBaseUrl, result.sourceAddonBaseUrl)
        verify(exactly = 0) { metadata.getMeta(any(), any(), any()) }
    }

    private val series = suggestion.copy(id = "tt0903747", imdbId = null, type = ContentType.SERIES,
        rawType = "series", name = "Breaking Bad", poster = "result.jpg")

    @Test fun sparseSeriesPosterIsImmediateAndVisibleBadgeFillsFromDetailsOnce() = runTest {
        val cachedPoster = series.copy(poster = "catalog.jpg")
        artwork.record(catalogUrl, listOf(cachedPoster))
        assertEquals("catalog.jpg", artwork.resolve(series)?.poster)
        verify(exactly = 0) { metadata.getMeta(any(), any(), any()) }

        val detail = MetaDto(series.id, "series", series.name, status = "Ended", ageRating = "TV-MA").toDomain()
        every { metadata.getMeta(catalogUrl, "series", series.id) } returns flowOf(NetworkResult.Success(detail))
        val result = requireNotNull(artwork.resolve(series.copy(type = ContentType.TV), fillSeriesBadge = true))
        assertEquals("TV-MA", result.ageRating)
        assertEquals("Ended", result.status)
        assertEquals("catalog.jpg", result.poster)
        artwork.record(catalogUrl, listOf(cachedPoster))
        val partial = requireNotNull(artwork.resolve(series.copy(ageRating = "15"), fillSeriesBadge = true))
        assertEquals("15", partial.ageRating)
        assertEquals("Ended", partial.status)
        verify(exactly = 1) { metadata.getMeta(any(), any(), any()) }
    }

    @Test fun blankDetailPosterStillSuppliesSeriesStatusAndKeepsOriginalPoster() = runTest {
        val detail = MetaDto(series.id, "series", series.name, poster = " ", status = "Returning Series").toDomain()
        every { metadata.getMeta(catalogUrl, "series", series.id) } returns flowOf(NetworkResult.Success(detail))
        val result = requireNotNull(artwork.resolve(series, fillSeriesBadge = true))
        assertEquals(series.poster, result.poster)
        assertEquals("Returning Series", result.status)
        assertNull(result.ageRating)
        assertEquals(result, artwork.resolve(series, fillSeriesBadge = true))
        verify(exactly = 1) { metadata.getMeta(any(), any(), any()) }
    }

    @Test fun completeSeriesCatalogBadgeNeedsNoDetailLookup() = runTest {
        artwork.record(catalogUrl, listOf(series.copy(ageRating = "TV-Y", status = "Continuing")))
        val result = requireNotNull(artwork.resolve(series, fillSeriesBadge = true))
        assertEquals("TV-Y", result.ageRating)
        assertEquals("Continuing", result.status)
        verify(exactly = 0) { metadata.getMeta(any(), any(), any()) }
    }

    @Test fun sourceNativeSeriesIdGoesOnlyToItsOwnMetadataAddon() = runTest {
        val source = "https://series.example"
        val native = series.copy(id = "native:7", sourceAddonBaseUrl = source)
        val ownAddon = mockk<Addon> {
            every { id } returns "example.series"
            every { enabled } returns true
            every { baseUrl } returns source
            every { resources } returns listOf(AddonResource("meta", listOf("series"), listOf("native:")))
        }
        every { addons.getInstalledAddons() } returns flowOf(listOf(addon, ownAddon))
        val detail = MetaDto(native.id, "series", series.name, ageRating = "12").toDomain()
        every { metadata.getMeta(source, "series", native.id) } returns flowOf(NetworkResult.Success(detail))
        assertEquals("12", artwork.resolve(native, fillSeriesBadge = true)?.ageRating)
        verify(exactly = 0) { metadata.getMeta(catalogUrl, any(), any()) }
    }

    @Test fun coldLookupUsesExactTypedIdAndDoesNotUseOtherAddonsOrWrongTitle() = runTest {
        artwork.record("https://different.example", listOf(suggestion.copy(poster = "wrong.jpg")))
        val other = mockk<Meta> {
            every { id } returns "tt9999999"
            every { imdbId } returns "tt9999999"
            every { apiType } returns "movie"
        }
        every { metadata.getMeta(catalogUrl, "movie", "tt4016934") } returns flowOf(NetworkResult.Success(other))
        assertNull(artwork.resolve(suggestion))
        assertNull(artwork.resolve(suggestion))
        verify(exactly = 1) { metadata.getMeta(catalogUrl, "movie", "tt4016934") }
    }

    @Test fun tmdbRecommendationUsesProviderPosterAndKeepsItsTmdbNavigationId() = runTest {
        val canonical = mockk<Meta> {
            every { id } returns "tt4016934"
            every { imdbId } returns null
            every { apiType } returns "movie"
            every { poster } returns "w500.jpg"
            every { background } returns "wide.jpg"
            every { logo } returns null
            every { landscapePoster } returns null
        }
        val item = suggestion.copy(id = "tmdb:290098", imdbId = null, posterShape = PosterShape.LANDSCAPE)
        every { metadata.getMeta(catalogUrl, "movie", "tmdb:290098") } returns flowOf(NetworkResult.Success(canonical))
        val result = requireNotNull(artwork.resolve(item))
        assertEquals("w500.jpg", result.poster)
        assertEquals(PosterShape.POSTER, result.posterShape)
        assertEquals("tt4016934", result.imdbId)
        assertEquals("tmdb:290098", result.id)
        assertEquals(result, artwork.resolve(item))
        assertEquals(result, artwork.resolve(result))
        verify(exactly = 1) { metadata.getMeta(catalogUrl, "movie", "tmdb:290098") }
    }
}
