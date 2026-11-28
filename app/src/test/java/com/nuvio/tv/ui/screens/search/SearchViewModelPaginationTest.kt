package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.data.repository.MovieRelease
import com.nuvio.tv.data.repository.MovieReleaseReply
import com.nuvio.tv.data.repository.DiscoverSource
import com.nuvio.tv.data.repository.DiscoverFailure
import com.nuvio.tv.data.repository.DiscoverServiceException
import com.nuvio.tv.data.repository.DiscoverSearchRequest
import com.nuvio.tv.data.repository.wire
import com.nuvio.tv.data.repository.DiscoverSearchPage
import com.nuvio.tv.data.repository.DiscoverSnapshotExpiredException
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.nuvio.tv.data.repository.NUVIO_CATALOG_ID
import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import com.nuvio.tv.core.tmdb.CastKeywordResults
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SearchHistoryDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

private const val CATALOG_ID = "top"
private const val ADDON_ID = "addon"

/**
 * A page belongs to the search run that requested it. These cover both ways that used to break:
 * paging read the live text field rather than the submitted query, and a late page merged into
 * whatever rows were current when it landed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelPaginationTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `paging asks for the submitted query, not what is currently typed`() = runTest {
        val repository = RecordingCatalogRepository()
        val viewModel = newViewModel(repository)

        viewModel.onEvent(SearchEvent.QueryChanged("wolf"))
        advanceUntilIdle()
        assertEquals("wolf", viewModel.uiState.value.submittedQuery)

        // Typed after the run started and never submitted. Paging must ignore it.
        viewModel.onEvent(SearchEvent.QueryChanged("wolf of wall street"))
        repository.queries.clear()

        viewModel.onEvent(SearchEvent.LoadMoreCatalog(viewModel.uiState.value.catalogRows.single().stableKey()))
        advanceUntilIdle()

        assertTrue(
            "paging made no request",
            repository.queries.isNotEmpty()
        )
        assertTrue(
            "paged the live field instead of the submitted query: ${repository.queries}",
            repository.queries.all { it == "wolf" }
        )
    }

    @Test
    fun `Nuvio search requests consecutive pages after a short response`() = runTest {
        val repository = RecordingCatalogRepository(rawPageSize = 19)
        val viewModel = newViewModel(repository, addonId = NUVIO_CATALOG_ID)
        viewModel.onEvent(SearchEvent.QueryChanged("star"))
        advanceUntilIdle()
        repeat(3) {
            viewModel.onEvent(SearchEvent.LoadMoreCatalog(viewModel.uiState.value.catalogRows.single().stableKey()))
            advanceUntilIdle()
        }
        assertEquals(listOf(0, 20, 40, 60), repository.paginatedOffsets)
        assertEquals(
            listOf("star", "star page 20", "star page 40", "star page 60"),
            viewModel.uiState.value.catalogRows.single().items.map { it.name }
        )
    }

    @Test
    fun `a fully hidden first page still pages the selected addon instance`() = runTest {
        val repository = RecordingCatalogRepository(firstPageRelease = "2099-01-01")
        val secondInstance = "https://second.example.test"
        val viewModel = newViewModel(repository, hideUnreleased = true, secondInstance = secondInstance)
        viewModel.onEvent(SearchEvent.QueryChanged("star"))
        advanceUntilIdle()
        val firstRows = viewModel.uiState.value.catalogRows
        assertEquals(2, firstRows.size)
        assertTrue(firstRows.all { it.items.isEmpty() && it.hasMore })

        val selected = firstRows.single { it.addonBaseUrl == secondInstance }
        viewModel.onEvent(SearchEvent.LoadMoreCatalog(selected.stableKey()))
        advanceUntilIdle()
        assertEquals(listOf(secondInstance), repository.pagedSources)
        val rows = viewModel.uiState.value.catalogRows
        assertTrue(rows.single { it.addonBaseUrl != secondInstance }.items.isEmpty())
        assertEquals(listOf("star page 100"), rows.single { it.addonBaseUrl == secondInstance }.items.map { it.name })
    }

    @Test
    fun `a page that arrives after a new search does not merge into the new rows`() = runTest {
        val repository = RecordingCatalogRepository(pageDelayMs = 500)
        val viewModel = newViewModel(repository)

        viewModel.onEvent(SearchEvent.QueryChanged("wolf"))
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.LoadMoreCatalog(viewModel.uiState.value.catalogRows.single().stableKey()))
        // Start the paging job and let it suspend inside the fake's delay. Without this the
        // dispatcher would still have it queued, and the query change below would cancel a request
        // that never ran, which is not the case being covered.
        runCurrent()

        // A new search supersedes the run the page belongs to while it is genuinely in flight.
        viewModel.onEvent(SearchEvent.QueryChanged("alpha"))
        advanceUntilIdle()

        assertEquals("alpha", viewModel.uiState.value.submittedQuery)
        val titles = viewModel.uiState.value.catalogRows.flatMap { row -> row.items.map { it.name } }
        assertFalse(
            "a page from the previous query merged into the current rows: $titles",
            titles.any { it.startsWith("wolf page") }
        )
    }

    @Test
    fun `Discover sends combined filters and pages the global order without querying addon catalogs`() = runTest {
        val repository = RecordingCatalogRepository()
        val requests = mutableListOf<DiscoverSearchRequest>()
        val viewModel = newViewModel(repository, discover = true, hideUnreleased = true,
            watchedMovieIds = MutableStateFlow(setOf("tt2", "tt1")), watchedSeriesIds = MutableStateFlow(setOf("tt3")),
            respond = { request ->
                requests += request
                if (request.cursor == null) {
                    DiscoverSearchPage(listOf(title("Z").copy(imdbRating = 7.5f), title("M").copy(imdbRating = 8f)), 3, "next")
                } else DiscoverSearchPage(listOf(title("A").copy(imdbRating = 9.5f)), 3, null)
            })
        viewModel.onEvent(SearchEvent.SelectDiscoverType("movie"))
        viewModel.onEvent(SearchEvent.SelectDiscoverCatalog("netflix"))
        viewModel.onEvent(SearchEvent.SelectDiscoverGenre("Drama"))
        viewModel.onEvent(SearchEvent.SelectDiscoverMinRating(7))
        viewModel.onEvent(SearchEvent.SelectDiscoverPeriod(DiscoverPeriod.DECADE_1990))
        viewModel.onEvent(SearchEvent.SelectDiscoverWatch(DiscoverWatch.UNWATCHED))
        viewModel.onEvent(SearchEvent.SelectDiscoverSort(DiscoverSort.TOP_RATED))
        viewModel.onEvent(SearchEvent.SetDiscoverKeyword("  An Actor  "))
        advanceUntilIdle()
        val request = DiscoverSearchRequest(profileId = 1, type = "movie", catalog = "netflix", genre = "Drama",
            keyword = "An Actor", period = "decade_1990", minRating = 7, watch = "unwatched", sort = "top_rated",
            hideUnreleased = true, watchedMovies = listOf("tt1", "tt2"), watchedSeries = emptyList())
        assertEquals(listOf(request), requests)
        assertEquals(3, viewModel.uiState.value.discoverTotal)
        assertEquals(listOf("Z", "M"), viewModel.uiState.value.discoverResults.map { it.id })
        viewModel.onEvent(SearchEvent.LoadNextDiscoverResults)
        viewModel.onEvent(SearchEvent.LoadNextDiscoverResults)
        advanceUntilIdle()
        assertEquals(listOf(request, request.copy(cursor = "next")), requests)
        assertEquals(listOf("Z", "M", "A"), viewModel.uiState.value.discoverResults.map { it.id })
        assertFalse(viewModel.uiState.value.discoverHasMore)
        viewModel.onEvent(SearchEvent.ResetDiscoverFilters)
        advanceUntilIdle()
        assertEquals("all", requests.last().catalog)
        assertEquals("trending", requests.last().sort)
        assertEquals("any", requests.last().type)
        assertEquals("", requests.last().keyword)
        assertTrue(requests.last().watchedMovies.isEmpty())
        assertTrue(repository.requests.isEmpty())
    }

    @Test
    fun `Discover submits a one-character keyword and clears it without changing ordinary Search`() = runTest {
        val requests = mutableListOf<DiscoverSearchRequest>()
        val viewModel = newViewModel(RecordingCatalogRepository(), discover = true) { request ->
            requests += request
            DiscoverSearchPage(emptyList(), 0, null)
        }
        viewModel.onEvent(SearchEvent.SetDiscoverKeyword(" X "))
        advanceUntilIdle()
        assertEquals("X", requests.single().keyword)
        assertEquals("X", viewModel.uiState.value.discoverKeyword)
        assertEquals("", submittedSearchQuery("X"))
        viewModel.onEvent(SearchEvent.SetDiscoverKeyword(" "))
        advanceUntilIdle()
        assertEquals(listOf("X", ""), requests.map { it.keyword })
    }

    @Test
    fun `one spoken Discover request applies title type decade and sort together`() = runTest {
        val requests = mutableListOf<DiscoverSearchRequest>()
        val viewModel = newViewModel(RecordingCatalogRepository(), discover = true) { request ->
            requests += request
            DiscoverSearchPage(emptyList(), 0, null)
        }
        viewModel.onEvent(SearchEvent.ApplyDiscoverVoiceQuery("søg efter Spiderman serie fra 90erne og sorter efter højest rating"))
        advanceUntilIdle()
        val first = requests.single().wire(2026)
        assertEquals("Spiderman", first.keyword)
        assertEquals("series", first.type)
        assertEquals(1990 to 1999, first.yearMin to first.yearMax)
        assertEquals("top_rated", first.sort)
        viewModel.onEvent(SearchEvent.ApplyDiscoverVoiceQuery("kun på Netflix"))
        advanceUntilIdle()
        assertEquals(listOf(first, first.copy(catalog = "netflix")), requests.map { it.wire(2026) })
    }

    @Test
    fun `a spoken keyword retains Discover criteria and cancelled recognition cannot replace it`() = runTest {
        val repository = RecordingCatalogRepository()
        val viewModel = newViewModel(repository, discover = true)
        viewModel.onEvent(SearchEvent.SelectDiscoverType("movie"))
        viewModel.onEvent(SearchEvent.SetDiscoverKeyword("Matrix"))
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.SelectDiscoverGenre("Action"))
        viewModel.onEvent(SearchEvent.SelectDiscoverPeriod(DiscoverPeriod.DECADE_2010))
        viewModel.onEvent(SearchEvent.SelectDiscoverSort(DiscoverSort.NEWEST))

        val context = mockk<Context>(relaxed = true)
        val listener = slot<RecognitionListener>()
        val recognizer = mockk<SpeechRecognizer>(relaxed = true)
        every { recognizer.setRecognitionListener(capture(listener)) } returns Unit
        val bundle = mockk<Bundle>()
        every { bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) } returns arrayListOf("  Spider-Man  ")

        mockkStatic(SpeechRecognizer::class)
        try {
            every { SpeechRecognizer.isRecognitionAvailable(context) } returns true
            every { SpeechRecognizer.createSpeechRecognizer(context) } returns recognizer
            val voice = VoiceSearch(context)
            val applyKeyword: (String) -> Unit = { viewModel.onEvent(SearchEvent.ApplyDiscoverVoiceQuery(it)) }
            voice.start(applyKeyword)
            assertTrue(voice.listening)
            listener.captured.onResults(bundle)
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals("Spider-Man", state.discoverKeyword)
            assertEquals(listOf("Spider-Man"), state.discoverResults.map { it.name })
            assertEquals("Action", state.selectedDiscoverGenre)
            assertEquals(DiscoverPeriod.DECADE_2010, state.discoverPeriod)
            assertEquals(DiscoverSort.NEWEST, state.discoverSort)
            assertFalse(voice.listening)

            voice.start(applyKeyword)
            voice.stop()
            every { bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) } returns arrayListOf("Cancelled")
            listener.captured.onResults(bundle)
            advanceUntilIdle()
            assertEquals("Spider-Man", viewModel.uiState.value.discoverKeyword)
        } finally {
            unmockkStatic(SpeechRecognizer::class)
        }
    }

    @Test
    fun `a delayed old page cannot overwrite a newer keyword or its count`() = runTest {
        lateinit var late: Continuation<DiscoverSearchPage>
        val viewModel = newViewModel(RecordingCatalogRepository(), discover = true, respond = { request ->
            when {
                request.cursor != null -> suspendCoroutine { late = it }
                request.keyword == "matrix" -> DiscoverSearchPage(listOf(title("Matrix")), 2, "matrix-2")
                else -> DiscoverSearchPage(emptyList(), 0, null)
            }
        })
        viewModel.onEvent(SearchEvent.SetDiscoverKeyword("matrix"))
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.LoadNextDiscoverResults)
        runCurrent()
        viewModel.onEvent(SearchEvent.SetDiscoverKeyword("batman"))
        assertTrue(viewModel.uiState.value.discoverResults.isEmpty())
        assertEquals(null, viewModel.uiState.value.discoverTotal)
        advanceUntilIdle()
        late.resume(DiscoverSearchPage(listOf(title("Old film")), 2, null))
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.discoverTotal)
        assertTrue(viewModel.uiState.value.discoverResults.isEmpty())
        assertFalse(viewModel.uiState.value.discoverError)
    }

    @Test
    fun `initial and continuation failures retry the same request and an expired snapshot starts fresh`() = runTest {
        val cursors = mutableListOf<String?>()
        val viewModel = newViewModel(RecordingCatalogRepository(), discover = true, respond = { request ->
            cursors += request.cursor
            when (cursors.size) {
                1 -> throw DiscoverServiceException(DiscoverFailure.CATALOG)
                3 -> throw IOException()
                2 -> DiscoverSearchPage(listOf(title("Old")), 2, "old-next", sources = listOf(DiscoverSource.JUSTWATCH))
                4 -> throw DiscoverSnapshotExpiredException()
                else -> DiscoverSearchPage(listOf(title("Fresh")), 1, null)
            }
        })
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.discoverError)
        assertEquals(DiscoverFailure.CATALOG, viewModel.uiState.value.discoverFailure)
        assertEquals(null, viewModel.uiState.value.discoverTotal)
        viewModel.onEvent(SearchEvent.RetryDiscover)
        advanceUntilIdle()
        assertEquals(listOf(DiscoverSource.JUSTWATCH), viewModel.uiState.value.discoverSources)
        viewModel.onEvent(SearchEvent.LoadNextDiscoverResults)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.discoverError)
        assertEquals(listOf(DiscoverSource.JUSTWATCH), viewModel.uiState.value.discoverSources)
        assertEquals(listOf("Old"), viewModel.uiState.value.discoverResults.map { it.id })
        assertEquals(2, viewModel.uiState.value.discoverTotal)
        viewModel.onEvent(SearchEvent.RetryDiscover)
        advanceUntilIdle()
        assertEquals(listOf(null, null, "old-next", "old-next", null), cursors)
        assertEquals(listOf("Fresh"), viewModel.uiState.value.discoverResults.map { it.id })
        assertEquals(1, viewModel.uiState.value.discoverTotal)
        assertFalse(viewModel.uiState.value.discoverError)
    }

    @Test
    fun `changing watch history reruns the full filter and switching profile clears its criteria and rows`() = runTest {
        val profile = MutableStateFlow(1)
        val movies = MutableStateFlow(setOf("tt12"))
        val shows = MutableStateFlow(setOf("tt34"))
        val requests = mutableListOf<DiscoverSearchRequest>()
        val viewModel = newViewModel(RecordingCatalogRepository(), discover = true, profile = profile,
            watchedMovieIds = movies, watchedSeriesIds = shows, respond = { request ->
                requests += request
                val watched = request.watchedMovies + request.watchedSeries
                DiscoverSearchPage(watched.map(::title), watched.size, null)
            })
        viewModel.onEvent(SearchEvent.SelectDiscoverWatch(DiscoverWatch.WATCHED))
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.discoverTotal)
        shows.value = emptySet()
        advanceUntilIdle()
        assertEquals(listOf("tt12"), viewModel.uiState.value.discoverResults.map { it.id })
        profile.value = 2
        advanceUntilIdle()
        assertEquals(2, requests.last().profileId)
        assertEquals("any", requests.last().watch)
        assertTrue(requests.last().watchedMovies.isEmpty())
        assertTrue(viewModel.uiState.value.discoverResults.isEmpty())
    }

    @Test
    fun `movie badges never hold the first title row and release filters preserve raw order and paging`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<MovieReleaseReply>()
        val calls = mutableListOf<List<String>>()
        val movies = listOf(title("tt1"), title("tmdb:2"), title("tt3"), title("local").copy(imdbId = "tt1"))
        val series = title("tt1").copy(type = ContentType.SERIES, rawType = "series")
        val repository = RecordingCatalogRepository(rowType = ContentType.UNKNOWN, itemsForPage = { _, skip ->
            if (skip == 0) movies + series else listOf(title("tt4"))
        })
        val viewModel = newViewModel(repository, releaseProfile = 1, releases = { _, ids ->
            calls += ids
            if (ids == listOf("tt4")) MovieReleaseReply(mapOf("tt4" to MovieRelease("home")), false)
            else gate.await()
        })
        viewModel.onEvent(SearchEvent.QueryChanged("films"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()
        val initial = viewModel.uiState.value
        assertEquals(movies + series, initial.catalogRows.single().items)
        assertFalse(initial.isSearching)
        assertEquals(listOf(listOf("tt1", "tmdb:2", "tt3")), calls)
        viewModel.onEvent(SearchEvent.SelectSearchReleaseWindow(ReleaseWindow.HOME))
        assertTrue(viewModel.uiState.value.searchReleasePending)
        assertTrue(viewModel.uiState.value.catalogRows.single().items.isEmpty())
        gate.complete(MovieReleaseReply(mapOf("tt1" to MovieRelease("home"), "tmdb:2" to MovieRelease("cinema"),
            "tt3" to MovieRelease("unknown")), false))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.searchReleasePending)
        val filtered = viewModel.uiState.value.catalogRows.single()
        assertEquals(listOf("tt1", "local"), filtered.items.map { it.id })
        assertEquals(null, viewModel.uiState.value.movieRelease(series))
        viewModel.onEvent(SearchEvent.LoadMoreCatalog(filtered.stableKey()))
        advanceUntilIdle()
        assertEquals(listOf("tt1", "local", "tt4"), viewModel.uiState.value.catalogRows.single().items.map { it.id })
        viewModel.onEvent(SearchEvent.SelectSearchReleaseWindow(ReleaseWindow.CINEMA))
        assertEquals(listOf("tmdb:2"), viewModel.uiState.value.catalogRows.single().items.map { it.id })
        viewModel.onEvent(SearchEvent.SelectSearchReleaseWindow(ReleaseWindow.ANY))
        assertEquals(movies + series + title("tt4"), viewModel.uiState.value.catalogRows.single().items)
        assertEquals(listOf(listOf("tt1", "tmdb:2", "tt3"), listOf("tt4")), calls)
    }

    @Test
    fun `stale release responses cannot add badges after changing query or profile`() = runTest {
        val late = mutableListOf<Continuation<MovieReleaseReply>>()
        val profile = MutableStateFlow(1)
        val repository = RecordingCatalogRepository(itemsForPage = { query, _ ->
            listOf(title(if (query == "alpha") "tt1" else "tt2"))
        })
        val viewModel = newViewModel(repository, releaseProfile = 1, profile = profile, releases = { _, _ ->
            suspendCoroutine { late += it }
        })
        viewModel.onEvent(SearchEvent.QueryChanged("alpha"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.QueryChanged("beta"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()
        assertEquals(2, late.size)
        late[0].resume(MovieReleaseReply(mapOf("tt1" to MovieRelease("home")), true))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.movieReleases.isEmpty())
        profile.value = 2
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.query.isEmpty())
        assertFalse(viewModel.uiState.value.movieReleasesEnabled)
        late[1].resume(MovieReleaseReply(mapOf("tt2" to MovieRelease("cinema")), false))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.movieReleases.isEmpty())
    }

    @Test
    fun `Discover release filters show partial results then refresh without mixing pages`() = runTest {
        val calls = mutableListOf<DiscoverSearchRequest>()
        val viewModel = newViewModel(RecordingCatalogRepository(), discover = true, releaseProfile = 1, respond = { request ->
            calls += request
            when {
                request.releaseWindow == "any" -> DiscoverSearchPage(emptyList(), 0, null)
                request.cursor != null -> DiscoverSearchPage(listOf(title("tt3")), 3, null)
                calls.count { it.releaseWindow == "home" } == 1 ->
                    DiscoverSearchPage(listOf(title("tt1")), 1, null, releasePending = true)
                else -> DiscoverSearchPage(listOf(title("tt1"), title("tt2")), 3, "v2:2")
            }
        })
        runCurrent()
        viewModel.onEvent(SearchEvent.SelectDiscoverType("series"))
        viewModel.onEvent(SearchEvent.SelectDiscoverReleaseWindow(ReleaseWindow.HOME))
        viewModel.ensureDiscoverLoaded()
        runCurrent()
        assertEquals("movie", calls.single().type)
        assertTrue(viewModel.uiState.value.discoverReleasePending)
        assertEquals(listOf("tt1"), viewModel.uiState.value.discoverResults.map { it.id })
        advanceUntilIdle()
        assertEquals(listOf("tt1", "tt2"), viewModel.uiState.value.discoverResults.map { it.id })
        assertFalse(viewModel.uiState.value.discoverReleasePending)
        viewModel.onEvent(SearchEvent.LoadNextDiscoverResults)
        advanceUntilIdle()
        assertEquals(listOf(null, null, "v2:2"), calls.map { it.cursor })
        assertEquals(listOf("tt1", "tt2", "tt3"), viewModel.uiState.value.discoverResults.map { it.id })
        viewModel.onEvent(SearchEvent.SelectDiscoverType("series"))
        advanceUntilIdle()
        assertEquals("any", calls.last().releaseWindow)
        assertEquals("series", calls.last().type)
    }

    @Test
    fun `watched and unwatched Discover read cached badges without scheduling provider lookups`() = runTest {
        val calls = mutableListOf<List<String>>()
        val viewModel = newViewModel(RecordingCatalogRepository(), discover = true, releaseProfile = 1,
            releases = { _, ids -> calls += ids; MovieReleaseReply(emptyMap(), false) }, respond = { request ->
                val cached = if (request.watch == "any") emptyMap() else mapOf("tt1" to MovieRelease("home"))
                DiscoverSearchPage(listOf(title("tt1"), title("tt2")), 2, null, movieReleases = cached)
            })
        runCurrent()
        for (filter in listOf(DiscoverWatch.WATCHED, DiscoverWatch.UNWATCHED)) {
            viewModel.onEvent(SearchEvent.SelectDiscoverWatch(filter))
            viewModel.ensureDiscoverLoaded()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals("home", state.movieRelease(state.discoverResults.first())?.status)
            assertEquals(null, state.movieRelease(state.discoverResults.last()))
            viewModel.requestDiscoverMovieReleases(state.discoverResults)
            advanceUntilIdle()
            assertTrue(calls.isEmpty())
        }
        viewModel.onEvent(SearchEvent.SelectDiscoverWatch(DiscoverWatch.ANY))
        advanceUntilIdle()
        assertEquals(listOf(listOf("tt1", "tt2")), calls)
    }

    @Test
    fun `retry on a partial release result resumes the failed continuation`() = runTest {
        val cursors = mutableListOf<String?>()
        val viewModel = newViewModel(RecordingCatalogRepository(), discover = true, releaseProfile = 1, respond = { request ->
            cursors += request.cursor
            when (cursors.size) {
                1 -> DiscoverSearchPage(listOf(title("tt1")), 2, "v:1", releasePending = true)
                2 -> throw IOException("temporary")
                else -> DiscoverSearchPage(listOf(title("tt2")), 2, null)
            }
        })
        runCurrent()
        viewModel.onEvent(SearchEvent.SelectDiscoverReleaseWindow(ReleaseWindow.HOME))
        viewModel.ensureDiscoverLoaded()
        runCurrent()
        viewModel.onEvent(SearchEvent.LoadNextDiscoverResults)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.discoverError)
        viewModel.onEvent(SearchEvent.RetryDiscover)
        advanceUntilIdle()
        assertEquals(listOf(null, "v:1", "v:1"), cursors)
        assertEquals(listOf("tt1", "tt2"), viewModel.uiState.value.discoverResults.map { it.id })
    }

    private fun title(name: String) = MetaPreview(id = name, type = ContentType.MOVIE, name = name, poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null, releaseInfo = null,
        imdbRating = null, genres = emptyList())

    /** Answers every query, recording what it was asked for and paging indefinitely. */
    private class RecordingCatalogRepository(
        private val pageDelayMs: Long = 0L,
        private val rawPageSize: Int = 100,
        private val firstPageRelease: String? = null,
        private val itemsForPage: ((String, Int) -> List<MetaPreview>)? = null,
        private val rowType: ContentType = ContentType.MOVIE
    ) : CatalogRepository {
        val requests = mutableListOf<Map<String, String>>()
        val queries = mutableListOf<String>()
        val paginatedOffsets = mutableListOf<Int>()
        val pagedSources = mutableListOf<String>()

        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean,
            posterScreen: com.nuvio.tv.core.poster.CustomPosterScreen
        ): Flow<NetworkResult<CatalogRow>> = flow {
            val query = extraArgs["search"].orEmpty()
            if (supportsSkip) {
                paginatedOffsets.add(skip)
                requests.add(extraArgs)
            }
            emit(NetworkResult.Loading)
            if (skip > 0) {
                queries.add(query)
                pagedSources.add(addonBaseUrl)
                if (pageDelayMs > 0) delay(pageDelayMs)
            }
            val row = row(addonId, catalogId, query, skip, skipStep)
            emit(NetworkResult.Success(row.copy(addonBaseUrl = addonBaseUrl,
                hasMore = supportsSkip && row.hasMore)))
        }

        private fun row(addonId: String, catalogId: String, query: String, skip: Int, skipStep: Int): CatalogRow = CatalogRow(
            addonId = addonId,
            addonName = "Addon",
            addonBaseUrl = "https://example.test",
            catalogId = catalogId,
            catalogName = catalogId,
            type = rowType,
            items = itemsForPage?.invoke(query, skip) ?: listOf(
                MetaPreview(
                    id = "${query}_$skip",
                    type = ContentType.MOVIE,
                    name = if (skip > 0) "$query page $skip" else query,
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = firstPageRelease.takeIf { skip == 0 },
                    imdbRating = null,
                    genres = listOf(if (skip > 0) "Drama" else "Action")
                )
            ),
            hasMore = true,
            skipStep = skipStep,
            nextSkip = skip + rawPageSize,
            supportsSkip = true
        )
    }

    private class SearchAddonRepository(private val addons: List<Addon>) : AddonRepository {
        override fun getInstalledAddons(): Flow<List<Addon>> = flowOf(addons)
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = error("unused")
        override suspend fun addAddon(url: String) = error("unused")
        override suspend fun removeAddon(url: String) = error("unused")
        override suspend fun setAddonOrder(urls: List<String>) = error("unused")
        override suspend fun setAddonEnabled(url: String, enabled: Boolean) = error("unused")
    }

    private fun newViewModel(
        repository: CatalogRepository,
        discover: Boolean = false,
        addonId: String = ADDON_ID,
        hideUnreleased: Boolean = false,
        secondInstance: String? = null,
        profile: MutableStateFlow<Int> = MutableStateFlow(1),
        watchedMovieIds: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        watchedSeriesIds: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        releaseProfile: Int? = null,
        releases: suspend (Int, List<String>) -> MovieReleaseReply = { _, _ -> MovieReleaseReply(emptyMap(), false) },
        respond: suspend (DiscoverSearchRequest) -> DiscoverSearchPage = {
            DiscoverSearchPage(listOf(title(it.keyword)), 1, null)
        }
    ): SearchViewModel {
        val addon = Addon(
            id = addonId,
            name = "Addon",
            version = "1",
            description = null,
            logo = null,
            baseUrl = "https://example.test",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = CATALOG_ID,
                    name = CATALOG_ID,
                    extra = listOf(CatalogExtra(name = "skip"), CatalogExtra(name = "search"), CatalogExtra(name = "genre", options = listOf("Action", "Drama")))
                )
            ),
            types = listOf(ContentType.MOVIE),
            resources = emptyList()
        )

        val layoutPreferences = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferences.discoverLocation } returns flowOf(if (discover) DiscoverLocation.IN_SEARCH else DiscoverLocation.OFF)
        every { layoutPreferences.posterCardWidthDp } returns flowOf(126)
        every { layoutPreferences.posterLabelsEnabled } returns flowOf(true)
        every { layoutPreferences.catalogAddonNameEnabled } returns flowOf(true)
        every { layoutPreferences.posterCardHeightDp } returns flowOf(189)
        every { layoutPreferences.posterCardCornerRadiusDp } returns flowOf(12)
        every { layoutPreferences.catalogTypeSuffixEnabled } returns flowOf(true)
        every { layoutPreferences.hideUnreleasedContent } returns flowOf(hideUnreleased)

        every { layoutPreferences.customPosterUrlPattern } returns flowOf("")
        every { layoutPreferences.customPosterEnabledScreens } returns flowOf(emptySet())

        val history = mockk<SearchHistoryDataStore>(relaxed = true)
        every { history.recentSearches } returns flowOf(emptyList())

        val watchProgress = mockk<WatchProgressRepository>()
        every { watchProgress.observeWatchedMovieIds() } returns watchedMovieIds

        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns watchedSeriesIds
        every { watchedSeries.observeForProfile(any()) } returns watchedSeriesIds

        return SearchViewModel(
            addonRepository = SearchAddonRepository(listOfNotNull(addon, secondInstance?.let { addon.copy(baseUrl = it) })),
            catalogRepository = repository,
            metaRepository = mockk(relaxed = true),
            searchArtwork = mockk(),
            discoverSearch = mockk {
                every { movieReleaseProfile } returns flowOf(releaseProfile)
                coEvery { movieReleases(any(), any()) } coAnswers { releases(firstArg(), secondArg()) }
                coEvery { search(any()) } coAnswers { respond(firstArg()) }
            },
            profileManager = mockk { every { activeProfileId } returns profile },
            castKeywordLookup = mockk { coEvery { search(any()) } returns CastKeywordResults() },
            layoutPreferenceDataStore = layoutPreferences,
            searchHistoryDataStore = history,
            watchProgressRepository = watchProgress,
            watchedSeriesStateHolder = watchedSeries,
            posterOptions = mockk<PosterOptionsController>(relaxed = true),
            serverCatalog = mockk { every { searchAddons } returns flowOf(emptyList()) },
            context = mockk<Context>(relaxed = true)
        )
    }
}
