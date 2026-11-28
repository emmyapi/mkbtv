package com.nuvio.tv.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.localizedTitle
import com.nuvio.tv.ui.screens.search.DiscoverCatalogOption
import com.nuvio.tv.ui.screens.search.DiscoverDropdownPicker
import com.nuvio.tv.ui.screens.search.DiscoverKeywordDialog
import com.nuvio.tv.ui.screens.search.DiscoverOption
import com.nuvio.tv.ui.screens.search.DiscoverPeriod
import com.nuvio.tv.ui.screens.search.DiscoverWatch
import com.nuvio.tv.ui.screens.search.MovieReleasePicker
import com.nuvio.tv.ui.screens.search.discoverGenres
import com.nuvio.tv.ui.screens.search.discoverTypes
import com.nuvio.tv.ui.screens.search.labelResource
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.localizedGenreLabel

@Composable
internal fun SavedLibraryControls(
    state: LibraryUiState,
    onChange: (SavedLibraryFilters) -> Unit,
    onCreate: () -> Unit,
    onManage: () -> Unit
) {
    val filters = state.savedFilters
    val any = stringResource(R.string.discover_filter_any)
    val all = stringResource(R.string.discover_all)
    var editingKeyword by rememberSaveable { mutableStateOf(false) }
    if (editingKeyword) {
        DiscoverKeywordDialog(filters.keyword, onDismiss = { editingKeyword = false }) {
            editingKeyword = false
            onChange(filters.copy(keyword = it))
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { editingKeyword = true }, modifier = Modifier.weight(1f)) {
                Text(filters.keyword.ifBlank { stringResource(R.string.discover_add_keyword) },
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onCreate) { Text(stringResource(R.string.library_new_playlist)) }
            Button(onClick = onManage) { Text(stringResource(R.string.library_manage_playlists)) }
            Button(onClick = { onChange(SavedLibraryFilters(playlist = filters.playlist)) }) {
                Text(stringResource(R.string.discover_reset_filters))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            Filter(R.string.library_playlist_filter, filters.playlist.orEmpty(),
                listOf(DiscoverOption(all, "")) + state.listTabs.map { DiscoverOption(it.localizedTitle(), it.key) }
            ) { onChange(filters.copy(playlist = it.ifEmpty { null })) }
            Filter(R.string.discover_filter_type, filters.type, discoverTypes.map {
                DiscoverOption(if (it == "any") any else localizedContentType(it), it)
            }) { onChange(filters.withType(it)) }
            Filter(R.string.discover_filter_catalog, filters.catalog,
                DiscoverCatalogOption.entries.filter { it.availableFor(filters.type) }.map { DiscoverOption(stringResource(it.label), it.key) }
            ) { onChange(filters.copy(catalog = it)) }
            Filter(R.string.discover_filter_genre, filters.genre.orEmpty(),
                listOf(DiscoverOption(any, "")) + discoverGenres.map { DiscoverOption(localizedGenreLabel(it), it) }
            ) { onChange(filters.copy(genre = it.ifEmpty { null })) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            Filter(R.string.discover_watch_status, filters.watch.name, DiscoverWatch.entries.map {
                DiscoverOption(stringResource(it.labelResource), it.name)
            }) { onChange(filters.copy(watch = DiscoverWatch.valueOf(it))) }
            Filter(R.string.discover_period, filters.period.name, DiscoverPeriod.entries.map {
                DiscoverOption(stringResource(it.labelResource), it.name)
            }) { onChange(filters.copy(period = DiscoverPeriod.valueOf(it))) }
            Filter(R.string.discover_min_rating, filters.minRating?.toString().orEmpty(),
                listOf(DiscoverOption(any, "")) + (1..10).map { DiscoverOption("$it+", it.toString()) }
            ) { onChange(filters.copy(minRating = it.toIntOrNull())) }
            if (filters.type != "series") {
                MovieReleasePicker(filters.release, { onChange(filters.copy(release = it)) }, Modifier.weight(1f))
            }
            Filter(R.string.discover_sort, filters.sort.name, SavedLibrarySort.entries.map {
                DiscoverOption(stringResource(it.label), it.name)
            }) { onChange(filters.copy(sort = SavedLibrarySort.valueOf(it))) }
        }
    }
}

@Composable
private fun RowScope.Filter(title: Int, value: String, options: List<DiscoverOption>, onSelect: (String) -> Unit) {
    DiscoverDropdownPicker(
        modifier = Modifier.weight(1f),
        title = stringResource(title),
        value = options.firstOrNull { it.value == value }?.label.orEmpty(),
        selectedValue = value,
        options = options,
        onSelect = { onSelect(it.value) }
    )
}
