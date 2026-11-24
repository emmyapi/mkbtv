package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.CatalogRepository
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"

class CinemetaRelatedService @Inject constructor(private val catalogs: CatalogRepository) {
    suspend fun related(meta: Meta, excludedIds: Set<String>, hideUnreleased: Boolean): List<MetaPreview> {
        val type = when (meta.type) {
            ContentType.MOVIE -> "movie"
            ContentType.SERIES, ContentType.TV -> "series"
            else -> return emptyList()
        }
        val genres = meta.genres.flatMap(::cinemetaGenres).distinct()
        val candidates = coroutineScope {
            genres.take(2).map { genre ->
                async {
                    withTimeoutOrNull(5_000) {
                        val result = catalogs.getCatalog(
                            addonBaseUrl = CINEMETA_BASE_URL, addonId = "com.linvo.cinemeta",
                            addonName = "Cinemeta", catalogId = "top", catalogName = "Popular",
                            type = type, extraArgs = mapOf("genre" to genre),
                            posterScreen = CustomPosterScreen.SEARCH
                        ).first { it !is NetworkResult.Loading }
                        (result as? NetworkResult.Success)?.data?.items
                    }.orEmpty()
                }
            }.awaitAll().flatten()
        }
        val crew = (meta.director + meta.writer).toSet()
        val year = meta.releaseInfo?.take(4)?.toIntOrNull()
        val today = java.time.LocalDate.now()
        return candidates.asSequence()
            .filter { it.id !in excludedIds && it.imdbId !in excludedIds && !it.poster.isNullOrBlank() }
            .filterNot { it.name.equals(meta.name, ignoreCase = true) && (year == null || it.releaseInfo?.take(4)?.toIntOrNull() == year) }
            .filterNot { hideUnreleased && it.isUnreleased(today) }
            .distinctBy { it.imdbId ?: it.id }
            .sortedWith(compareByDescending<MetaPreview> { item -> item.genres.count { it in genres } }
                .thenByDescending { item -> (item.director + item.writer).count { it in crew } }
                .thenBy { item ->
                    val candidateYear = item.releaseInfo?.take(4)?.toIntOrNull()
                    if (year != null && candidateYear != null) abs(candidateYear - year) else Int.MAX_VALUE
                })
            .take(20)
            .toList()
    }

    private fun cinemetaGenres(raw: String): List<String> = when (raw.lowercase().trim()) {
        "science fiction", "sci-fi" -> listOf("Sci-Fi")
        "sci-fi & fantasy" -> listOf("Sci-Fi", "Fantasy")
        "action & adventure" -> listOf("Action", "Adventure")
        "war & politics" -> listOf("War")
        "kids" -> listOf("Family")
        else -> listOf(raw.trim().lowercase().replaceFirstChar { it.uppercase() }).filter { it.isNotEmpty() }
    }
}
