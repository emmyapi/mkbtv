package com.nuvio.tv.ui.screens.cast

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tmdb.PublicPersonRateLimited
import com.nuvio.tv.core.tmdb.PublicPersonService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CastDetailBackoffTest {
    private val publicService = mockk<PublicPersonService>()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun openFromToyStory(sourceId: String = "tt0114709"): CastDetailViewModel {
        val meta = mockk<MetaRepository>()
        every { meta.getCachedMeta("movie", sourceId) } returns null
        val watch = mockk<WatchProgressRepository>()
        every { watch.observeWatchedMovieIds() } returns flowOf(emptySet())
        val series = mockk<WatchedSeriesStateHolder>()
        every { series.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        val layout = mockk<LayoutPreferenceDataStore>()
        every { layout.posterCardCornerRadiusDp } returns flowOf(12)
        every { layout.customPosterUrlPattern } returns flowOf("")
        every { layout.customPosterEnabledScreens } returns flowOf(emptySet())
        val args = SavedStateHandle(mapOf(
            "personName" to "Tom Hanks", "sourceType" to "movie", "sourceContentId" to "tt0114709",
            "sourceId" to sourceId, "sourceTitle" to "Toy Story"
        ))
        return CastDetailViewModel(mockk(), mockk(), meta, publicService, mockk(),
            watch, series, layout, mockk(relaxUnitFun = true), args)
    }

    private fun CastDetailViewModel.currentPerson() = (uiState.value as CastDetailUiState.Success).personDetail

    @Test fun opensBeforeLookupAndEnrichesAfterProviderBackoff() = runTest {
        val enriched = PersonDetail(
            tmdbId = 0, name = "Tom Hanks", biography = "Verified biography", birthday = null,
            deathday = null, placeOfBirth = null, profilePhoto = null, knownFor = null,
            movieCredits = emptyList(), tvCredits = emptyList()
        )
        var requests = 0
        coEvery { publicService.fetch("Tom Hanks", false, "tt0114709") } coAnswers {
            if (++requests == 1) throw PublicPersonRateLimited(SystemClock.elapsedRealtime() + 120_000)
            enriched
        }
        val vm = openFromToyStory()
        try {
            val immediate = vm.currentPerson()
            assertTrue(immediate.partialFilmography)
            assertEquals("Toy Story", immediate.movieCredits.single().name)
            assertEquals("tt0114709", immediate.movieCredits.single().id)
            runCurrent()
            assertEquals(1, requests)
            assertEquals(immediate, vm.currentPerson())
            advanceTimeBy(119_999)
            runCurrent()
            assertEquals(1, requests)
            assertEquals(immediate, vm.currentPerson())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, requests)
            assertEquals("Verified biography", vm.currentPerson().biography)
            assertFalse(vm.currentPerson().partialFilmography)
            assertEquals("tt0114709", vm.currentPerson().movieCredits.single().id)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun enrichmentKeepsOneNavigableCreditWhenLibraryAndPublicIdsDiffer() = runTest {
        val libraryId = "library:123"
        val vm = openFromToyStory(libraryId)
        try {
            val source = vm.currentPerson()
            val remoteCredit = source.movieCredits.single().copy(
                id = "tt0114709", imdbId = null, poster = "https://example.org/poster.jpg"
            )
            coEvery { publicService.fetch("Tom Hanks", false, "tt0114709") } returns source.copy(
                movieCredits = listOf(remoteCredit), partialFilmography = false
            )
            runCurrent()
            val onlyCredit = vm.currentPerson().movieCredits.single()
            assertEquals(libraryId, onlyCredit.id)
            assertEquals("tt0114709", onlyCredit.imdbId)
            assertEquals(remoteCredit.poster, onlyCredit.poster)
            assertFalse(vm.currentPerson().partialFilmography)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun closingPersonPageCancelsPendingRetry() = runTest {
        coEvery { publicService.fetch(any(), any(), any()) } throws PublicPersonRateLimited(SystemClock.elapsedRealtime() + 120_000)
        val vm = openFromToyStory()
        runCurrent()
        assertTrue(vm.currentPerson().partialFilmography)
        vm.viewModelScope.cancel()
        advanceTimeBy(120_000)
        runCurrent()
        coVerify(exactly = 1) { publicService.fetch("Tom Hanks", false, "tt0114709") }
    }
}
