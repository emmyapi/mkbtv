package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.toLibraryEntryInput
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun HomeViewModel.refreshPosterLibraryStatus(item: MetaPreview) {
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    viewModelScope.launch {
        runCatching {
            val isInLibrary = libraryRepository.isInLibrary(item.toLibraryEntryInput(null)).first()
            _uiState.update { state ->
                if (state.posterLibraryMembership[statusKey] == isInLibrary) state
                else state.copy(
                    posterLibraryMembership = state.posterLibraryMembership + (statusKey to isInLibrary)
                )
            }
        }
    }
}

fun HomeViewModel.openPosterListPicker(item: MetaPreview, addonBaseUrl: String?) {
    playlistPicker.open(item.toLibraryEntryInput(addonBaseUrl))
}

fun HomeViewModel.togglePosterMovieWatched(item: MetaPreview) {
    if (!item.apiType.equals("movie", ignoreCase = true)) return
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    if (statusKey in _uiState.value.movieWatchedPending) return

    _uiState.update { state ->
        state.copy(movieWatchedPending = state.movieWatchedPending + statusKey)
    }

    viewModelScope.launch {
        val currentlyWatched = _uiState.value.movieWatchedStatus[statusKey] == true
        runCatching {
            if (currentlyWatched) {
                watchProgressRepository.removeFromHistory(item.id, videoId = item.imdbId)
            } else {
                watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(item))
            }
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to toggle poster watched status for ${item.id}: ${error.message}")
        }
        _uiState.update { state ->
            state.copy(movieWatchedPending = state.movieWatchedPending - statusKey)
        }
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

fun HomeViewModel.togglePosterSeriesWatched(item: MetaPreview) {
    val isSeries = item.apiType.equals("series", ignoreCase = true) ||
        item.apiType.equals("tv", ignoreCase = true)
    if (!isSeries) return
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    if (statusKey in _uiState.value.movieWatchedPending) return

    val currentlyWatched = _uiState.value.movieWatchedStatus[statusKey] == true

    // Optimistically update the UI immediately
    _uiState.update { state ->
        state.copy(
            movieWatchedPending = state.movieWatchedPending + statusKey,
            movieWatchedStatus = state.movieWatchedStatus + (statusKey to !currentlyWatched)
        )
    }

    viewModelScope.launch {
        runCatching {
            if (currentlyWatched) {
                unmarkSeriesWatched(item)
            } else {
                markSeriesWatched(item)
            }
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to toggle series watched for ${item.id}: ${error.message}")
            // Revert optimistic update on failure
            _uiState.update { state ->
                state.copy(
                    movieWatchedStatus = state.movieWatchedStatus + (statusKey to currentlyWatched)
                )
            }
        }
        _uiState.update { state ->
            state.copy(movieWatchedPending = state.movieWatchedPending - statusKey)
        }
    }
}

private suspend fun HomeViewModel.markSeriesWatched(item: MetaPreview) {
    val episodes = fetchSeriesEpisodes(item)
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
    fullyWatchedSeriesIds.updateWithValidation(
        fullyWatchedSeriesIds.fullyWatchedSeriesIds.value + item.id,
        setOf(item.id)
    )
}

private suspend fun HomeViewModel.unmarkSeriesWatched(item: MetaPreview) {
    val episodes = fetchSeriesEpisodes(item)
    if (episodes.isEmpty()) {
        watchProgressRepository.removeFromHistory(item.id, videoId = item.imdbId)
        return
    }

    watchProgressRepository.removeFromHistoryBatch(
        contentId = item.id,
        videoId = item.imdbId,
        episodes = episodes.map { Triple(it.season!!, it.episode!!, it.id) }
    )
    fullyWatchedSeriesIds.updateWithValidation(
        fullyWatchedSeriesIds.fullyWatchedSeriesIds.value - item.id,
        setOf(item.id)
    )
}

private suspend fun HomeViewModel.fetchSeriesEpisodes(item: MetaPreview): List<com.nuvio.tv.domain.model.Video> {
    val type = if (item.apiType.equals("tv", ignoreCase = true)) "series" else item.apiType
    var episodes: List<com.nuvio.tv.domain.model.Video> = emptyList()
    metaRepository.getMetaFromPrimaryAddon(type, item.id)
        .collect { networkResult ->
            if (networkResult is com.nuvio.tv.core.network.NetworkResult.Success) {
                episodes = networkResult.data.watchableEpisodes()
            }
        }
    return episodes
}
