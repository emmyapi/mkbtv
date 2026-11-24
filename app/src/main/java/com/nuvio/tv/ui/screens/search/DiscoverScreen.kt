package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.home.HeroBackdropState

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.roundToInt

@Composable
fun DiscoverScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    initialKeyword: String? = null,
    onInitialKeywordHandled: () -> Unit = {},
    voiceRequest: String? = null,
    onVoiceRequestHandled: () -> Unit = {},
    onNavigateToDetail: (String, String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val watchedMovieIds by viewModel.watchedMovieIds.collectAsState()
    val watchedSeriesIds by viewModel.watchedSeriesIds.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val discoverFirstItemFocusRequester = remember { FocusRequester() }
    var discoverFocusedItemIndex by rememberSaveable { mutableStateOf(0) }
    var detailItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreDiscoverFocus by rememberSaveable { mutableStateOf(false) }
    var pendingDiscoverRestoreOnResume by rememberSaveable { mutableStateOf(false) }

    val posterCardStyle = remember(uiState.posterCardWidthDp, uiState.posterCardCornerRadiusDp) {
        val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = computedHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    LaunchedEffect(uiState.discoverLocation, initialKeyword) {
        if (uiState.discoverLocation != DiscoverLocation.OFF) {
            if (initialKeyword != null) {
                if (initialKeyword.isNotBlank() && voiceRequest.isNullOrBlank()) {
                    viewModel.onEvent(SearchEvent.SetDiscoverKeyword(initialKeyword))
                }
                onInitialKeywordHandled()
            }
            // A complete incoming phrase applies its filters and starts the first query together.
            if (voiceRequest.isNullOrBlank()) viewModel.ensureDiscoverLoaded()
        }
    }

    LaunchedEffect(restoreDiscoverFocus, uiState.discoverResults.isEmpty(), uiState.discoverLoading) {
        if (restoreDiscoverFocus && uiState.discoverResults.isEmpty() && !uiState.discoverLoading) {
            restoreDiscoverFocus = false
        }
    }

    val latestPendingDiscoverRestore by rememberUpdatedState(pendingDiscoverRestoreOnResume)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && latestPendingDiscoverRestore) {
                restoreDiscoverFocus = true
                pendingDiscoverRestoreOnResume = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        if (uiState.discoverLocation == DiscoverLocation.OFF) {
            EmptyScreenState(
                title = stringResource(R.string.discover_disabled_title),
                subtitle = stringResource(R.string.discover_disabled_subtitle),
                icon = Icons.Default.Search
            )
        } else {
            DiscoverSection(
                uiState = uiState,
                voiceRequest = voiceRequest,
                onVoiceRequestHandled = onVoiceRequestHandled,
                posterCardStyle = posterCardStyle,
                watchedMovieIds = watchedMovieIds,
                watchedSeriesIds = watchedSeriesIds,
                focusResults = false,
                showBuiltInHeader = showBuiltInHeader,
                firstItemFocusRequester = discoverFirstItemFocusRequester,
                focusedItemIndex = if (restoreDiscoverFocus) {
                    uiState.discoverResults.indexOfFirst { it.id == detailItemId }.takeIf { it >= 0 }
                        ?: discoverFocusedItemIndex.coerceAtMost(uiState.discoverResults.lastIndex)
                } else discoverFocusedItemIndex,
                shouldRestoreFocusedItem = restoreDiscoverFocus,
                blockFilterFocus = restoreDiscoverFocus || pendingDiscoverRestoreOnResume,
                onRestoreFocusedItemHandled = { restoreDiscoverFocus = false },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    detailItemId = itemId
                    pendingDiscoverRestoreOnResume = true
                    onNavigateToDetail(itemId, itemType, addonBaseUrl)
                },
                onDiscoverItemFocused = { index ->
                    discoverFocusedItemIndex = index
                    uiState.discoverResults.getOrNull(index)?.takeIf { it.hasDiscoverDetails }?.let { item ->
                        viewModel.prefetchMetaOnFocus(item.id, item.rawType)
                    }
                },
                onFilterEvent = { event ->
                    discoverFocusedItemIndex = 0
                    viewModel.onEvent(event)
                },
                onLoadMore = { viewModel.onEvent(SearchEvent.LoadNextDiscoverResults) },
                resolveArtwork = viewModel::resolveDiscoverArtwork,
                onItemLongPress = { item, addonBaseUrl ->
                    viewModel.posterOptions.show(item, addonBaseUrl)
                },
                modifier = Modifier.padding(top = NuvioTheme.spacing.lg)
            )
        }

        val posterOptionsState by viewModel.posterOptions.state.collectAsState()
        com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
            state = posterOptionsState,
            controller = viewModel.posterOptions,
            onNavigateToDetail = { id, type, addonBaseUrl ->
                detailItemId = id
                pendingDiscoverRestoreOnResume = true
                val clickedItem = uiState.discoverResults.firstOrNull { it.id == id }
                HeroBackdropState.update(clickedItem?.backdropUrl)
                onNavigateToDetail(id, type, addonBaseUrl)
            }
        )
    }
}
