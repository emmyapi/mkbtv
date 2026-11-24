package com.nuvio.tv.data.trailer

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class FullscreenTrailerServiceTest {
    private val hdrTv = TrailerDisplayCapabilities(3840, 2160, true, false)
    private val extractor = mockk<InAppYouTubeExtractor> {
        every { isRateLimited() } returns false
    }
    private val trailerApi = mockk<TrailerApi>()
    private val tmdbApi = mockk<TmdbApi>()
    private val service = TrailerService(trailerApi, tmdbApi, extractor, mockk<TmdbSettingsDataStore> {
        every { settings } returns MutableStateFlow(TmdbSettings(language = "da-DK", useTrailers = false))
    }, mockk<TmdbService>(), maxTrailerVideoHeight = { 1080 })
    private val ids = listOf("n9xhJrPXop4", "second00002", "third000003", "fourth00004")

    @Test
    fun `metadata selects official localized trailer and rejects clips and unrelated URLs`() {
        val trailers = listOf(
            MetaTrailer(ytId = ids[0], type = "Trailer", name = "Unofficial trailer", lang = "da"),
            MetaTrailer(ytId = ids[1], type = "Trailer", name = "Official trailer", lang = "en"),
            MetaTrailer(ytId = ids[2], type = "Trailer", name = "Official trailer", lang = "da"),
            MetaTrailer(ytId = ids[3], type = "Clip", lang = "da")
        )
        assertEquals(listOf(ids[2], ids[0], ids[1]), service.rankedFullscreenIds(trailers, ids, "da-DK"))
        assertEquals(ids.take(3), service.rankedFullscreenIds(
            ids.map { MetaTrailer(ytId = it, type = "Trailer") }, ids, "da-DK"))
        assertEquals(listOf(ids[0]), service.rankedFullscreenIds(emptyList(), listOf(
            "https://example.org/watch?v=${ids[1]}", "https://youtube.com.example.org/watch?v=${ids[2]}",
            "https://youtu.be/${ids[0]}", ids[0]
        ), "da-DK"))
    }

    @Test
    fun `compares at most three different catalog videos independently of preview cap`() = runTest {
        coEvery { extractor.extractFullscreenSource(ids[0], hdrTv, "da-DK") } returns source(1920)
        coEvery { extractor.extractFullscreenSource(ids[1], hdrTv, "da-DK") } returns source(3840)
        coEvery { extractor.extractFullscreenSource(ids[2], hdrTv, "da-DK") } returns source(2560)
        val catalog = listOf(ids[0], "https://youtu.be/${ids[0]}") + ids.drop(1)
        val winner = service.getFullscreenYouTubeTrailer("123", "movie", catalog, hdrTv)
        assertEquals(ids[1], winner?.youtubeVideoId)
        assertEquals(3840, winner?.width)
        assertEquals(winner, service.getFullscreenYouTubeTrailer("123", "movie", catalog, hdrTv))
        coVerify(exactly = 3) { extractor.extractFullscreenSource(any(), any(), any()) }
        coVerify(exactly = 0) { trailerApi.getTrailer(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApi.getMovieVideos(any(), any(), any()) }
    }

    @Test
    fun `two callers share an optimal HDR result without probing other uploads`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { extractor.extractFullscreenSource(ids[0], hdrTv, "da-DK") } coAnswers {
            entered.complete(Unit)
            release.await()
            source(3840, TrailerTransfer.PQ)
        }
        val first = async(Dispatchers.IO) { service.getFullscreenYouTubeTrailer(null, "movie", ids, hdrTv) }
        entered.await()
        val other = async(Dispatchers.IO) { service.getFullscreenYouTubeTrailer(null, "movie", ids, hdrTv) }
        release.complete(Unit)
        assertEquals(ids[0], first.await()?.youtubeVideoId)
        assertEquals(first.await(), other.await())
        coVerify(exactly = 1) { extractor.extractFullscreenSource(any(), any(), any()) }
    }

    @Test
    fun `rate limiting stops requests and leaves later attempts available`() = runTest {
        var blocked = false
        every { extractor.isRateLimited() } answers { blocked }
        coEvery { extractor.extractFullscreenSource(ids[0], hdrTv, "da-DK") } answers { blocked = true; null }
        assertNull(service.getFullscreenYouTubeTrailer(null, "movie", ids, hdrTv))
        coVerify(exactly = 1) { extractor.extractFullscreenSource(any(), any(), any()) }
        blocked = false
        coEvery { extractor.extractFullscreenSource(ids[0], hdrTv, "da-DK") } returns source(3840, TrailerTransfer.PQ)
        assertEquals(ids[0], service.getFullscreenYouTubeTrailer(null, "movie", ids, hdrTv)?.youtubeVideoId)
        coVerify(exactly = 2) { extractor.extractFullscreenSource(any(), any(), any()) }
    }

    @Test
    fun `cached result expires when the earlier signed audio URL expires and retry can invalidate it`() = runTest {
        val stale = source(3840, TrailerTransfer.PQ).copy(audioUrl = "https://media.googlevideo.com/audio?expire=1")
        val renewed = stale.copy(audioUrl = "https://media.googlevideo.com/audio/reissued")
        val refreshed = renewed.copy(audioUrl = "https://media.googlevideo.com/audio/retry")
        coEvery { extractor.extractFullscreenSource(ids[0], hdrTv, "da-DK") } returnsMany listOf(stale, renewed, refreshed)
        suspend fun lookup() = requireNotNull(service.getFullscreenYouTubeTrailer(null, "movie", ids, hdrTv))
        assertEquals(stale.audioUrl, lookup().audioUrl)
        val second = lookup()
        assertEquals(renewed.audioUrl, second.audioUrl)
        service.invalidateFullscreenSource(second)
        assertEquals(refreshed.audioUrl, lookup().audioUrl)
        coVerify(exactly = 3) { extractor.extractFullscreenSource(any(), any(), any()) }
    }

    @Test
    fun `dialog retry bypasses cached failed HDR and keeps normal quality for the next visit`() = runTest {
        val hdr = source(3840, TrailerTransfer.PQ)
        val sdr = source(3840, TrailerTransfer.SDR)
        coEvery { extractor.extractFullscreenSource(ids[0], hdrTv, "da-DK", emptySet()) } returns hdr
        coEvery { extractor.extractFullscreenSource(ids[0], hdrTv, "da-DK", setOf(hdr.videoFormat)) } returns sdr
        val catalog = listOf(ids[0])
        val first = requireNotNull(service.getFullscreenYouTubeTrailer(null, "movie", catalog, hdrTv))
        val retry = service.getFullscreenYouTubeTrailer(null, "movie", catalog, hdrTv, failedSources = listOf(first))
        assertEquals(TrailerTransfer.SDR, retry?.transfer)
        assertEquals(first, service.getFullscreenYouTubeTrailer(null, "movie", catalog, hdrTv))
        coVerify(exactly = 2) { extractor.extractFullscreenSource(any(), any(), any(), any()) }
    }

    @Test
    fun `one localized TMDB lookup can promote an official upload beyond the first three anonymous IDs`() = runTest {
        val configured = configuredService("test-key")
        val anonymous = ids.map { MetaTrailer(ytId = it, type = "Trailer") }
        coEvery { tmdbApi.getMovieVideos(123, "test-key", "da-DK") } returns Response.success(
            TmdbVideosResponse(id = 123, results = listOf(
                TmdbVideoResult(key = ids.last(), type = "Trailer", site = "YouTube", iso6391 = "da", official = true)
            ))
        )
        coEvery { extractor.extractFullscreenSource(any(), hdrTv, "da-DK") } returns source(1920)
        val winner = configured.getFullscreenYouTubeTrailer("123", "movie", ids, hdrTv, anonymous)
        assertEquals(ids.last(), winner?.youtubeVideoId)
        coVerify(exactly = 1) { tmdbApi.getMovieVideos(any(), any(), any()) }
        coVerify(exactly = 3) { extractor.extractFullscreenSource(any(), any(), any()) }
    }

    @Test
    fun `catalog labels are sufficient without another TMDB lookup and explicit official overrides name`() = runTest {
        val configured = configuredService("test-key")
        val catalog = ids.mapIndexed { index, id ->
            MetaTrailer(ytId = id, type = "Trailer", lang = "da",
                official = index == 2, name = if (index == 0) "Official trailer" else null)
        }
        coEvery { extractor.extractFullscreenSource(any(), hdrTv, "da-DK") } returns source(3840, TrailerTransfer.PQ)
        assertEquals(ids[2], configured.getFullscreenYouTubeTrailer("123", "movie", ids, hdrTv, catalog)?.youtubeVideoId)
        val withoutKey = configuredService("")
        assertEquals(ids[0], withoutKey.getFullscreenYouTubeTrailer("123", "movie", ids, hdrTv)?.youtubeVideoId)
        coVerify(exactly = 0) { tmdbApi.getMovieVideos(any(), any(), any()) }
    }

    private fun configuredService(key: String) = TrailerService(trailerApi, tmdbApi, extractor,
        mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "da-DK", useTrailers = true))
        }, mockk<TmdbService> { every { apiKey() } returns key })

    private fun source(width: Int, transfer: TrailerTransfer = TrailerTransfer.SDR) = TrailerPlaybackSource(
        videoUrl = "https://media.googlevideo.com/video-$width",
        audioUrl = "https://media.googlevideo.com/audio", width = width, height = width * 9 / 22,
        videoCodec = "vp9", audioCodec = "opus", transfer = transfer
    )
}
