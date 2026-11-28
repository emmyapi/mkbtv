package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MovieDistributionDates
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape

internal fun preferredAgeRating(local: String?, standard: String?, fallback: String?): String? =
    listOf(local, standard, fallback).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }

fun MetaPreviewDto.toDomainOrNull(catalogType: String, sourceAddonBaseUrl: String? = null): MetaPreview? {
    val resolvedId = id?.takeIf { it.isNotBlank() } ?: return null
    val resolvedName = name?.takeIf { it.isNotBlank() } ?: return null
    val resolvedType = type?.takeIf { it.isNotBlank() } ?: catalogType
    val contentType = ContentType.fromString(resolvedType)
    val dates = appExtras?.releaseDates?.results
    return MetaPreview(
        id = resolvedId,
        type = contentType,
        rawType = resolvedType,
        name = resolvedName,
        poster = poster,
        posterShape = PosterShape.fromString(posterShape),
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating?.toFloatOrNull(),
        popularity = parsePopularity(popularity),
        genres = genres ?: emptyList(),
        runtime = runtime,
        status = status?.trim()?.takeIf { it.isNotBlank() },
        movieDistributionDates = if (contentType == ContentType.MOVIE && !dates.isNullOrEmpty()) {
            MovieDistributionDates.fromTypedDates(dates.asSequence().flatMap { it.releaseDates.orEmpty().asSequence() }
                .map { it.type to it.releaseDate })
        } else null,
        ageRating = preferredAgeRating(appExtras?.certificationLocal, appExtras?.certification, ageRating),
        released = released,
        country = country,
        imdbId = imdbId,
        slug = slug,
        landscapePoster = landscapePoster,
        rawPosterUrl = rawPosterUrl,
        director = coerceStringList(director),
        writer = coerceStringList(writer).ifEmpty { coerceStringList(writers) },
        links = links?.mapNotNull { it.toDomain() } ?: emptyList(),
        behaviorHints = mapBehaviorHints(behaviorHints),
        trailers = mapTrailers(trailers, trailerStreams),
        trailerYtIds = collectTrailerYtIds(trailers, trailerStreams),
        sourceAddonBaseUrl = sourceAddonBaseUrl
    )
}
