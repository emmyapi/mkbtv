package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape

internal fun MetaPreview.toLibraryEntryInput(addonBaseUrl: String?, originalId: String = id): LibraryEntryInput {
    val parsedIds = parseContentIds(id)
    val original = parseContentIds(originalId)
    // The library renders as portrait. If the source MetaPreview was built for landscape
    // display (e.g. TMDB collection / more-like-this), its `poster` field holds the backdrop.
    // Prefer `rawPosterUrl` (the proper portrait) when available so the saved entry isn't a
    // stretched/cropped landscape inside a portrait card.
    val isLandscapeSource = posterShape == PosterShape.LANDSCAPE
    val portraitPoster = rawPosterUrl?.takeIf { it.isNotBlank() }
    val savedPoster = if (isLandscapeSource && portraitPoster != null) portraitPoster else poster
    val savedShape = if (isLandscapeSource) PosterShape.POSTER else posterShape
    return LibraryEntryInput(
        itemId = originalId,
        itemType = apiType,
        title = name,
        year = extractYear(releaseInfo),
        traktId = parsedIds.trakt ?: original.trakt,
        simklId = parsedIds.simkl ?: original.simkl,
        imdbId = parsedIds.imdb ?: imdbId ?: original.imdb,
        tmdbId = parsedIds.tmdb ?: original.tmdb,
        poster = savedPoster,
        posterShape = savedShape,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        movieDistributionDates = movieDistributionDates,
        genres = genres,
        addonBaseUrl = addonBaseUrl
    )
}
