package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.data.repository.NUVIO_CATALOG_ID
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.skipStep

enum class DiscoverPeriod { ANY, THIS_YEAR, LAST_FIVE_YEARS, DECADE_2020, DECADE_2010, DECADE_2000, DECADE_1990, DECADE_1980, BEFORE_1980, BEFORE_2010 }

enum class DiscoverWatch { ANY, UNWATCHED, WATCHED }

enum class DiscoverSort { TRENDING, POPULAR, TOP_RATED, NEWEST, OLDEST, TITLE, TITLE_DESC }

internal fun CatalogDescriptor.searchSkipStep(addonId: String): Int =
    skipStep(defaultStep = if (addonId == NUVIO_CATALOG_ID) 20 else 100)

// Nuvio derives a page number from skip, even when it returns fewer valid titles.
internal fun CatalogRow.withSearchPageOffset(requestedSkip: Int): CatalogRow =
    if (addonId == NUVIO_CATALOG_ID && hasMore) copy(nextSkip = requestedSkip + skipStep) else this

// The index uses a Watchmode ID only when it has neither an IMDb nor a TMDB detail target.
internal val MetaPreview.hasDiscoverDetails: Boolean get() = !id.startsWith("watchmode:")

internal fun MetaPreview.isDiscoverWatched(watchedMovies: Set<String>, watchedSeries: Set<String>): Boolean {
    val watched = if (apiType.equals("series", true) || apiType.equals("tv", true)) watchedSeries else watchedMovies
    return id in watched || imdbId in watched
}
