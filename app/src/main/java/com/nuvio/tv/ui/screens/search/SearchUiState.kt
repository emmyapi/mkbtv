package com.nuvio.tv.ui.screens.search

import androidx.compose.runtime.Immutable
import com.nuvio.tv.data.repository.DiscoverSource
import com.nuvio.tv.data.repository.DiscoverFailure
import com.nuvio.tv.data.repository.MovieRelease
import com.nuvio.tv.data.repository.movieReleaseFromDates
import com.nuvio.tv.data.repository.movieReleaseId
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.MetaPreview

internal const val MIN_SEARCH_QUERY_LENGTH = 2

internal fun submittedSearchQuery(rawQuery: String): String =
    rawQuery.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }.orEmpty()

internal fun shouldShowDiscoverInSearch(
    discoverLocation: DiscoverLocation,
    query: String,
    submittedQuery: String
): Boolean =
    discoverLocation == DiscoverLocation.IN_SEARCH &&
        query.trim().length < MIN_SEARCH_QUERY_LENGTH &&
        submittedQuery.isBlank()

enum class ReleaseWindow { ANY, UPCOMING, CINEMA, HOME }

@Immutable
data class SearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val isSearching: Boolean = false,
    val error: String? = null,
    val catalogRows: List<CatalogRow> = emptyList(),
    val movieReleasesEnabled: Boolean = false,
    val movieReleases: Map<String, MovieRelease> = emptyMap(),
    val searchReleaseWindow: ReleaseWindow = ReleaseWindow.ANY,
    val searchReleasePending: Boolean = false,
    val discoverReleaseWindow: ReleaseWindow = ReleaseWindow.ANY,
    val discoverReleasePending: Boolean = false,
    val installedAddons: List<Addon> = emptyList(),
    val discoverLocation: DiscoverLocation = DiscoverLocation.IN_SEARCH,
    val discoverInitialized: Boolean = false,
    val discoverLoading: Boolean = false,
    val discoverLoadingMore: Boolean = false,
    val selectedDiscoverType: String = DISCOVER_TYPE_ANY,
    val selectedDiscoverCatalogKey: String = DiscoverCatalogOption.ALL.key,
    val selectedDiscoverGenre: String? = null,
    val discoverKeyword: String = "",
    val discoverPeriod: DiscoverPeriod = DiscoverPeriod.ANY,
    val discoverMinRating: Int? = null,
    val discoverWatch: DiscoverWatch = DiscoverWatch.ANY,
    val discoverSort: DiscoverSort = DiscoverSort.TRENDING,
    val discoverFailure: DiscoverFailure? = null,
    val discoverPosterPattern: String = "",
    val discoverResults: List<MetaPreview> = emptyList(),
    val discoverTotal: Int? = null,
    val discoverSources: List<DiscoverSource> = emptyList(),
    val discoverHasMore: Boolean = false,
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val recentSearches: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
) {
    fun movieRelease(item: MetaPreview): MovieRelease? =
        if (item.type == ContentType.MOVIE) movieReleaseFromDates(item.movieDistributionDates, movieReleases[item.movieReleaseId])
        else null

    fun discoverMovieRelease(item: MetaPreview): MovieRelease? {
        if (discoverReleaseWindow == ReleaseWindow.ANY) return movieRelease(item)
        if (item.type != ContentType.MOVIE) return null
        // The server matched this poster before counting and paginating the selected release window.
        val phase = discoverReleaseWindow.name.lowercase()
        val cached = movieReleases[item.movieReleaseId]
        return if (cached?.status == phase) cached else MovieRelease(phase, ageRating = cached?.ageRating)
    }

    val discoverError: Boolean get() = discoverFailure != null
}
