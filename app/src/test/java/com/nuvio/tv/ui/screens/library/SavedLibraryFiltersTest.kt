package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.data.repository.DiscoverLibraryFact
import com.nuvio.tv.data.repository.DiscoverLibraryId
import com.nuvio.tv.data.repository.DiscoverLibrarySnapshot
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.MovieDistributionDates
import com.nuvio.tv.ui.screens.search.DiscoverPeriod
import com.nuvio.tv.ui.screens.search.DiscoverWatch
import com.nuvio.tv.ui.screens.search.ReleaseWindow
import io.mockk.coVerify
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SavedLibraryFiltersTest {
    @get:Rule val main = MainDispatcherRule()
    private val later = LOCAL_LIBRARY_LIST_KEY
    private val family = "local:playlist:family"
    private val first = film("tt01", "Blue", 12, mapOf(later to 12L, family to 55L))
        .copy(imdbRating = 8f, genres = listOf("Drama"), releaseInfo = "2020",
            movieDistributionDates = MovieDistributionDates("2019-12-01", "2020-03-01"))
    private val second = film("tt02", "Red", 31, mapOf(family to 31L)).copy(releaseInfo = "2015")
    private val series = film("tt03", "Green", 39, mapOf(later to 39L)).copy(type = "series")
    private val entries = listOf(first, second, series)

    private fun film(id: String, title: String, added: Long, lists: Map<String, Long>) = LibraryEntry(
        id = id, type = "movie", name = title, poster = null, background = null, logo = null,
        description = null, releaseInfo = null, imdbRating = null, genres = emptyList(),
        addonBaseUrl = null, listedAt = added, listKeys = lists.keys, listAddedAt = lists
    )

    private fun SavedLibraryFilters.results(index: DiscoverLibrarySnapshot = DiscoverLibrarySnapshot.EMPTY,
        searchedFor: String? = null) = apply(entries, index, searchedFor, setOf("tt01"), setOf("tt03"), currentYear = 2026)

    @Test fun recentlyAddedUsesFirstSaveInAllAndMembershipDateInsideAPlaylist() {
        assertEquals(listOf(series, second, first), SavedLibraryFilters().results())
        assertEquals(listOf(first, second), SavedLibraryFilters(playlist = family).results())
        assertEquals(listOf(series, first), SavedLibraryFilters(playlist = later).results())
        assertFalse(SavedLibraryFilters().needsIndex)
    }

    @Test fun movieFiltersComposeAndUseTypedHomeReleaseDatesOffline() {
        val filters = SavedLibraryFilters(playlist = family, type = "movie", genre = "Drama",
            minRating = 8, period = DiscoverPeriod.DECADE_2020, release = ReleaseWindow.HOME, watch = DiscoverWatch.WATCHED)
        assertEquals(listOf(first), filters.results())
        assertTrue(filters.copy(watch = DiscoverWatch.UNWATCHED).results().isEmpty())
        assertEquals(ReleaseWindow.ANY, filters.withType("series").release)
        assertEquals(listOf(series), SavedLibraryFilters(type = "series", watch = DiscoverWatch.WATCHED).results())
        assertTrue(SavedLibraryFilters(release = ReleaseWindow.CINEMA).results().isEmpty())
    }

    @Test fun keywordMatchesCurrentCastOrLocalTitleAndCatalogAndRatingIntersect() {
        val fact = DiscoverLibraryFact(DiscoverLibraryId("movie", "tt02"), year = 2026,
            genres = listOf("Comedy"), rating = 8.6, ranks = mapOf("popular" to 4), keywordMatch = true)
        val index = DiscoverLibrarySnapshot(listOf(fact), setOf("popular"), true)
        val filter = SavedLibraryFilters(genre = "Comedy", period = DiscoverPeriod.THIS_YEAR, minRating = 8,
            keyword = "Actor", catalog = "popular")
        assertEquals(listOf(second), filter.results(index, "Actor"))
        assertTrue(filter.results(index, "Different actor").isEmpty())
        assertEquals(listOf(first), SavedLibraryFilters(keyword = "blu").results(index, "Other"))
        assertFalse(filter.missingCatalog(index))
        assertTrue(filter.copy(catalog = "netflix").missingCatalog(index))
    }

    @Test fun datesPutUnknownLastInEitherDirection() {
        assertEquals(listOf(first, second, series), SavedLibraryFilters(sort = SavedLibrarySort.NEWEST).results())
        assertEquals(listOf(second, first, series), SavedLibraryFilters(sort = SavedLibrarySort.OLDEST).results())
    }

    @Test fun openingLibraryUsesTheWholeNativeLibraryEvenWhenATrackerIsConnected() = runTest {
        val f = LibraryViewModelTestFixture(showProvider = false)
        f.localItems.value = entries
        f.localTabs.value = listOf(LibraryListTab(family, "Family", LibraryListTab.Type.PERSONAL))
        runCurrent()
        val state = f.viewModel.uiState.value
        assertEquals(LibrarySourceMode.MDBLIST, state.configuredSource)
        assertEquals(LibrarySourceMode.LOCAL, state.sourceMode)
        assertEquals(SavedLibraryFilters(), state.savedFilters)
        assertEquals(listOf("tt03", "tt02", "tt01"), state.visibleItems.map { it.id })
        f.viewModel.setSavedFilters(SavedLibraryFilters(playlist = family))
        runCurrent()
        assertEquals(listOf("tt01", "tt02"), f.viewModel.uiState.value.visibleItems.map { it.id })
        f.profile.value = 2
        runCurrent()
        assertEquals(SavedLibraryFilters(), f.viewModel.uiState.value.savedFilters)
        coVerify(exactly = 0) { f.discover.libraryFacts(any(), any(), any(), any(), any()) }
    }
}
