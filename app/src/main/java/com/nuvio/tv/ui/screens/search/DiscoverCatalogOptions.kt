package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.R

internal const val DISCOVER_TYPE_ANY = "any"
private val mediaTypes = listOf("movie", "series")
internal val discoverTypes = listOf(DISCOVER_TYPE_ANY) + mediaTypes

internal enum class DiscoverCatalogOption(
    val key: String,
    val label: Int,
    private val streamingService: Boolean = false
) {
    ALL("all", R.string.discover_all),
    DISNEY("disney-plus", R.string.discover_disney_plus, true),
    NETFLIX("netflix", R.string.discover_netflix, true),
    HBO("hbo", R.string.discover_hbo, true),
    PRIME_VIDEO("prime-video", R.string.discover_prime_video, true),
    HULU("hulu", R.string.discover_hulu, true),
    TRENDING("trending", R.string.discover_trending),
    POPULAR("popular", R.string.discover_popular),
    AIRING_TODAY("airing-today", R.string.discover_airing_today),
    NEW_AND_RETURNING("new-and-returning", R.string.discover_new_and_returning);

    fun availableFor(type: String): Boolean = when {
        this == ALL || streamingService -> type in discoverTypes
        this == AIRING_TODAY || this == NEW_AND_RETURNING -> type == "series"
        else -> type in mediaTypes
    }
}

// The filter keys are independent of the display language.
private val movieGenres = listOf("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary",
    "Drama", "Family", "Fantasy", "History", "Horror", "Music", "Mystery", "Romance", "Science Fiction",
    "TV Movie", "Thriller", "War", "Western")
private val seriesGenres = listOf("Action & Adventure", "Animation", "Comedy", "Crime", "Documentary",
    "Drama", "Family", "Kids", "Mystery", "News", "Reality", "Sci-Fi & Fantasy", "Soap", "Talk", "War & Politics", "Western")

// Additional IMDb/Cinemeta genres used by the streaming feeds.
internal val discoverGenres = (movieGenres + seriesGenres + listOf(
    "Adult", "Biography", "Film Noir", "Game Show", "Musical", "Short", "Sport"
)).distinct().sorted()
