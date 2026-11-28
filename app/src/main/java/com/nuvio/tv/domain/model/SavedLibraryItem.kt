package com.nuvio.tv.domain.model

import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY

data class SavedLibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val genres: List<String>,
    val addonBaseUrl: String?,
    val logo: String? = null,
    val addedAt: Long = 0L,
    // Null is the legacy flat-library format, where every row belongs to Watch Later.
    val playlistAddedAt: Map<String, Long>? = null,
    val movieDistributionDates: MovieDistributionDates? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null
) {
    val memberships: Map<String, Long>
        get() = playlistAddedAt ?: mapOf(LOCAL_LIBRARY_LIST_KEY to addedAt)

    fun withoutPlaylist(key: String): SavedLibraryItem? {
        if (key !in memberships) return this
        val remaining = memberships - key
        return if (remaining.isEmpty()) null else copy(playlistAddedAt = remaining)
    }

    fun toMetaPreview(): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.fromString(type),
            rawType = type,
            name = name,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            imdbId = imdbId,
            movieDistributionDates = movieDistributionDates
        )
    }
}
