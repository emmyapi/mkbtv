package com.nuvio.tv.core.image

import android.content.Context
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomPosterFallbackInterceptorTest {
    @Test fun onlyRequestsAnAlternativeAfterTheImageLoaderFails() = runTest {
        assertEquals(listOf("custom"), attempts(success = "custom"))
        assertEquals(listOf("custom", "search"), attempts(success = "search"))
        assertEquals(listOf("custom", "search", "index"), attempts(success = "index"))
        assertEquals(listOf("custom", "search", "index"), attempts(success = null))
    }

    @Test fun neverRetriesTheSameUrl() = runTest {
        assertEquals(listOf("custom", "search"), attempts(success = null, index = "search"))
        assertEquals(listOf("custom", "index"), attempts(success = null, search = "custom"))
    }

    private suspend fun attempts(success: String?, search: String = "search", index: String = "index"): List<String> {
        val initialRequest = ImageRequest.Builder(mockk<Context>(relaxed = true))
            .data("custom")
            .memoryCacheKeyExtra(CustomPosterFallbackInterceptor.FALLBACK_URL_KEY, search)
            .memoryCacheKeyExtra(CustomPosterFallbackInterceptor.INDEX_POSTER_URL_KEY, index)
            .build()
        val urls = mutableListOf<String>()
        fun chain(forRequest: ImageRequest): Interceptor.Chain = object : Interceptor.Chain {
            override val request = forRequest
            override val size = Size.ORIGINAL
            override fun withRequest(request: ImageRequest) = chain(request)
            override fun withSize(size: Size) = this
            override suspend fun proceed(): ImageResult {
                val url = request.data as String
                urls += url
                return if (url == success) mockk<SuccessResult>() else mockk<ErrorResult>()
            }
        }
        CustomPosterFallbackInterceptor().intercept(chain(initialRequest))
        return urls
    }
}
