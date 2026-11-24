package com.nuvio.tv.core.image

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest

/**
 * Coil interceptor that detects failed custom poster loads and retries with the
 * original poster URL stored in [ImageRequest.memoryCacheKeyExtra] under the
 * [FALLBACK_URL_KEY] key.
 *
 * This runs globally in the ImageLoader pipeline, so every composable that loads
 * posters (ModernHomeRows, ContentCard, GridContentCard, ContinueWatching, etc.)
 * gets automatic fallback without per-composable code.
 */
class CustomPosterFallbackInterceptor : Interceptor {

    companion object {
        const val FALLBACK_URL_KEY = "custom_poster_fallback_url"
        const val INDEX_POSTER_URL_KEY = "discover_index_poster_url"
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        var result = chain.proceed()
        if (result !is ErrorResult) return result
        val request = chain.request
        val extras = request.memoryCacheKeyExtras
        val alternatives = listOfNotNull(extras[FALLBACK_URL_KEY], extras[INDEX_POSTER_URL_KEY])
            .filter { it.isNotBlank() && it != request.data }
            .distinct()
        for (url in alternatives) {
            val fallback = request.newBuilder()
                .data(url)
                .memoryCacheKey(null as String?)
                .memoryCacheKeyExtras(extras - FALLBACK_URL_KEY - INDEX_POSTER_URL_KEY)
                .build()
            result = chain.withRequest(fallback).proceed()
            if (result !is ErrorResult) break
        }
        return result
    }
}
