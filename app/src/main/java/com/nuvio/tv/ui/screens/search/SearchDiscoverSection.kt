package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.home.HeroBackdropState

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.nuvio.tv.core.poster.withCustomPosterUrl
import com.nuvio.tv.data.repository.DiscoverFailure
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.tv.ui.util.contentTextDirection
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.nuvioCardDepth
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.ui.util.dpadVerticalFastScroll
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.localizedGenreLabel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DiscoverSection(
    uiState: SearchUiState,
    posterCardStyle: PosterCardStyle,
    watchedMovieIds: Set<String> = emptySet(),
    watchedSeriesIds: Set<String> = emptySet(),
    focusResults: Boolean,
    showBuiltInHeader: Boolean = true,
    firstItemFocusRequester: FocusRequester,
    focusedItemIndex: Int,
    shouldRestoreFocusedItem: Boolean,
    blockFilterFocus: Boolean = false,
    onRestoreFocusedItemHandled: () -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onDiscoverItemFocused: (Int) -> Unit,
    onFilterEvent: (SearchEvent) -> Unit,
    onLoadMore: () -> Unit,
    resolveArtwork: suspend (MetaPreview) -> MetaPreview?,
    onItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    voiceRequest: String? = null,
    onVoiceRequestHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val filterFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = LocalContentFocusRequester.current
    val sidebarExpanded = LocalSidebarExpanded.current
    val showingResults = uiState.discoverResults.isNotEmpty() && !uiState.discoverLoading
    var editingKeyword by remember { mutableStateOf(false) }
    var gridHasFocus by remember(showingResults) { mutableStateOf(false) }
    var requestFilterFocus by remember { mutableStateOf(!blockFilterFocus && !sidebarExpanded) }
    val browsingResults = gridHasFocus && !requestFilterFocus && !sidebarExpanded
    val controlsVisible = !showingResults || sidebarExpanded || requestFilterFocus ||
        (!gridHasFocus && !blockFilterFocus)

    LaunchedEffect(requestFilterFocus, blockFilterFocus, sidebarExpanded) {
        if (!requestFilterFocus || blockFilterFocus || sidebarExpanded) return@LaunchedEffect
        filterFocusRequester.requestFocusAfterFrames()
        requestFilterFocus = false
    }

    androidx.activity.compose.BackHandler(enabled = browsingResults && !blockFilterFocus) {
        requestFilterFocus = true
    }

    val (voice, launchVoiceSearch) = rememberVoiceSearch(language = "da-DK") { phrase ->
        onFilterEvent(SearchEvent.ApplyDiscoverVoiceQuery(phrase))
    }
    androidx.activity.compose.BackHandler(enabled = voice.listening && !sidebarExpanded) {
        voice.stop()
        if (gridHasFocus) requestFilterFocus = true
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(voiceRequest, lifecycle) {
        val phrase = voiceRequest ?: return@LaunchedEffect
        lifecycle.withResumed {
            onVoiceRequestHandled()
            editingKeyword = false
            requestFilterFocus = true
            if (phrase.isEmpty()) {
                if (!voice.listening) launchVoiceSearch()
            } else {
                voice.stop()
                onFilterEvent(SearchEvent.ApplyDiscoverVoiceQuery(phrase))
            }
        }
    }

    val anyLabel = stringResource(R.string.discover_filter_any)
    val localContext = LocalContext.current
    fun typeLabel(type: String) = if (type == DISCOVER_TYPE_ANY) anyLabel else localizedContentType(localContext, type)
    val catalogOptions = DiscoverCatalogOption.entries.filter { it.availableFor(uiState.selectedDiscoverType) }
    val selectedCatalog = catalogOptions.firstOrNull { it.key == uiState.selectedDiscoverCatalogKey } ?: DiscoverCatalogOption.ALL
    val selectedGenreLabel = uiState.selectedDiscoverGenre?.let { localizedGenreLabel(it) } ?: anyLabel

    if (editingKeyword) {
        DiscoverKeywordDialog(
            keyword = uiState.discoverKeyword,
            onDismiss = { editingKeyword = false },
            onApply = { keyword ->
                onFilterEvent(SearchEvent.SetDiscoverKeyword(keyword))
                editingKeyword = false
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        // Give the sidebar's floating Discover title its own line, including its icon-only state.
        Row(
            modifier = Modifier.heightIn(min = NuvioTheme.sizes.player.control + NuvioTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            if (showBuiltInHeader) {
                Icon(Icons.Default.Explore, contentDescription = null, tint = NuvioTheme.colors.TextPrimary)
                Text(
                    text = stringResource(R.string.mkbtv_discover_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
        }
        if (controlsVisible) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                Button(
                    onClick = { voice.stop(); editingKeyword = true },
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    modifier = Modifier.weight(1f)
                        .focusRequester(contentFocusRequester)
                        .focusRequester(filterFocusRequester)
                        .focusProperties { canFocus = !blockFilterFocus }
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (uiState.discoverKeyword.isEmpty()) {
                            stringResource(R.string.discover_add_keyword)
                        } else {
                            stringResource(R.string.discover_current_keyword, uiState.discoverKeyword)
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (voice.available) {
                    Button(
                        onClick = launchVoiceSearch,
                        modifier = Modifier.focusProperties { canFocus = !blockFilterFocus }
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (voice.listening) R.string.discover_voice_listening else R.string.cd_voice_search))
                    }
                }
                Button(
                    onClick = { voice.stop(); onFilterEvent(SearchEvent.ResetDiscoverFilters) },
                    modifier = Modifier.focusProperties { canFocus = !blockFilterFocus }
                ) { Text(stringResource(R.string.discover_reset_filters)) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                DiscoverDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.discover_filter_type),
                    value = typeLabel(uiState.selectedDiscoverType),
                    selectedValue = uiState.selectedDiscoverType,
                    options = discoverTypes.map { DiscoverOption(typeLabel(it), it) },
                    onSelect = { option ->
                        onFilterEvent(SearchEvent.SelectDiscoverType(option.value))
                    },
                    blockFocus = blockFilterFocus
                )

                DiscoverDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.discover_filter_catalog),
                    value = stringResource(selectedCatalog.label),
                    selectedValue = uiState.selectedDiscoverCatalogKey,
                    options = catalogOptions.map { DiscoverOption(stringResource(it.label), it.key) },
                    onSelect = { option ->
                        onFilterEvent(SearchEvent.SelectDiscoverCatalog(option.value))
                    },
                    blockFocus = blockFilterFocus
                )

                DiscoverDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.discover_filter_genre),
                    value = selectedGenreLabel,
                    selectedValue = uiState.selectedDiscoverGenre.orEmpty(),
                    options = listOf(DiscoverOption(anyLabel, "")) + discoverGenres.map {
                        DiscoverOption(localizedGenreLabel(it), it)
                    },
                    onSelect = { option ->
                        onFilterEvent(SearchEvent.SelectDiscoverGenre(option.value.takeIf { it.isNotEmpty() }))
                    },
                    blockFocus = blockFilterFocus
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                val periodOptions = DiscoverPeriod.entries.map { DiscoverOption(stringResource(it.labelResource), it.name) }
                val sortOptions = DiscoverSort.entries.map { DiscoverOption(stringResource(it.labelResource), it.name) }
                val ratingOptions = listOf(DiscoverOption(anyLabel, "")) + (1..10).map { DiscoverOption("$it+", it.toString()) }
                DiscoverDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.discover_watch_status),
                    value = stringResource(uiState.discoverWatch.labelResource),
                    selectedValue = uiState.discoverWatch.name,
                    options = DiscoverWatch.entries.map { DiscoverOption(stringResource(it.labelResource), it.name) },
                    onSelect = {
                        onFilterEvent(SearchEvent.SelectDiscoverWatch(DiscoverWatch.valueOf(it.value)))
                    },
                    blockFocus = blockFilterFocus
                )
                DiscoverDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.discover_period),
                    value = stringResource(uiState.discoverPeriod.labelResource),
                    selectedValue = uiState.discoverPeriod.name,
                    options = periodOptions,
                    onSelect = {
                        onFilterEvent(SearchEvent.SelectDiscoverPeriod(DiscoverPeriod.valueOf(it.value)))
                    },
                    blockFocus = blockFilterFocus
                )
                DiscoverDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.discover_min_rating),
                    value = uiState.discoverMinRating?.let { "$it+" } ?: anyLabel,
                    selectedValue = uiState.discoverMinRating?.toString().orEmpty(),
                    options = ratingOptions,
                    onSelect = {
                        onFilterEvent(SearchEvent.SelectDiscoverMinRating(it.value.toIntOrNull()))
                    },
                    blockFocus = blockFilterFocus
                )
                DiscoverDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.discover_sort),
                    value = stringResource(uiState.discoverSort.labelResource),
                    selectedValue = uiState.discoverSort.name,
                    options = sortOptions,
                    onSelect = {
                        onFilterEvent(SearchEvent.SelectDiscoverSort(DiscoverSort.valueOf(it.value)))
                    },
                    blockFocus = blockFilterFocus
                )
            }
        }

        uiState.discoverTotal?.let { total ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                Text(
                    text = pluralStringResource(R.plurals.discover_match_count, total, total),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                for (source in uiState.discoverSources) {
                    var focused by remember(source) { mutableStateOf(false) }
                    Text(
                        text = source.label,
                        modifier = Modifier
                            .focusProperties { canFocus = !blockFilterFocus }
                            .onFocusChanged { focused = it.isFocused }
                            .border(1.dp, if (focused) NuvioTheme.colors.FocusRing else Color.Transparent,
                                RoundedCornerShape(3.dp))
                            .clickable {
                                try {
                                    localContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(localContext, source.url, Toast.LENGTH_LONG).show()
                                }
                            }
                            .padding(horizontal = NuvioTheme.spacing.xs),
                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                        color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }

        if (uiState.discoverError) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                val message = when (uiState.discoverFailure) {
                    DiscoverFailure.CATALOG -> R.string.discover_catalog_unavailable
                    DiscoverFailure.RANKING -> R.string.discover_ranking_unavailable
                    DiscoverFailure.INDEX -> R.string.discover_index_unavailable
                    DiscoverFailure.WATCH -> R.string.discover_watch_unavailable
                    DiscoverFailure.BUSY -> R.string.discover_busy
                    else -> R.string.discover_error_loading
                }
                Text(stringResource(message), color = NuvioTheme.colors.TextPrimary)
                if (uiState.discoverResults.isEmpty()) {
                    Button(onClick = { onFilterEvent(SearchEvent.RetryDiscover) }) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }

        when {
            uiState.discoverLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp, bottom = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.discoverResults.isNotEmpty() -> {
                Box(
                    modifier = Modifier
                        .then(if (controlsVisible) Modifier else Modifier.focusRequester(contentFocusRequester))
                        .onFocusChanged { gridHasFocus = it.hasFocus }
                        .focusGroup()
                ) {
                    DiscoverGrid(
                        items = uiState.discoverResults,
                        resolveArtwork = resolveArtwork,
                        posterPattern = uiState.discoverPosterPattern,
                        showLabels = uiState.posterLabelsEnabled,
                        posterCardStyle = posterCardStyle,
                        watchedMovieIds = watchedMovieIds,
                        watchedSeriesIds = watchedSeriesIds,
                        focusResults = focusResults,
                        firstItemFocusRequester = firstItemFocusRequester,
                        focusedItemIndex = focusedItemIndex,
                        shouldRestoreFocusedItem = shouldRestoreFocusedItem,
                        onRestoreFocusedItemHandled = onRestoreFocusedItemHandled,
                        onItemFocused = onDiscoverItemFocused,
                        onUpFromFirstRow = { requestFilterFocus = true },
                        browsingResults = browsingResults,
                        pageError = uiState.discoverError,
                        canLoadMore = uiState.discoverHasMore,
                        isLoadingMore = uiState.discoverLoadingMore,
                        onLoadMore = if (uiState.discoverError) {
                            { onFilterEvent(SearchEvent.RetryDiscover) }
                        } else onLoadMore,
                        onItemClick = { _, item ->
                            HeroBackdropState.update(item.backdropUrl)
                            onNavigateToDetail(
                                item.id,
                                item.apiType,
                                item.sourceAddonBaseUrl.orEmpty()
                            )
                        },
                        onItemLongPress = { item ->
                            onItemLongPress(item, item.sourceAddonBaseUrl.orEmpty())
                        },
                        filterKey = listOf(uiState.selectedDiscoverType, uiState.selectedDiscoverCatalogKey,
                            uiState.selectedDiscoverGenre, uiState.discoverKeyword, uiState.discoverPeriod,
                            uiState.discoverMinRating, uiState.discoverWatch, uiState.discoverSort)
                    )
                }
            }

            uiState.discoverTotal == 0 -> {
                EmptyScreenState(
                    title = stringResource(R.string.discover_no_matches),
                    subtitle = stringResource(R.string.discover_empty_filter_hint),
                    height = 140.dp
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DiscoverDropdownPicker(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    selectedValue: String?,
    options: List<DiscoverOption>,
    onSelect: (DiscoverOption) -> Unit,
    blockFocus: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    // Seed focused option with the current selection so reopen highlights the right row
    // even before focus lands (fixes #2848 / incomplete #2507 race on TV).
    var focusedOptionValue by remember(expanded) {
        mutableStateOf(if (expanded) selectedValue else null)
    }
    val selectedItemFocusRequester = remember { FocusRequester() }
    val selectedBringIntoViewRequester = remember { BringIntoViewRequester() }

    // Popup content attaches focus targets a few frames after expand. A fixed 50ms
    // delay was flaky on TV and left focus on the first item for non-top selections.
    LaunchedEffect(expanded, selectedValue) {
        if (!expanded || selectedValue == null) return@LaunchedEffect
        var focused = selectedItemFocusRequester.requestFocusAfterFrames(frames = 3)
        var attempt = 0
        while (!focused && attempt < 6) {
            delay(32)
            focused = runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)
            attempt++
        }
        if (!focused) return@LaunchedEffect
        runCatching { selectedBringIntoViewRequester.bringIntoView() }
        // Material DropdownMenu may still move initial focus to the first item after
        // the popup settles; re-assert once so long lists keep the real selection.
        delay(48)
        if (runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)) {
            runCatching { selectedBringIntoViewRequester.bringIntoView() }
        }
    }

    Box(modifier = modifier) {
        Card(
            onClick = { expanded = options.isNotEmpty() },
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { anchorSize = it }
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                }
                .then(
                    if (blockFocus || options.isEmpty()) Modifier.focusProperties { canFocus = false }
                    else Modifier
                ),
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(14.dp)
                )
            ),
            scale = CardDefaults.scale(
                focusedScale = 1.0f,
                pressedScale = 1.0f
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium.copy(
                            textDirection = value.contentTextDirection()
                        ),
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) stringResource(R.string.cd_collapse, title) else stringResource(R.string.cd_expand, title),
                        modifier = Modifier.size(20.dp),
                        tint = if (isFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                focusedOptionValue = null
                expanded = false
            },
            modifier = Modifier
                .width(with(LocalDensity.current) { anchorSize.width.toDp() })
                .heightIn(max = 320.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = NuvioTheme.colors.BackgroundCard,
            tonalElevation = NuvioTheme.spacing.none,
            shadowElevation = NuvioTheme.spacing.sm,
            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
        ) {
            options.forEach { option ->
                val isSelected = option.value == selectedValue
                val isOptionFocused = option.value == focusedOptionValue
                val itemTextColor = when {
                    isOptionFocused -> NuvioTheme.colors.OnSecondary
                    isSelected -> NuvioTheme.colors.TextPrimary
                    else -> NuvioTheme.colors.TextPrimary
                }
                val itemBackgroundColor = when {
                    isOptionFocused -> NuvioTheme.colors.Secondary
                    isSelected -> NuvioTheme.colors.FocusBackground
                    else -> Color.Transparent
                }

                DropdownMenuItem(
                    modifier = Modifier
                        .then(
                            if (isSelected) {
                                Modifier
                                    .focusRequester(selectedItemFocusRequester)
                                    .bringIntoViewRequester(selectedBringIntoViewRequester)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = NuvioTheme.spacing.xxs)
                        .background(
                            color = itemBackgroundColor,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .onFocusChanged { state ->
                            val hasFocus = state.isFocused || state.hasFocus
                            focusedOptionValue = when {
                                hasFocus -> option.value
                                focusedOptionValue == option.value -> null
                                else -> focusedOptionValue
                            }
                        },
                    text = {
                        Text(
                            text = option.label,
                            color = itemTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                textDirection = option.label.contentTextDirection()
                            )
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = itemTextColor,
                        disabledTextColor = NuvioTheme.colors.TextDisabled
                    )
                )
            }
        }
    }
}

private val DiscoverWatch.labelResource get() = when (this) {
    DiscoverWatch.ANY -> R.string.discover_filter_any
    DiscoverWatch.UNWATCHED -> R.string.discover_unwatched
    DiscoverWatch.WATCHED -> R.string.discover_watched
}

private val DiscoverPeriod.labelResource get() = when (this) {
    DiscoverPeriod.ANY -> R.string.discover_filter_any
    DiscoverPeriod.THIS_YEAR -> R.string.discover_this_year
    DiscoverPeriod.LAST_FIVE_YEARS -> R.string.discover_last_five_years
    DiscoverPeriod.DECADE_2020 -> R.string.discover_2020s
    DiscoverPeriod.DECADE_2010 -> R.string.discover_2010s
    DiscoverPeriod.DECADE_2000 -> R.string.discover_2000s
    DiscoverPeriod.DECADE_1990 -> R.string.discover_1990s
    DiscoverPeriod.DECADE_1980 -> R.string.discover_1980s
    DiscoverPeriod.BEFORE_1980 -> R.string.discover_before_1980
    DiscoverPeriod.BEFORE_2010 -> R.string.discover_before_2010
}

private val DiscoverSort.labelResource get() = when (this) {
    DiscoverSort.POPULAR -> R.string.discover_popular
    DiscoverSort.TRENDING -> R.string.discover_trending
    DiscoverSort.TOP_RATED -> R.string.discover_top_rated
    DiscoverSort.NEWEST -> R.string.discover_newest
    DiscoverSort.OLDEST -> R.string.discover_oldest
    DiscoverSort.TITLE_DESC -> R.string.discover_title_desc
    DiscoverSort.TITLE -> R.string.discover_title_order
}

private data class DiscoverOption(
    val label: String,
    val value: String
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun DiscoverGrid(
    items: List<MetaPreview>,
    resolveArtwork: suspend (MetaPreview) -> MetaPreview?,
    posterPattern: String,
    showLabels: Boolean,
    posterCardStyle: PosterCardStyle,
    watchedMovieIds: Set<String> = emptySet(),
    watchedSeriesIds: Set<String> = emptySet(),
    focusResults: Boolean,
    firstItemFocusRequester: FocusRequester,
    focusedItemIndex: Int,
    shouldRestoreFocusedItem: Boolean,
    onRestoreFocusedItemHandled: () -> Unit,
    onItemFocused: (Int) -> Unit,
    onUpFromFirstRow: () -> Unit,
    browsingResults: Boolean,
    pageError: Boolean,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onItemClick: (Int, MetaPreview) -> Unit,
    onItemLongPress: (MetaPreview) -> Unit = {},
    filterKey: List<Any?> = emptyList()
) {
    val context = LocalContext.current
    val restoreFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var sizeBeforeLoadMore by remember(filterKey) { mutableStateOf<Int?>(null) }

    // Scroll to top only when the filter combination actually changes (not on initial composition / back navigation).
    var previousFilterKey by remember { mutableStateOf(filterKey) }
    LaunchedEffect(filterKey) {
        if (filterKey != previousFilterKey) {
            gridState.scrollToItem(0, 0)
            previousFilterKey = filterKey
        }
    }
    var localRestoreFocusedItemIndex by remember { mutableStateOf(-1) }
    var localShouldRestoreFocusedItem by remember { mutableStateOf(false) }
    val effectiveFocusedItemIndex = if (localShouldRestoreFocusedItem) {
        localRestoreFocusedItemIndex
    } else {
        focusedItemIndex
    }
    val effectiveShouldRestoreFocusedItem = shouldRestoreFocusedItem || localShouldRestoreFocusedItem
    val actionType = when {
        pageError -> DiscoverGridAction.Retry
        isLoadingMore -> DiscoverGridAction.Loading
        canLoadMore -> DiscoverGridAction.LoadMore
        else -> DiscoverGridAction.None
    }
    val totalCells = items.size + if (actionType != DiscoverGridAction.None) 1 else 0
    val hasActionCell = actionType != DiscoverGridAction.None

    val globalLandscape = com.nuvio.tv.ui.components.LocalLandscapePosterMode.current
    val adaptiveStyle = remember(posterCardStyle) {
        val cardWidth = posterCardStyle.width
        posterCardStyle.copy(
            width = cardWidth,
            height = cardWidth * 1.5f
        )
    }

    val canRestoreFocus = !LocalSidebarExpanded.current && (!localShouldRestoreFocusedItem || browsingResults)
    LaunchedEffect(effectiveShouldRestoreFocusedItem, effectiveFocusedItemIndex, totalCells, canRestoreFocus) {
        if (!effectiveShouldRestoreFocusedItem) return@LaunchedEffect
        if (canRestoreFocus && effectiveFocusedItemIndex in 0 until totalCells) {
            if (gridState.layoutInfo.visibleItemsInfo.none { it.index == effectiveFocusedItemIndex }) {
                gridState.scrollToItem(effectiveFocusedItemIndex)
            }
            restoreFocusRequester.requestFocusAfterFrames(frames = 2)
        }
        if (localShouldRestoreFocusedItem) {
            localShouldRestoreFocusedItem = false
            localRestoreFocusedItemIndex = -1
        } else {
            onRestoreFocusedItemHandled()
        }
    }

    LaunchedEffect(items.size, canLoadMore, sizeBeforeLoadMore) {
        val previousSize = sizeBeforeLoadMore ?: return@LaunchedEffect
        if (items.size > previousSize) {
            sizeBeforeLoadMore = null
            if (browsingResults) {
                localRestoreFocusedItemIndex = previousSize
                localShouldRestoreFocusedItem = true
            }
        } else if (!canLoadMore) {
            sizeBeforeLoadMore = null
        }
    }

    val shouldAutoLoad = canLoadMore && !isLoadingMore && !pageError && items.isNotEmpty()
    LaunchedEffect(gridState.firstVisibleItemIndex, items.size, shouldAutoLoad) {
        if (!shouldAutoLoad) return@LaunchedEffect
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible >= items.size - 6) {
            onLoadMore()
        }
    }

    // Per-item FocusRequesters for focusRestorer on the grid.
    // Pre-create the requester for the focused item so focusRestorer can use it
    // even before the grid items are composed (first return from navigation).
    val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val focusedItemRequester = remember(focusedItemIndex) {
        itemFocusRequesters.getOrPut(focusedItemIndex) { FocusRequester() }
    }

    // Column the user was navigating in at the moment fast-scroll engaged.
    // Captured on drag-start (see onFastScrollingChanged below) because the
    // originally-focused card will have scrolled out of visibleItemsInfo by
    // the time landing runs — looking it up at landing time is unreliable
    // and was causing focus to jump to a different column after long drags.
    var fastScrollStartColumn by remember { mutableStateOf<Int?>(null) }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(
            minSize = if (globalLandscape) posterCardStyle.height else adaptiveStyle.width
        ),
        modifier = Modifier.fillMaxSize()
            .focusRestorer { focusedItemRequester }
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (key.action == AndroidKeyEvent.ACTION_DOWN && key.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP &&
                    key.repeatCount == 0 && browsingResults &&
                    gridState.layoutInfo.visibleItemsInfo.any { it.index == focusedItemIndex && it.row == 0 }
                ) {
                    onUpFromFirstRow()
                    true
                } else false
            }
            .dpadVerticalFastScroll(
                scrollableState = gridState,
                onFastScrollingChanged = { active ->
                    // Only snapshot on drag-start. The modifier fires
                    // active=false BEFORE it calls resolveVerticalLanding,
                    // so clearing here would wipe the column right before
                    // landing reads it. Next drag-start will overwrite.
                    if (active) {
                        fastScrollStartColumn = gridState.layoutInfo
                            .visibleItemsInfo
                            .firstOrNull { it.index == focusedItemIndex }
                            ?.column
                    }
                },
                resolveVerticalLanding = { sign ->
                    // Keep the user on the column they were navigating in
                    // so fast-scroll feels like a single vertical strip,
                    // not a teleport to an arbitrary cell in the viewport.
                    // Exclude the action (Load More / Loading) cell up front
                    // — it lives at index == items.size and when items.size
                    // % columns == 0 it lands at column 0 of a brand-new
                    // tail row, which would otherwise hijack every column-0
                    // landing and bounce focus to the last content cell.
                    val contentVisible = gridState.layoutInfo.visibleItemsInfo
                        .filter { it.index < items.size }
                    if (contentVisible.isEmpty()) {
                        null
                    } else {
                        val col = fastScrollStartColumn
                        val sameColumn = if (col != null) {
                            contentVisible.filter { it.column == col }
                        } else {
                            emptyList()
                        }
                        val target = when {
                            sameColumn.isNotEmpty() && sign > 0 -> sameColumn.last()
                            sameColumn.isNotEmpty() && sign < 0 -> sameColumn.first()
                            sign > 0 -> contentVisible.last()
                            else -> contentVisible.first()
                        }
                        val requester = itemFocusRequesters[target.index]
                        runCatching { requester?.requestFocus() }
                        null // Discover uses imperative requestFocus for now
                    }
                }
            ),
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        itemsIndexed(
            items = items,
            key = { index, item -> "${item.apiType}:${item.id.ifEmpty { "discover_$index" }}" },
            contentType = { _, _ -> "content_card" }
        ) { index, item ->
            val itemFocusReq = remember(index) { itemFocusRequesters.getOrPut(index) { FocusRequester() } }
            val focusReq = when {
                effectiveShouldRestoreFocusedItem && index == effectiveFocusedItemIndex -> restoreFocusRequester
                focusResults && index == 0 -> firstItemFocusRequester
                else -> itemFocusReq
            }
            // Loading follows this lazy cell's lifetime. The index owns membership and ordering.
            var artwork by remember(item) { mutableStateOf<MetaPreview?>(null) }
            LaunchedEffect(item) { artwork = resolveArtwork(item) ?: item }
            val displayItem = remember(item, artwork, posterPattern) {
                // Give Search artwork first chance before requesting the index's lazy image.
                (artwork ?: item.copy(poster = null, rawPosterUrl = null)).withCustomPosterUrl(posterPattern)
            }
            GridContentCard(
                item = displayItem,
                indexPosterUrl = if (artwork != null) item.poster else null,
                showLabel = showLabels,
                onClick = {
                    if (displayItem.hasDiscoverDetails) onItemClick(index, displayItem)
                    else Toast.makeText(context, R.string.discover_no_title_details, Toast.LENGTH_SHORT).show()
                },
                onLongPress = if (displayItem.hasDiscoverDetails) { { onItemLongPress(displayItem) } } else null,
                posterCardStyle = adaptiveStyle,
                isWatched = displayItem.isDiscoverWatched(watchedMovieIds, watchedSeriesIds),
                modifier = Modifier
                    .padding(top = 3.dp)
                    .width(adaptiveStyle.width),
                focusRequester = focusReq,
                onFocused = {
                    onItemFocused(index)
                }
            )
        }

        if (hasActionCell) {
            item(
                key = "discover_action",
                contentType = "action_card"
            ) {
                val actionIndex = items.size
                val focusReq = when {
                    effectiveShouldRestoreFocusedItem && actionIndex == effectiveFocusedItemIndex -> restoreFocusRequester
                    focusResults && items.isEmpty() -> firstItemFocusRequester
                    else -> null
                }
                DiscoverActionCard(
                    actionType = actionType,
                    posterCardStyle = adaptiveStyle,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .width(adaptiveStyle.width),
                    focusRequester = focusReq,
                    onFocused = { onItemFocused(actionIndex) },
                    onClick = {
                        if (actionType == DiscoverGridAction.LoadMore || actionType == DiscoverGridAction.Retry) {
                            sizeBeforeLoadMore = items.size
                            onLoadMore()
                        }
                    }
                )
            }
        }
    }
}

private sealed class DiscoverGridAction {
    object None : DiscoverGridAction()
    object Retry : DiscoverGridAction()
    object LoadMore : DiscoverGridAction()
    object Loading : DiscoverGridAction()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverActionCard(
    actionType: DiscoverGridAction,
    posterCardStyle: PosterCardStyle,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    val title = when (actionType) {
        DiscoverGridAction.Retry -> stringResource(R.string.action_retry)
        DiscoverGridAction.LoadMore -> stringResource(R.string.discover_load_more)
        DiscoverGridAction.Loading -> stringResource(R.string.discover_loading)
        DiscoverGridAction.None -> ""
    }
    val cardDepthStyle = LocalCardDepthStyle.current
    val globalLandscape = com.nuvio.tv.ui.components.LocalLandscapePosterMode.current

    Column(
        modifier = modifier.then(if (globalLandscape) Modifier.fillMaxWidth() else Modifier.width(posterCardStyle.width))
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .then(
                    if (globalLandscape) Modifier.fillMaxWidth().aspectRatio(com.nuvio.tv.domain.model.PosterShape.LANDSCAPE.aspectRatio())
                    else Modifier.width(posterCardStyle.width).height(posterCardStyle.height)
                )
                .onPreviewKeyEvent { event ->
                    actionType != DiscoverGridAction.None &&
                        event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN &&
                        event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                }
                .onFocusChanged { state -> if (state.isFocused) onFocused() }
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                ),
            shape = CardDefaults.shape(shape = cardShape),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(posterCardStyle.focusedBorderWidth),
                    shape = cardShape
                )
            ),
            scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cardShape)
                    .nuvioCardDepth(
                        shape = cardShape,
                        surface = CardDepthSurface.POSTERS,
                        style = cardDepthStyle
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (actionType == DiscoverGridAction.Loading) {
                    LoadingIndicator()
                } else {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        // Reserve space for label to match GridContentCard height
        Spacer(
            modifier = Modifier
                .then(if (globalLandscape) Modifier.fillMaxWidth() else Modifier.width(posterCardStyle.width))
                .padding(top = NuvioTheme.spacing.sm)
                .height(MaterialTheme.typography.titleMedium.lineHeight.value.dp)
        )
    }
}
