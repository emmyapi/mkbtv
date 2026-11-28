package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.R
import com.nuvio.tv.data.repository.DiscoverLibraryFact
import com.nuvio.tv.data.repository.DiscoverLibrarySnapshot
import com.nuvio.tv.data.repository.discoverYearBounds
import com.nuvio.tv.data.repository.movieReleaseFromDates
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.ui.screens.search.DiscoverCatalogOption
import com.nuvio.tv.ui.screens.search.DiscoverPeriod
import com.nuvio.tv.ui.screens.search.DiscoverWatch
import com.nuvio.tv.ui.screens.search.ReleaseWindow
import java.time.LocalDate
import java.time.ZoneOffset

enum class SavedLibrarySort(val label: Int) {
    RECENTLY_ADDED(R.string.library_recently_added),
    TRENDING(R.string.discover_trending),
    POPULAR(R.string.discover_popular),
    TOP_RATED(R.string.discover_top_rated),
    NEWEST(R.string.discover_newest),
    OLDEST(R.string.discover_oldest),
    TITLE(R.string.discover_title_order),
    TITLE_DESC(R.string.discover_title_desc)
}

data class SavedLibraryFilters(
    val playlist: String? = null,
    val type: String = "any",
    val catalog: String = "all",
    val genre: String? = null,
    val keyword: String = "",
    val period: DiscoverPeriod = DiscoverPeriod.ANY,
    val minRating: Int? = null,
    val watch: DiscoverWatch = DiscoverWatch.ANY,
    val release: ReleaseWindow = ReleaseWindow.ANY,
    val sort: SavedLibrarySort = SavedLibrarySort.RECENTLY_ADDED
) {
    internal val needsIndex: Boolean get() =
        catalog != "all" || keyword.isNotBlank() || release != ReleaseWindow.ANY || genre != null ||
            minRating != null || period != DiscoverPeriod.ANY || watch != DiscoverWatch.ANY ||
            sort in indexedSorts

    internal fun missingCatalog(index: DiscoverLibrarySnapshot): Boolean {
        val chart = when (sort) {
            SavedLibrarySort.TRENDING, SavedLibrarySort.POPULAR -> sort.name.lowercase()
            SavedLibrarySort.TOP_RATED -> "imdb-top-250"
            else -> null
        }
        val supplementalGenre = genre?.lowercase()?.takeIf { it in supplementalGenres }
        return (supplementalGenre != null && "genre:$supplementalGenre" !in index.availableCatalogs) ||
            (catalog != "all" && catalog !in index.availableCatalogs) ||
            (chart != null && chart !in index.availableCatalogs) ||
            (release != ReleaseWindow.ANY && !index.releaseAvailable)
    }

    internal fun withType(value: String): SavedLibraryFilters = copy(
        type = value,
        catalog = catalog.takeIf { key -> DiscoverCatalogOption.entries.any { it.key == key && it.availableFor(value) } } ?: "all",
        release = if (value == "series") ReleaseWindow.ANY else release
    )

    internal fun apply(
        entries: List<LibraryEntry>,
        index: DiscoverLibrarySnapshot,
        indexedKeyword: String?,
        watchedMovies: Set<String>,
        watchedSeries: Set<String>,
        currentYear: Int = LocalDate.now(ZoneOffset.UTC).year
    ): List<LibraryEntry> {
        fun year(entry: LibraryEntry) = index[entry]?.year ?: yearPattern.find(entry.releaseInfo.orEmpty())?.value?.toIntOrNull()
        val (firstYear, lastYear) = discoverYearBounds(period.name.lowercase(), currentYear)
        val selected = entries.filter { entry ->
            val movie = entry.type.equals("movie", ignoreCase = true)
            val kind = when (val raw = entry.type.lowercase()) {
                "show", "tv" -> "series"
                else -> raw
            }
            val fact = index[entry]
            (playlist == null || playlist in entry.listKeys) &&
                (type == "any" || type == kind) &&
                (catalog == "all" || fact?.ranks?.containsKey(catalog) == true) &&
                (genre == null || (fact?.genres?.takeIf { it.isNotEmpty() } ?: entry.genres).any { it.equals(genre, true) }) &&
                (period == DiscoverPeriod.ANY || year(entry)?.let { (firstYear == null || it >= firstYear) && (lastYear == null || it <= lastYear) } == true) &&
                (minRating == null || (fact?.rating ?: entry.imdbRating?.toDouble() ?: -1.0) >= minRating) &&
                (watch == DiscoverWatch.ANY || entry.isSavedWatched(fact, watchedMovies, watchedSeries) == (watch == DiscoverWatch.WATCHED)) &&
                (release == ReleaseWindow.ANY || movie && movieReleaseFromDates(entry.movieDistributionDates, fact?.release)?.status == release.name.lowercase()) &&
                (keyword.isBlank() || entry.name.contains(keyword, ignoreCase = true) ||
                    indexedKeyword == keyword && fact?.keywordMatch == true)
        }
        val byTitle = compareBy<LibraryEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.type }.thenBy { it.id }
        val order = when (sort) {
            SavedLibrarySort.RECENTLY_ADDED -> compareByDescending<LibraryEntry> {
                playlist?.let { key -> it.listAddedAt[key] } ?: it.listedAt
            }.then(byTitle)
            SavedLibrarySort.TRENDING, SavedLibrarySort.POPULAR, SavedLibrarySort.TOP_RATED -> index.comparator(sort.name.lowercase())
            SavedLibrarySort.NEWEST -> compareBy<LibraryEntry, Int?>(nullsLast(reverseOrder())) { year(it) }.then(byTitle)
            SavedLibrarySort.OLDEST -> compareBy<LibraryEntry, Int?>(nullsLast()) { year(it) }.then(byTitle)
            SavedLibrarySort.TITLE -> byTitle
            SavedLibrarySort.TITLE_DESC -> byTitle.reversed()
        }
        return selected.sortedWith(order)
    }
}

internal fun LibraryEntry.isSavedWatched(fact: DiscoverLibraryFact?, movies: Set<String>, series: Set<String>): Boolean {
    val ids = if (type.equals("movie", ignoreCase = true)) movies else series
    return id in ids || imdbId in ids || fact?.imdbId in ids ||
        tmdbId?.let { "tmdb:$it" in ids } == true || fact?.aliases?.any { it in ids } == true
}

private val yearPattern = Regex("""\b\d{4}\b""")

private val supplementalGenres = setOf("kids", "soap", "war & politics")

private val indexedSorts = setOf(SavedLibrarySort.TRENDING, SavedLibrarySort.POPULAR, SavedLibrarySort.TOP_RATED,
    SavedLibrarySort.NEWEST, SavedLibrarySort.OLDEST)
