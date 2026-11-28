package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.repository.DiscoverFailure
import com.nuvio.tv.data.repository.DiscoverServiceException
import com.nuvio.tv.data.repository.NuvioCatalogArtwork
import com.nuvio.tv.data.repository.DiscoverSearchApi
import com.nuvio.tv.data.repository.MovieRelease
import com.nuvio.tv.data.repository.movieReleaseId
import com.nuvio.tv.data.repository.DiscoverSearchRequest
import com.nuvio.tv.data.repository.DiscoverSnapshotExpiredException
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.CastKeywordLookup
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.core.poster.withCustomPosterUrls
import java.io.IOException
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SearchHistoryDataStore
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.catalogRowStableKey
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.core.util.filterReleasedItems
import com.nuvio.tv.domain.repository.AddonRepository
import java.time.LocalDate
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val searchHistoryDataStore: SearchHistoryDataStore,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    private val serverCatalog: ServerCatalog,
    private val discoverSearch: DiscoverSearchApi,
    private val searchArtwork: NuvioCatalogArtwork,
    private val profileManager: ProfileManager,
    private val castKeywordLookup: CastKeywordLookup,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Saved focus state for restoring scroll/focus position after returning from details. */
    var savedFocusRowKey: String? = null
    var savedFocusItemIndex: Int = -1
    var savedRowScrollPositions: Map<String, Pair<Int, Int>> = emptyMap()
    var hasSavedSearchFocus: Boolean = false

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    private val catalogsMap = linkedMapOf<String, CatalogRow>()
    private val catalogOrder = mutableListOf<String>()

    private var activeSearchJobs: List<Job> = emptyList()

    /**
     * Load-more jobs, tracked separately from [activeSearchJobs] because that list is replaced
     * wholesale by each search run. Cancelled alongside it in [cancelSearchRun].
     */
    private val activeLoadMoreJobs: MutableSet<Job> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var searchRunJob: Job? = null
    private var activeSearchQuery: String? = null
    private var searchGeneration = 0L
    private var rememberActiveSearch = false
    private var discoverJob: Job? = null
    private var catalogRowsUpdateJob: Job? = null
    private var suggestionJob: Job? = null
    private var liveSearchJob: Job? = null
    private var lastRequestKey: String? = null
    private var lastCompletedRequestKey: String? = null
    private var hasRenderedFirstCatalog = false
    private var pendingCatalogResponses = 0
    private var discoverCriteriaJob: Job? = null
    private var discoverGeneration = 0L
    private var discoverProfileId = profileManager.activeProfileId.value
    private var discoverRequest: DiscoverSearchRequest? = null
    private var discoverCursor: String? = null
    private var hideUnreleasedContent = false
    private var movieReleaseProfile: Int? = null
    private val movieReleaseLoader = MovieReleaseLoader(viewModelScope, discoverSearch::movieReleases, ::publishMovieReleases)
    private var discoverRefreshJob: Job? = null
    private var discoverRefreshAttempts = 0
    private var failedDiscoverRefresh = false

    private companion object {
        const val SUGGESTION_DEBOUNCE_MS = 150L

        /**
         * Live search fires while typing, but each run fans out to every enabled addon catalog, so
         * it waits longer than the suggestion debounce to avoid a request storm per keystroke.
         */
        const val LIVE_SEARCH_DEBOUNCE_MS = 350L

        const val MAX_SUGGESTIONS = 8
        /** Splits titles and queries into words. */
        private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
        const val MAX_RECENT_SEARCHES = 8
    }

    init {
        posterOptions.bind(viewModelScope)
        viewModelScope.launch {
            discoverSearch.movieReleaseProfile.collect { profile ->
                if (movieReleaseProfile == profile) return@collect
                movieReleaseProfile = profile
                resetReleaseLookups()
                val wasFiltered = _uiState.value.discoverReleaseWindow != ReleaseWindow.ANY
                _uiState.update { it.copy(movieReleasesEnabled = profile != null, searchReleaseWindow = ReleaseWindow.ANY,
                    discoverReleaseWindow = ReleaseWindow.ANY) }
                if (wasFiltered) restartDiscoverQuery()
                if (profile == null || profile == profileManager.activeProfileId.value && discoverProfileId == profile) {
                    updateCatalogRowsNow()
                    requestDiscoverMovieReleases(_uiState.value.discoverResults.take(12))
                }
            }
        }
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids -> _watchedMovieIds.value = ids }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.discoverLocation.distinctUntilChanged().collectLatest { location ->
                _uiState.update { it.copy(discoverLocation = location) }
                if (location == DiscoverLocation.OFF) {
                    clearDiscover()
                }
            }
        }
        viewModelScope.launch {
            profileManager.activeProfileId.collect { profileId ->
                if (profileId != discoverProfileId) {
                    val wasOpen = _uiState.value.discoverInitialized
                    discoverProfileId = profileId
                    cancelSearchRun()
                    liveSearchJob?.cancel()
                    suggestionJob?.cancel()
                    catalogRowsUpdateJob?.cancel()
                    resetCatalogAccumulator()
                    lastRequestKey = null
                    lastCompletedRequestKey = null
                    resetReleaseLookups()
                    _uiState.update { it.copy(query = "", submittedQuery = "", isSearching = false, error = null,
                        catalogRows = emptyList(), suggestions = emptyList(), searchReleaseWindow = ReleaseWindow.ANY,
                        movieReleasesEnabled = movieReleaseProfile == profileId) }
                    clearDiscover()
                    if (wasOpen) ensureDiscoverLoaded()
                }
            }
        }
        // Combine all layout preference flows into a single collector to reduce coroutine overhead
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterLabelsEnabled,
                layoutPreferenceDataStore.catalogAddonNameEnabled,
                layoutPreferenceDataStore.posterCardHeightDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp ->
                LayoutPrefs(widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp)
            }.collectLatest { prefs ->
                _uiState.update {
                    it.copy(
                        posterCardWidthDp = prefs.widthDp,
                        posterLabelsEnabled = prefs.labelsEnabled,
                        catalogAddonNameEnabled = prefs.addonNameEnabled,
                        posterCardHeightDp = prefs.heightDp,
                        posterCardCornerRadiusDp = prefs.cornerRadiusDp
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(layoutPreferenceDataStore.customPosterUrlPattern, layoutPreferenceDataStore.customPosterEnabledScreens) { pattern, screens ->
                patternForScreen(pattern, CustomPosterScreen.SEARCH, screens)
            }.distinctUntilChanged().collect { pattern ->
                _uiState.update { it.copy(discoverPosterPattern = pattern) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogTypeSuffixEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(catalogTypeSuffixEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.hideUnreleasedContent.collectLatest { enabled ->
                hideUnreleasedContent = enabled
                scheduleCatalogRowsUpdate()
            }
        }
        viewModelScope.launch {
            searchHistoryDataStore.recentSearches.collectLatest { recent ->
                _uiState.update { it.copy(recentSearches = recent.take(MAX_RECENT_SEARCHES)) }
            }
        }
    }

    private data class LayoutPrefs(
        val widthDp: Int,
        val labelsEnabled: Boolean,
        val addonNameEnabled: Boolean,
        val heightDp: Int,
        val cornerRadiusDp: Int
    )

    fun ensureDiscoverLoaded() {
        val state = _uiState.value
        if (state.discoverLocation == DiscoverLocation.OFF) return
        if (state.discoverInitialized) return
        _uiState.update { it.copy(discoverInitialized = true) }
        restartDiscoverQuery()
    }

    private val metaPrefetchedIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var metaPrefetchJob: Job? = null

    /**
     * Prefetch meta from addons in background when an item receives focus.
     * Warms the MetaRepository cache so the detail screen loads instantly.
     * Debounced to avoid flooding the network during rapid scrolling.
     */
    fun prefetchMetaOnFocus(id: String, type: String) {
        if (id.isBlank() || id in metaPrefetchedIds) return
        metaPrefetchJob?.cancel()
        metaPrefetchJob = viewModelScope.launch {
            delay(150)
            if (id in metaPrefetchedIds) return@launch
            metaPrefetchedIds.add(id)
            metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
            watchProgressRepository.getAllEpisodeProgress(id.substringBefore(":")).first()
        }
    }

    /**
     * Returns the cached backdrop URL from a previously prefetched meta, or null.
     */
    fun getCachedBackdrop(id: String, type: String): String? {
        return metaRepository.getCachedMeta(type, id)?.backdropUrl
    }

    fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> onQueryChanged(event.query)
            SearchEvent.SubmitSearch -> submitSearch()
            SearchEvent.RememberSearchFromTextInput -> rememberSearchFromTextInput()
            SearchEvent.ClearRecentSearches -> clearRecentSearches()
            is SearchEvent.RemoveRecentSearch -> removeRecentSearch(event.query)
            is SearchEvent.LoadMoreCatalog -> loadMoreCatalogItems(event.rowKey)
            is SearchEvent.SelectDiscoverType -> selectDiscoverType(event.type)
            is SearchEvent.SelectDiscoverCatalog -> selectDiscoverCatalog(event.catalogKey)
            is SearchEvent.SelectDiscoverGenre -> selectDiscoverGenre(event.genre)
            is SearchEvent.SetDiscoverKeyword -> setDiscoverKeyword(event.keyword)
            is SearchEvent.ApplyDiscoverVoiceQuery -> {
                applyDiscoverFilter { DiscoverVoiceQuery.apply(event.phrase, it) }
                ensureDiscoverLoaded()
            }
            is SearchEvent.SelectSearchReleaseWindow -> {
                if (_uiState.value.movieReleasesEnabled && _uiState.value.searchReleaseWindow != event.window) {
                    if (event.window != ReleaseWindow.ANY) movieReleaseLoader.retryUnsettled()
                    _uiState.update { it.copy(searchReleaseWindow = event.window) }
                    updateCatalogRowsNow()
                }
            }
            is SearchEvent.SelectDiscoverReleaseWindow -> {
                if (_uiState.value.movieReleasesEnabled) applyDiscoverFilter {
                    if (event.window != ReleaseWindow.ANY && it.selectedDiscoverType == "series") {
                        it.withDiscoverType("movie").copy(discoverReleaseWindow = event.window)
                    } else it.copy(discoverReleaseWindow = event.window)
                }
            }
            is SearchEvent.SelectDiscoverPeriod -> applyDiscoverFilter { it.copy(discoverPeriod = event.period) }
            is SearchEvent.SelectDiscoverMinRating -> applyDiscoverFilter { it.copy(discoverMinRating = event.rating) }
            is SearchEvent.SelectDiscoverSort -> applyDiscoverFilter { it.copy(discoverSort = event.sort) }
            is SearchEvent.SelectDiscoverWatch -> applyDiscoverFilter { it.copy(discoverWatch = event.watch) }
            SearchEvent.ResetDiscoverFilters -> resetDiscoverFilters()
            SearchEvent.RetryDiscover -> {
                if (_uiState.value.discoverError) {
                    if (discoverRequest == null) restartDiscoverQuery() else fetchDiscoverPage(refresh = failedDiscoverRefresh)
                } else if (_uiState.value.discoverReleasePending) {
                    discoverRefreshAttempts = 0
                    fetchDiscoverPage(refresh = true)
                }
            }
            SearchEvent.LoadNextDiscoverResults -> {
                if (discoverCursor != null && !_uiState.value.discoverError) fetchDiscoverPage()
            }
            SearchEvent.Retry -> {
                // An explicit retry must refetch even though nothing about the request changed.
                lastRequestKey = null
                lastCompletedRequestKey = null
                resetReleaseLookups()
                cancelSearchRun()
                performSearch(uiState.value.submittedQuery.ifBlank { uiState.value.query })
            }
        }
    }

    private fun onQueryChanged(query: String) {
        if (_uiState.value.query.trim() != query.trim()) resetReleaseLookups()
        _uiState.update {
            val trimmedInput = query.trim()
            // Narrow the strip to what still matches before anything is fetched, so a letter
            // that rules a title out drops it on that keystroke rather than a fetch later.
            // Narrowing does not clear the strip when every current title stops matching. The
            // fetch stays authoritative for an empty result.
            val narrowed = rankedSuggestions(it.suggestions, trimmedInput.lowercase())
            it.copy(
                query = query,
                suggestions = if (narrowed.isEmpty()) it.suggestions else narrowed,
                error = null,
                isSearching = false,
                // Keep whatever is on screen while a keystroke waits to run. Clearing here flashed
                // the no-results state on every letter, because on a remote each letter outlasts the
                // debounce. The screen renders skeleton rows for this window instead.
                catalogRows = if (trimmedInput.length < MIN_SEARCH_QUERY_LENGTH) emptyList() else it.catalogRows
            )
        }

        // Drop in-flight requests for the previous keystroke before scheduling the next run.
        cancelSearchRun()

        // Live search: results follow what you type, like mobile. Debounced because each run hits
        // every enabled addon catalog.
        liveSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length >= MIN_SEARCH_QUERY_LENGTH) {
            liveSearchJob = viewModelScope.launch {
                kotlinx.coroutines.delay(LIVE_SEARCH_DEBOUNCE_MS)
                performSearch(query, keepSuggestions = true)
            }
        } else {
            // Emptying the field has to retire the submitted query too. Leaving it set kept the
            // screen in its results state with nothing to show, instead of falling back to recent
            // searches, until the screen was rebuilt by navigating away and back.
            performSearch(query)
        }

        fetchSuggestions(trimmed)
    }

    /** Match rank for [title], lower being better, or null when it does not match [queryLower]. */
    private fun suggestionRank(title: String, queryLower: String): Int? {
        val titleLower = title.lowercase()
        if (titleLower == queryLower) return 0
        if (titleLower.startsWith(queryLower)) return 1
        if (titleLower.contains(queryLower)) return 2

        // Allow multi-word matches such as "wolf wall" -> "The Wolf of Wall Street".
        // Each query word must consume a different title word.
        val queryWords = queryLower.split(WORD_SEPARATOR).filter { it.isNotEmpty() }
        if (queryWords.size < 2) return null
        val unmatchedTitleWords = titleLower.split(WORD_SEPARATOR).filterTo(mutableListOf()) { it.isNotEmpty() }
        val everyWordMatches = queryWords.all { word ->
            val index = unmatchedTitleWords.indexOfFirst { it.startsWith(word) }
            if (index >= 0) unmatchedTitleWords.removeAt(index)
            index >= 0
        }
        return if (everyWordMatches) 3 else null
    }

    /**
     * The strip contents for [names], best match first, capped at [MAX_SUGGESTIONS].
     *
     * Titles of the same rank keep the order of [names], which is the addon's own relevance order.
     * Sorting them alphabetically put five titles ahead of "Jurassic Park" for "juras", and of
     * "Slow Horses" for "slo", past the few completions a TV keyboard shows.
     */
    private fun rankedSuggestions(names: Collection<String>, queryLower: String): List<String> =
        names
            .mapNotNull { name -> suggestionRank(name, queryLower)?.let { name to it } }
            .sortedBy { it.second }
            .map { it.first }
            .take(MAX_SUGGESTIONS)

    /**
     * Every catalog's titles, taken one position at a time: each catalog's first title, then each
     * one's second, and so on, in catalog order. Each catalog's best matches lead, and the result
     * does not depend on which catalog happened to answer first.
     */
    private fun mergedCatalogNames(namesByCatalog: Map<Int, List<String>>, catalogCount: Int): List<String> {
        val catalogs = (0 until catalogCount).mapNotNull { namesByCatalog[it] }
        val merged = LinkedHashSet<String>()
        for (position in 0 until (catalogs.maxOfOrNull { it.size } ?: 0)) {
            catalogs.forEach { names -> names.getOrNull(position)?.let(merged::add) }
        }
        return merged.toList()
    }

    private fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()

        if (query.length < MIN_SEARCH_QUERY_LENGTH) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }

        // Already searched, so a fetch would repeat itself and the strip is current. Leave it
        // standing: live search submits as the user types, so typing a space between words
        // trims back to the submitted query and lands here mid-query.
        if (query == _uiState.value.submittedQuery.trim() && _uiState.value.catalogRows.isNotEmpty()) {
            return
        }

        suggestionJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SUGGESTION_DEBOUNCE_MS)

            val addons = try {
                searchAddons()
            } catch (_: Exception) {
                return@launch
            }

            val allTargets = buildSearchTargets(addons)
            val firstAddonId = allTargets.firstOrNull()?.first?.id
            val searchTargets = if (firstAddonId != null) allTargets.filter { it.first.id == firstAddonId } else emptyList()
            if (searchTargets.isEmpty()) {
                _uiState.update { it.copy(suggestions = emptyList()) }
                return@launch
            }

            // Each catalog's titles in the order it returned them, keyed by its place in searchTargets.
            val namesByCatalog = java.util.concurrent.ConcurrentHashMap<Int, List<String>>()
            val queryLower = query.lowercase()
            val suggestionJobs = searchTargets.mapIndexed { catalogIndex, (addon, catalog) ->
                launch {
                    try {
                        catalogRepository.getCatalog(
                            addonBaseUrl = addon.baseUrl,
                            addonId = addon.id,
                            addonName = addon.displayName,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            type = catalog.apiType,
                            skip = 0,
                            skipStep = 100,
                            extraArgs = mapOf("search" to query),
                            supportsSkip = false,
                            posterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.SEARCH
                        ).collect { result ->
                            if (result is NetworkResult.Success && _uiState.value.query.trim() == query) {
                                val names = result.data.items.map { it.name }
                                val previous = namesByCatalog.put(catalogIndex, names)
                                // Catalog results arrive independently and are merged, so a batch
                                // whose titles all fail the filter ranks to nothing while the batch
                                // holding the match is still in flight. Only the settle below may
                                // empty the strip: an empty push tells the keyboard there are no
                                // completions, and it does not always take them back when the next
                                // batch lands.
                                if (names != previous) {
                                    val ranked = rankedSuggestions(
                                        mergedCatalogNames(namesByCatalog, searchTargets.size),
                                        queryLower
                                    )
                                    if (ranked.isNotEmpty()) {
                                        _uiState.update { it.copy(suggestions = ranked) }
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        // The settle below treats joinAll() as "collection is over".
                        throw e
                    } catch (_: Exception) {
                        // Ignore per-catalog errors for suggestions
                    }
                }
            }

            suggestionJobs.joinAll()

            // Every catalog job has completed, so this is the first point the query is known
            // to have no suggestions. Until here the strip keeps the previous query's titles
            // rather than blinking on every keystroke. It must be cleared here or it would go
            // on captioning text the field no longer contains.
            if (_uiState.value.query.trim() == query) {
                val ranked = rankedSuggestions(mergedCatalogNames(namesByCatalog, searchTargets.size), queryLower)
                _uiState.update { it.copy(suggestions = ranked) }
            }
        }
    }

    private fun submitSearch() {
        // An explicit submit just skips the remaining debounce; the live run would land anyway.
        liveSearchJob?.cancel()
        performSearch(_uiState.value.query, rememberToHistory = true)
    }

    /**
     * Moving from the text input into the results confirms the current query the same way
     * Done/submit does. Live search may already have results on screen without an explicit
     * submit; saving here
     * avoids losing useful history while still not recording every keystroke prefix.
     */
    private fun rememberSearchFromTextInput() {
        val state = _uiState.value
        val query = state.submittedQuery.trim().ifBlank { state.query.trim() }
        if (query.length < MIN_SEARCH_QUERY_LENGTH) return
        val hasRealResults = state.catalogRows.any { row ->
            row.items.any { item -> !item.id.startsWith("__placeholder_") }
        } || catalogsMap.values.any { row ->
            row.items.any { item -> !item.id.startsWith("__placeholder_") }
        }
        if (!hasRealResults) return
        viewModelScope.launch {
            searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
        }
    }

    private fun clearRecentSearches() {
        viewModelScope.launch {
            searchHistoryDataStore.clearRecentSearches()
        }
    }

    private fun removeRecentSearch(query: String) {
        viewModelScope.launch {
            searchHistoryDataStore.removeRecentSearch(query)
        }
    }

    private fun resetCatalogAccumulator() {
        catalogsMap.clear()
        catalogOrder.clear()
        hasRenderedFirstCatalog = false
        pendingCatalogResponses = 0
    }

    private fun cancelSearchRun() {
        searchGeneration++
        searchRunJob?.cancel()
        searchRunJob = null
        activeSearchJobs.forEach { it.cancel() }
        activeSearchJobs = emptyList()
        activeLoadMoreJobs.forEach { it.cancel() }
        activeLoadMoreJobs.clear()
        activeSearchQuery = null
    }

    /**
     * Identifies a search by everything that changes what it would return: the query, the released
     * filter, and the exact set of catalogs it would hit. Enabling an addon or flipping the filter
     * changes the key, so those still refetch.
     */
    private fun buildRequestKey(
        query: String,
        searchTargets: List<Pair<Addon, CatalogDescriptor>>
    ): String = buildString {
        append(query.lowercase())
        append('|')
        append(hideUnreleasedContent)
        append('|')
        append(
            searchTargets.joinToString(separator = "|") { (addon, catalog) ->
                "${addon.baseUrl}:${catalog.apiType}:${catalog.id}"
            }
        )
    }


    /**
     * @param keepSuggestions live search runs this on every keystroke, while the field is still
     * being typed into and the suggestion strip is the whole point. Those runs leave the strip
     * alone. A submit or a retry replaces the screen with results, which retires it.
     */
    private fun performSearch(
        rawQuery: String,
        rememberToHistory: Boolean = false,
        keepSuggestions: Boolean = false
    ) {
        val query = rawQuery.trim()
        if (!keepSuggestions) {
            suggestionJob?.cancel()
        }
        _uiState.update {
            it.copy(
                submittedQuery = submittedSearchQuery(query),
                query = rawQuery,
                suggestions = if (keepSuggestions) it.suggestions else emptyList()
            )
        }

        if (query.length < MIN_SEARCH_QUERY_LENGTH) {
            cancelSearchRun()
            catalogRowsUpdateJob?.cancel()
            resetCatalogAccumulator()
            lastRequestKey = null
            lastCompletedRequestKey = null
            _uiState.update {
                it.copy(
                    isSearching = false,
                    error = null,
                    catalogRows = emptyList()
                )
            }
            ensureDiscoverLoaded()
            return
        }

        // Submit can immediately follow the debounced live-search launch. Reuse an active run for
        // the same query, but cancel a different query's entire scope before starting this one.
        if (activeSearchQuery == query && searchRunJob?.isActive == true) {
            if (rememberToHistory) rememberActiveSearch = true
            return
        }
        cancelSearchRun()
        rememberActiveSearch = rememberToHistory
        val generation = searchGeneration
        activeSearchQuery = query

        val job = viewModelScope.launch {
            val addons = try {
                searchAddons()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == searchGeneration && activeSearchQuery == query) {
                    _uiState.update { it.copy(isSearching = false, error = e.message ?: context.getString(com.nuvio.tv.R.string.search_error_load_addons_failed)) }
                }
                return@launch
            }

            if (generation != searchGeneration || activeSearchQuery != query) return@launch

            val searchTargets = buildSearchTargets(addons)

            // Same query against the same catalogs, and that run either finished or is still
            // arriving, so there is nothing new to fetch. Without this, pressing Done after live
            // search had already run the query tore the rows down and refetched everything, and
            // deleting a letter then retyping it did the same. A run that was cancelled part way
            // is deliberately not counted, so it gets to finish rather than staying half filled.
            val requestKey = buildRequestKey(query, searchTargets)
            val alreadySatisfied = requestKey == lastRequestKey &&
                (requestKey == lastCompletedRequestKey || activeSearchJobs.any { it.isActive })
            if (alreadySatisfied) {
                updateCatalogRowsNow()
                // An explicit submit that lands on a query the live search already finished still
                // counts as a search the user confirmed, so remember it instead of skipping out
                // of the history save below.
                if (rememberActiveSearch && catalogsMap.values.any { row -> row.items.isNotEmpty() }) {
                    viewModelScope.launch {
                        searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
                    }
                }
                return@launch
            }
            lastRequestKey = requestKey

            // Committed to a new run: drop the previous query's work and accumulated rows.
            activeSearchJobs.forEach { it.cancel() }
            activeSearchJobs = emptyList()
            catalogRowsUpdateJob?.cancel()
            resetCatalogAccumulator()

            // Rows are left alone here. Clearing them produced an empty frame between the old
            // results and the placeholders below, which is the flash this screen used to show.
            _uiState.update { it.copy(isSearching = true, error = null, installedAddons = addons) }

            // Preserve addon manifest order.
            searchTargets.forEach { (addon, catalog) ->
                val key = catalogKey(
                    addonId = addon.id,
                    addonBaseUrl = addon.baseUrl,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                if (key !in catalogOrder) {
                    catalogOrder.add(key)
                }
            }

            // Emit placeholder rows with shimmer items so the UI shows
            // skeleton rows immediately instead of a spinner.
            val placeholderRows = searchTargets.map { (addon, catalog) ->
                val key = catalogKey(
                    addonId = addon.id,
                    addonBaseUrl = addon.baseUrl,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                val fakeItems = (0 until 8).map { i ->
                    MetaPreview(
                        id = "__placeholder_${key}_$i",
                        type = ContentType.fromString(catalog.apiType),
                        rawType = catalog.apiType,
                        name = " ",
                        poster = PLACEHOLDER_IMAGE_URL,
                        posterShape = PosterShape.POSTER,
                        background = null,
                        logo = null,
                        description = null,
                        releaseInfo = " ",
                        imdbRating = null,
                        genres = emptyList()
                    )
                }
                CatalogRow(
                    addonId = addon.id,
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = ContentType.fromString(catalog.apiType),
                    rawType = catalog.apiType,
                    items = fakeItems,
                    isLoading = true,
                    hasMore = false,
                    currentPage = 0,
                    supportsSkip = false,
                    skipStep = 0,
                    extraArgs = emptyMap()
                )
            }
            // Only shimmer when there is nothing real to look at. If the previous query's results
            // are still up, they stay until this query's results replace them, so refining a search
            // is a single swap rather than results -> shimmer -> results on every letter.
            _uiState.update { state ->
                val showingRealRows = state.catalogRows.any { row ->
                    row.items.firstOrNull()?.id?.startsWith("__placeholder_") != true
                }
                if (showingRealRows) state else state.copy(catalogRows = placeholderRows)
            }

            pendingCatalogResponses = searchTargets.size + 1
            val titleJobs = searchTargets.map { (addon, catalog) ->
                launch {
                    loadCatalog(addon, catalog, query, generation)
                }
            }
            val castJob = launch {
                // A pause while typing updates titles immediately. Give the name lookup a longer
                // debounce so navigating the TV keyboard does not search credits for each prefix.
                if (keepSuggestions) delay(750)
                loadCastRows(query, generation, searchTargets.isEmpty())
            }
            activeSearchJobs = titleJobs + castJob

            try {
                titleJobs.joinAll()
                // Moving focus into completed title results must not wait for the public cast provider.
                if (isCurrentSearch(generation, query) && catalogsMap.values.any { it.items.isNotEmpty() }) {
                    updateCatalogRowsNow()
                    _uiState.update { it.copy(isSearching = false) }
                }
                castJob.join()
            } finally {
                if (
                    generation == searchGeneration &&
                    activeSearchQuery == query &&
                    uiState.value.submittedQuery.trim() == query
                ) {
                    lastCompletedRequestKey = requestKey
                    _uiState.update { it.copy(isSearching = false) }
                    // Only explicit submit (or moving into results from the text input — see
                    // rememberSearchFromTextInput)
                    // writes history. Live search fires per keystroke and would otherwise record
                    // every prefix ("do", "dog") even when the user never confirmed the search.
                    if (rememberActiveSearch && catalogsMap.values.any { row -> row.items.isNotEmpty() }) {
                        viewModelScope.launch {
                            searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
                        }
                    }
                }
            }
        }
        searchRunJob = job
    }

    private suspend fun loadCastRows(query: String, generation: Long, noCatalogs: Boolean) {
        try {
            val result = castKeywordLookup.search(query)
            if (!isCurrentSearch(generation, query)) return
            if (result.items.isEmpty()) {
                if (noCatalogs) _uiState.update { it.copy(error = context.getString(R.string.search_error_no_catalogs)) }
                return
            }
            val pattern = patternForScreen(layoutPreferenceDataStore.customPosterUrlPattern.first(),
                CustomPosterScreen.SEARCH, layoutPreferenceDataStore.customPosterEnabledScreens.first())
            if (!isCurrentSearch(generation, query)) return
            val title = context.getString(if (result.truncated) R.string.cast_detail_partial_filmography else R.string.cast_detail_filmography)
            for ((type, items) in result.items.withCustomPosterUrls(pattern).groupBy { it.type }) {
                val row = CatalogRow(
                    addonId = "wikidata", addonName = "Wikidata", addonBaseUrl = "", catalogId = "cast",
                    catalogName = "$title · $query", type = type, items = items, hasMore = false
                )
                val key = row.stableKey()
                catalogOrder.add(key)
                catalogsMap[key] = row
            }
        } catch (_: IOException) {
            if (isCurrentSearch(generation, query) && catalogsMap.values.none { it.items.isNotEmpty() }) {
                _uiState.update { it.copy(error = context.getString(R.string.search_error_failed)) }
            }
        } finally {
            if (isCurrentSearch(generation, query)) {
                pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                scheduleCatalogRowsUpdate()
            }
        }
    }

    private suspend fun loadCatalog(
        addon: Addon,
        catalog: CatalogDescriptor,
        query: String,
        generation: Long
    ) {
        val key = catalogKey(addon.id, addon.baseUrl, catalog.apiType, catalog.id)
        val supportsSkip = catalog.supportsExtra("skip")
        val skipStep = catalog.searchSkipStep(addon.id)
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = 0,
            skipStep = skipStep,
            extraArgs = mapOf("search" to query),
            supportsSkip = supportsSkip,
            posterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.SEARCH
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    if (!isCurrentSearch(generation, query)) return@collect
                    catalogsMap[key] = result.data.withSearchPageOffset(0)
                    pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                    scheduleCatalogRowsUpdate()
                }
                is NetworkResult.Error -> {
                    if (!isCurrentSearch(generation, query)) return@collect
                    pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                    // A settled failure cannot reuse the previous query's row or a loading placeholder.
                    catalogOrder.remove(key)
                    if (catalogsMap.values.none { it.items.isNotEmpty() }) {
                        _uiState.update { it.copy(error = result.message ?: context.getString(com.nuvio.tv.R.string.search_error_failed)) }
                    }
                    scheduleCatalogRowsUpdate()
                }
                NetworkResult.Loading -> {
                    // No-op; screen shows global loading when empty.
                }
            }
        }
    }

    private fun isCurrentSearch(generation: Long, query: String): Boolean =
        generation == searchGeneration && uiState.value.submittedQuery.trim() == query

    /**
     * Pages one row of the current search. The page belongs to the search run that created it:
     * it is keyed on [SearchUiState.submittedQuery] rather than the live field, and every
     * assignment back into [catalogsMap] is gated on that run still being current, so a late page
     * cannot merge into a different query's rows.
     */
    private fun loadMoreCatalogItems(key: String) {
        val currentRow = catalogsMap[key] ?: return

        if (currentRow.isLoading || !currentRow.hasMore) {
            return
        }

        val generation = searchGeneration
        val query = uiState.value.submittedQuery.trim()
        if (query.isBlank()) {
            return
        }

        catalogsMap[key] = currentRow.copy(isLoading = true)
        scheduleCatalogRowsUpdate()

        val job = viewModelScope.launch {
            val nextSkip = currentRow.nextCatalogSkip()
            catalogRepository.getCatalog(
                addonBaseUrl = currentRow.addonBaseUrl,
                addonId = currentRow.addonId,
                addonName = currentRow.addonName,
                catalogId = currentRow.catalogId,
                catalogName = currentRow.catalogName,
                type = currentRow.apiType,
                skip = nextSkip,
                skipStep = currentRow.skipStep,
                extraArgs = mapOf("search" to query),
                supportsSkip = currentRow.supportsSkip,
                posterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.SEARCH
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        if (!isCurrentSearch(generation, query)) return@collect
                        val latestRow = catalogsMap[key] ?: return@collect
                        catalogsMap[key] = latestRow.mergeCatalogPage(result.data.withSearchPageOffset(nextSkip))
                        scheduleCatalogRowsUpdate()
                    }
                    is NetworkResult.Error -> {
                        clearRowLoading(key, generation, query)
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
        activeLoadMoreJobs.add(job)
        job.invokeOnCompletion { activeLoadMoreJobs.remove(job) }
    }

    /** Drops the loading flag for a row, but only while its search run is still current. */
    private fun clearRowLoading(key: String, generation: Long, query: String) {
        if (!isCurrentSearch(generation, query)) return
        val row = catalogsMap[key] ?: return
        if (!row.isLoading) return
        catalogsMap[key] = row.copy(isLoading = false)
        scheduleCatalogRowsUpdate()
    }

    private fun scheduleCatalogRowsUpdate() {
        catalogRowsUpdateJob?.cancel()
        catalogRowsUpdateJob = viewModelScope.launch {
            if (!hasRenderedFirstCatalog && catalogsMap.isNotEmpty()) {
                hasRenderedFirstCatalog = true
                updateCatalogRowsNow()
                return@launch
            }
            val debounceMs = when {
                pendingCatalogResponses > 5 -> 220L
                pendingCatalogResponses > 0 -> 140L
                else -> 90L
            }
            kotlinx.coroutines.delay(debounceMs)
            updateCatalogRowsNow()
        }
    }

    private fun updateCatalogRowsNow() {
        val state = _uiState.value
        val placeholders = state.catalogRows.filter { it.items.firstOrNull()?.id?.startsWith("__placeholder_") == true }
            .associateBy { it.stableKey() }
        val today = if (hideUnreleasedContent) LocalDate.now() else null
        val rows = catalogOrder.mapNotNull { key ->
            val row = catalogsMap[key] ?: return@mapNotNull placeholders[key]
            when {
                !row.hasMore && row.items.isEmpty() -> null
                today != null -> row.filterReleasedItems(today)
                else -> row
            }
        }
        val window = state.searchReleaseWindow
        val profile = movieReleaseProfile
        if (profile != null && profile == profileManager.activeProfileId.value && state.query.trim() == activeSearchQuery) {
            // All loaded candidates need classification for a filter. For ordinary browsing, seed
            // only the first viewport; scrolling queues subsequent visible cards.
            val candidates = if (window == ReleaseWindow.ANY) {
                rows.asSequence().take(3).flatMap { it.items.asSequence().take(8) }
            } else rows.asSequence().flatMap { it.items.asSequence() }
            movieReleaseLoader.request(profile, candidates.mapNotNull { it.movieReleaseId }, revisit = false)
        }
        val visible = if (window == ReleaseWindow.ANY) rows else {
            val status = window.name.lowercase()
            rows.mapNotNull { row ->
                val matches = row.items.filter { state.movieRelease(it)?.status == status }
                // Some addon catalogs contain mixed types. Preserve their movie matches and
                // their original server offset even when this whole page was filtered out.
                val canPageMovies = row.hasMore && (row.type == ContentType.MOVIE || row.type == ContentType.UNKNOWN ||
                    row.items.any { it.type == ContentType.MOVIE })
                if (matches.isNotEmpty() || canPageMovies) row.copy(items = matches) else null
            }
        }
        val pending = window != ReleaseWindow.ANY && rows.any { row ->
            row.items.any { it.movieReleaseId?.let(movieReleaseLoader::isPending) == true }
        }
        _uiState.update { it.copy(catalogRows = visible, searchReleasePending = pending) }
    }

    fun requestMovieReleases(items: List<MetaPreview>) {
        val profile = movieReleaseProfile ?: return
        if (profile == profileManager.activeProfileId.value && profile == discoverProfileId) {
            movieReleaseLoader.request(profile, items.asSequence().mapNotNull { it.movieReleaseId })
        }
    }

    fun requestDiscoverMovieReleases(items: List<MetaPreview>) {
        // The private watch filter may read facts already in the household index. Using its
        // result IDs to schedule provider requests would reveal which movies the viewer watched.
        if (_uiState.value.discoverWatch == DiscoverWatch.ANY) requestMovieReleases(items)
    }

    private fun publishMovieReleases(profile: Int, updates: Map<String, MovieRelease>) {
        if (profile != movieReleaseProfile || profile != profileManager.activeProfileId.value) return
        if (updates.isNotEmpty()) _uiState.update { it.copy(movieReleases = it.movieReleases + updates) }
        if (_uiState.value.searchReleaseWindow != ReleaseWindow.ANY) updateCatalogRowsNow()
    }

    private fun resetReleaseLookups() {
        movieReleaseLoader.reset()
        _uiState.update { it.copy(movieReleases = emptyMap(), searchReleasePending = false) }
    }

    private suspend fun searchAddons(): List<Addon> =
        addonRepository.getInstalledAddons().first().enabledAddons() + serverCatalog.searchAddons.first()

    internal suspend fun resolveDiscoverArtwork(item: MetaPreview): MetaPreview? = try {
        withTimeoutOrNull(6_000) { searchArtwork.resolve(item) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun selectDiscoverType(type: String) {
        if (type !in discoverTypes) return
        applyDiscoverFilter { it.withDiscoverType(type) }
    }

    private fun SearchUiState.withDiscoverType(type: String): SearchUiState {
        val catalog = DiscoverCatalogOption.entries.firstOrNull { it.key == selectedDiscoverCatalogKey && it.availableFor(type) }
        return copy(selectedDiscoverType = type, selectedDiscoverCatalogKey = (catalog ?: DiscoverCatalogOption.ALL).key,
            discoverReleaseWindow = if (type == "series") ReleaseWindow.ANY else discoverReleaseWindow)
    }

    private fun selectDiscoverCatalog(key: String) {
        if (DiscoverCatalogOption.entries.any { it.key == key && it.availableFor(_uiState.value.selectedDiscoverType) }) {
            applyDiscoverFilter { it.copy(selectedDiscoverCatalogKey = key) }
        }
    }

    private fun clearDiscover() {
        cancelDiscoverQuery()
        _uiState.update {
            it.copy(discoverInitialized = false, discoverLoading = false, discoverLoadingMore = false,
                discoverTotal = null, discoverSources = emptyList(), discoverHasMore = false, discoverResults = emptyList(), discoverFailure = null,
                selectedDiscoverType = DISCOVER_TYPE_ANY, selectedDiscoverCatalogKey = DiscoverCatalogOption.ALL.key,
                selectedDiscoverGenre = null, discoverKeyword = "", discoverPeriod = DiscoverPeriod.ANY,
                discoverMinRating = null, discoverWatch = DiscoverWatch.ANY, discoverSort = DiscoverSort.TRENDING,
                discoverReleaseWindow = ReleaseWindow.ANY, discoverReleasePending = false)
        }
    }

    private fun cancelDiscoverQuery() {
        discoverGeneration++
        discoverRefreshJob?.cancel()
        discoverRefreshAttempts = 0
        discoverCriteriaJob?.cancel()
        discoverJob?.cancel()
        discoverRequest = null
        discoverCursor = null
    }

    private fun setDiscoverKeyword(raw: String) {
        val keyword = raw.trim()
        applyDiscoverFilter { it.copy(discoverKeyword = keyword) }
        if (!_uiState.value.discoverInitialized) ensureDiscoverLoaded()
    }

    private fun resetDiscoverFilters() {
        applyDiscoverFilter {
            it.copy(selectedDiscoverType = DISCOVER_TYPE_ANY, selectedDiscoverCatalogKey = DiscoverCatalogOption.ALL.key,
                selectedDiscoverGenre = null, discoverKeyword = "", discoverPeriod = DiscoverPeriod.ANY,
                discoverMinRating = null, discoverWatch = DiscoverWatch.ANY, discoverSort = DiscoverSort.TRENDING,
                discoverReleaseWindow = ReleaseWindow.ANY, discoverReleasePending = false)
        }
    }

    private fun selectDiscoverGenre(genre: String?) {
        if (genre == null || genre in discoverGenres) applyDiscoverFilter { it.copy(selectedDiscoverGenre = genre) }
    }

    private fun applyDiscoverFilter(change: (SearchUiState) -> SearchUiState) {
        val previous = _uiState.value
        if (change(previous) == previous) return
        _uiState.update(change)
        if (_uiState.value.discoverInitialized) restartDiscoverQuery()
    }

    private fun restartDiscoverQuery() {
        val criteria = _uiState.value
        if (!criteria.discoverInitialized || criteria.discoverLocation == DiscoverLocation.OFF) return
        cancelDiscoverQuery()
        val profileId = discoverProfileId
        val generation = discoverGeneration
        clearDiscoverPage()
        if (_uiState.value.submittedQuery.isBlank()) resetReleaseLookups()
        discoverCriteriaJob = viewModelScope.launch {
            try {
                val watchMovies = criteria.discoverWatch != DiscoverWatch.ANY && criteria.selectedDiscoverType != "series"
                val watchSeries = criteria.discoverWatch != DiscoverWatch.ANY && criteria.selectedDiscoverType != "movie" && criteria.discoverReleaseWindow == ReleaseWindow.ANY
                val movies = if (watchMovies) watchProgressRepository.observeWatchedMovieIds() else flowOf(emptySet())
                val series = if (watchSeries) watchedSeriesStateHolder.observeForProfile(profileId) else flowOf(emptySet())
                combine(layoutPreferenceDataStore.hideUnreleasedContent, movies, series) { hide, seenMovies, seenSeries ->
                    DiscoverSearchRequest(
                        profileId = profileId, type = criteria.selectedDiscoverType,
                        catalog = criteria.selectedDiscoverCatalogKey, genre = criteria.selectedDiscoverGenre,
                        keyword = criteria.discoverKeyword, period = criteria.discoverPeriod.name.lowercase(),
                        minRating = criteria.discoverMinRating, watch = criteria.discoverWatch.name.lowercase(),
                        sort = criteria.discoverSort.name.lowercase(), hideUnreleased = hide,
                        watchedMovies = seenMovies.sorted(), watchedSeries = seenSeries.sorted(),
                        releaseWindow = criteria.discoverReleaseWindow.name.lowercase()
                    )
                }.distinctUntilChanged().collect { request ->
                    if (!isCurrentDiscover(generation, profileId)) return@collect
                    discoverJob?.cancel()
                    discoverJob = null
                    discoverRefreshJob?.cancel()
                    discoverRefreshAttempts = 0
                    discoverRequest = request
                    discoverCursor = null
                    clearDiscoverPage()
                    fetchDiscoverPage()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentDiscover(generation, profileId)) {
                    cancelDiscoverQuery()
                    showDiscoverError()
                }
            }
        }
    }

    private fun isCurrentDiscover(generation: Long, profileId: Int): Boolean =
        generation == discoverGeneration && profileId == profileManager.activeProfileId.value

    private fun clearDiscoverPage() {
        _uiState.update {
            it.copy(discoverLoading = true, discoverLoadingMore = false, discoverReleasePending = false, discoverResults = emptyList(),
                discoverTotal = null, discoverSources = emptyList(), discoverHasMore = false, discoverFailure = null)
        }
    }

    private fun showDiscoverError(failure: DiscoverFailure = DiscoverFailure.OTHER) {
        _uiState.update { it.copy(discoverLoading = false, discoverLoadingMore = false, discoverFailure = failure) }
    }

    private fun fetchDiscoverPage(refresh: Boolean = false) {
        val request = discoverRequest ?: return
        val generation = discoverGeneration
        if (discoverJob?.isActive == true || !isCurrentDiscover(generation, request.profileId)) return
        discoverRefreshJob?.cancel()
        var cursor = if (refresh) null else discoverCursor
        if (!refresh) _uiState.update { it.copy(discoverLoading = cursor == null, discoverLoadingMore = cursor != null, discoverFailure = null) }
        fun isCurrent() = isCurrentDiscover(generation, request.profileId) && discoverRequest === request
        discoverJob = viewModelScope.launch {
            try {
                val page = try {
                    discoverSearch.search(request.copy(cursor = cursor))
                } catch (expired: DiscoverSnapshotExpiredException) {
                    if (!isCurrent()) return@launch
                    if (cursor == null) throw expired
                    // A new snapshot must start with an empty grid and a fresh total.
                    cursor = null
                    discoverCursor = null
                    clearDiscoverPage()
                    discoverSearch.search(request)
                }
                if (!isCurrent()) return@launch
                if (cursor != null) {
                    val state = _uiState.value
                    val newIds = page.items.mapTo(hashSetOf()) { it.apiType to it.id }
                    check(state.discoverTotal == page.total && state.discoverResults.none { (it.apiType to it.id) in newIds })
                }
                discoverCursor = page.nextCursor
                _uiState.update {
                    it.copy(discoverResults = if (cursor == null) page.items else it.discoverResults + page.items,
                        discoverTotal = page.total, discoverSources = page.sources,
                        movieReleases = if (page.movieReleases.isEmpty()) it.movieReleases else it.movieReleases + page.movieReleases,
                        discoverHasMore = page.nextCursor != null, discoverReleasePending = page.releasePending,
                        discoverLoading = false, discoverLoadingMore = false, discoverFailure = null)
                }
                requestDiscoverMovieReleases(page.items.take(12))
                // A growing result set can change the order/total. Refresh the first page in place,
                // then preserve the snapshot once the viewer starts paging it.
                if (cursor == null && page.releasePending && discoverRefreshAttempts < 3) {
                    val wait = 2_000L shl discoverRefreshAttempts++
                    discoverRefreshJob = viewModelScope.launch {
                        delay(wait)
                        if (isCurrent()) fetchDiscoverPage(refresh = true)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrent()) {
                    failedDiscoverRefresh = refresh
                    showDiscoverError((error as? DiscoverServiceException)?.failure ?: DiscoverFailure.OTHER)
                }
            }
        }
    }

    private fun buildSearchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> {
        val allSearchTargets = addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog -> catalog.isSearchable() }
                .map { catalog -> addon to catalog }
        }

        return allSearchTargets
    }

    /**
     * A catalog is only searchable if a search is all it needs. One that also requires something we
     * cannot supply, a mandatory genre for instance, answers with an error or nothing at all, so
     * querying it just costs a request per keystroke and leaves a row that never fills in.
     */
    private fun CatalogDescriptor.isSearchable(): Boolean =
        supportsExtra("search") &&
            extra.none { property ->
                property.isRequired && !property.name.equals("search", ignoreCase = true)
            }

    private fun catalogKey(addonId: String, addonBaseUrl: String, type: String, catalogId: String): String {
        return catalogRowStableKey(addonId, addonBaseUrl, type, catalogId)
    }
}
