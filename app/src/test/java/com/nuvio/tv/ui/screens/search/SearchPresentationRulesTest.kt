package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.data.repository.MovieRelease
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.MovieDistributionDates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPresentationRulesTest {

    @Test
    fun `movie poster matches normal search evidence and the active Discover page`() {
        val movie = requireNotNull(MetaPreviewDto(id = "tt1", name = "Movie").toDomainOrNull("movie"))
            .copy(movieDistributionDates = MovieDistributionDates(null, "2001-01-01"))
        val state = SearchUiState(movieReleases = mapOf("tt1" to MovieRelease("cinema", ageRating = "PG")))
        assertEquals("home", state.movieRelease(movie)?.status)
        assertEquals("home", state.discoverMovieRelease(movie)?.status)
        assertEquals(MovieRelease("cinema", ageRating = "PG"),
            state.copy(discoverReleaseWindow = ReleaseWindow.CINEMA).discoverMovieRelease(movie))
        assertEquals("upcoming", SearchUiState(discoverReleaseWindow = ReleaseWindow.UPCOMING).discoverMovieRelease(movie)?.status)
    }

    @Test
    fun `one character remains in discover without becoming a submitted search`() {
        assertEquals("", submittedSearchQuery("a"))
        assertTrue(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.IN_SEARCH,
                query = "a",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `minimum query leaves discover while live search is pending`() {
        assertEquals("ab", submittedSearchQuery(" ab "))
        assertFalse(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.IN_SEARCH,
                query = "ab",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `discover location off never enters discover mode`() {
        assertFalse(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.OFF,
                query = "",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `fresh Discover opens on Any All Any and Trending`() {
        val state = SearchUiState()
        assertEquals(listOf("any", "movie", "series"), discoverTypes)
        assertEquals("any", state.selectedDiscoverType)
        assertEquals("all", state.selectedDiscoverCatalogKey)
        assertEquals(null, state.selectedDiscoverGenre)
        assertEquals(DiscoverSort.TRENDING, state.discoverSort)
    }

    @Test
    fun `catalog menus expose only choices valid for the selected type`() {
        val streaming = listOf("all", "disney-plus", "netflix", "hbo", "prime-video", "hulu")
        fun keys(type: String) = DiscoverCatalogOption.entries.filter { it.availableFor(type) }.map { it.key }
        assertEquals(streaming, keys("any"))
        assertEquals(streaming + listOf("trending", "popular"), keys("movie"))
        assertEquals(keys("movie") + listOf("airing-today", "new-and-returning"), keys("series"))
    }
}
