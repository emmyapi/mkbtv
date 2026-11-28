package com.nuvio.tv.ui.components.posteroptions

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.repository.toLibraryEntryInput
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reusable controller for the long-press / hold-down poster options menu.
 *
 * Inject into a ViewModel via Hilt and call [bind] from the VM's init with
 * `viewModelScope`. The screen renders [PosterOptionsHost] with [state] and
 * wires [show] to each card's `onLongPress`.
 */
class PosterOptionsController @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val tmdbService: TmdbService,
    private val profileManager: ProfileManager
) {
    private val _state = MutableStateFlow(PosterOptionsState())
    val state: StateFlow<PosterOptionsState> = _state.asStateFlow()

    private val targetFlow = MutableStateFlow<Pair<MetaPreview, String>?>(null)

    private var scope: CoroutineScope? = null
    private var bound = false
    private var showJob: kotlinx.coroutines.Job? = null
    internal lateinit var playlistPicker: LibraryPlaylistPicker
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    fun bind(scope: CoroutineScope) {
        if (bound) return
        bound = true
        this.scope = scope

        playlistPicker = LibraryPlaylistPicker(libraryRepository, profileManager.activeProfileId, scope)

        targetFlow
            .flatMapLatest { target ->
                if (target == null) {
                    flowOf(false to false)
                } else {
                    val (item, original) = target
                    val library = libraryRepository.isInLibrary(item.toLibraryEntryInput(null, original))
                    val isSeries = item.apiType.equals("series", ignoreCase = true) ||
                        item.apiType.equals("tv", ignoreCase = true) ||
                        item.apiType.equals("anime", ignoreCase = true)
                    if (isSeries) {
                        combine(
                            library,
                            watchedSeriesStateHolder.fullyWatchedSeriesIds
                        ) { lib, watchedIds -> lib to (item.id in watchedIds) }
                    } else {
                        combine(
                            library,
                            watchProgressRepository.isWatched(item.id, videoId = item.imdbId)
                        ) { lib, watched -> lib to watched }
                    }
                }
            }
            .onEach { (isInLibrary, isWatched) ->
                _state.update { current ->
                    current.copy(
                        isInLibrary = isInLibrary,
                        isWatched = isWatched
                    )
                }
            }
            .launchIn(scope)
    }

    fun show(item: MetaPreview, addonBaseUrl: String?) {
        val launchScope = this.scope ?: return
        showJob?.cancel()
        showJob = launchScope.launch {
            val canonical = canonicalize(item)
            val isSeries = canonical.apiType.equals("series", ignoreCase = true) ||
                canonical.apiType.equals("tv", ignoreCase = true) ||
                canonical.apiType.equals("anime", ignoreCase = true)
            val initialIsInLibrary = runCatching {
                libraryRepository.isInLibrary(canonical.toLibraryEntryInput(addonBaseUrl, item.id)).first()
            }.getOrDefault(false)
            val initialIsWatched = if (isSeries) {
                canonical.id in watchedSeriesStateHolder.fullyWatchedSeriesIds.value
            } else {
                runCatching {
                    watchProgressRepository.isWatched(canonical.id, videoId = canonical.imdbId).first()
                }.getOrDefault(false)
            }

            _state.update { current ->
                current.copy(
                    target = canonical,
                    originalItemId = item.id,
                    addonBaseUrl = addonBaseUrl.orEmpty(),
                    isInLibrary = initialIsInLibrary,
                    isWatched = initialIsWatched,
                    isWatchedPending = false
                )
            }
            targetFlow.value = canonical to item.id
        }
    }

    private suspend fun canonicalize(item: MetaPreview): MetaPreview {
        if (item.id.startsWith("tt", ignoreCase = false)) return item
        val tmdbNumber = parseContentIds(item.id).tmdb ?: item.id.toIntOrNull() ?: return item
        val mediaType = if (item.apiType.equals("series", ignoreCase = true) ||
            item.apiType.equals("tv", ignoreCase = true) ||
            item.apiType.equals("anime", ignoreCase = true)
        ) "tv" else "movie"
        val imdb = runCatching { tmdbService.tmdbToImdb(tmdbNumber, mediaType) }.getOrNull()
        return if (!imdb.isNullOrBlank()) item.copy(id = imdb) else item
    }

    private suspend fun ensureCanonical(): MetaPreview? {
        val current = _state.value.target ?: return null
        val canonical = canonicalize(current)
        if (canonical.id != current.id && _state.value.target?.id == current.id) {
            _state.update { it.copy(target = canonical) }
            targetFlow.value = canonical to (_state.value.originalItemId ?: current.id)
        }
        return canonical
    }

    fun dismiss() {
        showJob?.cancel()
        showJob = null
        targetFlow.value = null
        _state.update { it.copy(target = null) }
    }

    fun openListPicker() {
        val state = _state.value
        val target = state.target ?: return
        val input = target.toLibraryEntryInput(state.addonBaseUrl.takeIf { it.isNotBlank() }, state.originalItemId ?: target.id)
        dismiss()
        playlistPicker.open(input)
    }

    fun toggleMovieWatched() {
        val state = _state.value
        val item = state.target ?: return
        if (!item.apiType.equals("movie", ignoreCase = true)) return
        if (state.isWatchedPending) return
        val scope = this.scope ?: return

        val currentlyWatched = state.isWatched
        // Collect all known ID variants: original UI id (e.g. "tmdb:123"),
        // canonical id (e.g. "tt1979376"), and imdbId field if available.
        val optimisticIds = buildSet {
            add(item.id)
            state.originalItemId?.takeIf { it != item.id }?.let(::add)
            item.imdbId?.takeIf(String::isNotBlank)?.let(::add)
        }

        watchProgressRepository.applyOptimisticWatchedMovie(optimisticIds, add = !currentlyWatched)

        _state.update { it.copy(isWatchedPending = true) }
        scope.launch {
            val canonical = ensureCanonical() ?: return@launch
            runCatching {
                if (currentlyWatched) {
                    watchProgressRepository.removeFromHistory(canonical.id, videoId = canonical.imdbId)
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(canonical))
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to toggle watched for ${canonical.id}: ${error.message}")
                // Revert optimistic update on failure
                watchProgressRepository.revertOptimisticWatchedMovie(optimisticIds, add = !currentlyWatched)
            }
            _state.update { it.copy(isWatchedPending = false) }
        }
    }

    fun toggleSeriesWatched() {
        val state = _state.value
        val item = state.target ?: return
        val isSeries = item.apiType.equals("series", ignoreCase = true) ||
            item.apiType.equals("tv", ignoreCase = true) ||
            item.apiType.equals("anime", ignoreCase = true)
        if (!isSeries) return
        if (state.isWatchedPending) return
        val scope = this.scope ?: return

        val currentlyWatched = state.isWatched

        // Optimistic UI update — badge changes immediately
        val currentIds = watchedSeriesStateHolder.fullyWatchedSeriesIds.value
        val optimisticIds = if (currentlyWatched) currentIds - item.id else currentIds + item.id
        watchedSeriesStateHolder.update(optimisticIds)

        _state.update { it.copy(isWatchedPending = true) }
        scope.launch {
            val canonical = ensureCanonical() ?: return@launch
            runCatching {
                if (currentlyWatched) {
                    unmarkSeriesWatched(canonical)
                } else {
                    markSeriesWatched(canonical)
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to toggle series watched for ${canonical.id}: ${error.message}")
                // Revert optimistic update on failure
                watchedSeriesStateHolder.update(currentIds)
            }
            _state.update { it.copy(isWatchedPending = false) }
        }
    }

    private suspend fun markSeriesWatched(item: MetaPreview) {
        val episodes = fetchSeriesEpisodes(item).filter { it.season != null && it.episode != null && it.season != 0 }
        // Without episodes the only mark left is a show-level one, which trackers answer by marking
        // every episode watched. Fail instead so the optimistic badge is reverted.
        check(episodes.isNotEmpty()) { "No episodes found for ${item.id}" }

        val progressList = episodes.map { video ->
            WatchProgress(
                contentId = item.id,
                contentType = item.apiType,
                name = item.name,
                poster = item.poster,
                backdrop = item.backdropUrl,
                logo = item.logo,
                videoId = video.id,
                season = video.season,
                episode = video.episode,
                episodeTitle = video.title,
                position = 1L,
                duration = 1L,
                lastWatched = System.currentTimeMillis(),
                progressPercent = 100f
            )
        }
        watchProgressRepository.markAsCompletedBatch(progressList)
    }

    private suspend fun unmarkSeriesWatched(item: MetaPreview) {
        val episodes = fetchSeriesEpisodes(item).filter { it.season != null && it.episode != null && it.season != 0 }
        if (episodes.isEmpty()) {
            watchProgressRepository.removeFromHistory(item.id, videoId = item.imdbId)
            return
        }

        watchProgressRepository.removeFromHistoryBatch(
            contentId = item.id,
            videoId = item.imdbId,
            episodes = episodes.map { Triple(it.season!!, it.episode!!, it.id) }
        )
    }

    private suspend fun fetchSeriesEpisodes(item: MetaPreview): List<Video> {
        val type = if (item.apiType.equals("tv", ignoreCase = true) ||
            item.apiType.equals("anime", ignoreCase = true)
        ) "series" else item.apiType
        var episodes: List<Video> = emptyList()
        metaRepository.getMetaFromPrimaryAddon(type, item.id)
            .collect { networkResult ->
                if (networkResult is NetworkResult.Success) {
                    episodes = networkResult.data.videos
                }
            }
        return episodes
    }

    companion object {
        private const val TAG = "PosterOptionsCtrl"
    }
}

private fun buildCompletedMovieProgress(item: MetaPreview): WatchProgress {
    return WatchProgress(
        contentId = item.id,
        contentType = item.apiType,
        name = item.name,
        poster = item.poster,
        backdrop = item.backdropUrl,
        logo = item.logo,
        videoId = item.id,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = System.currentTimeMillis(),
        progressPercent = 100f
    )
}
