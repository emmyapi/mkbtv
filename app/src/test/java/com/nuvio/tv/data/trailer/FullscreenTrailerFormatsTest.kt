package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenTrailerFormatsTest {
    private val hdrTv = TrailerDisplayCapabilities(3840, 2160, supportsHdr10 = true, supportsHlg = false)
    private val extractor = InAppYouTubeExtractor()
    private val master = "https://manifest.googlevideo.com/trailer/master.m3u8"

    @Test
    fun `ranks cropped 4K HDR, cropped 4K SDR, 1440 and 1080 using actual track sizes`() {
        val hdr = format(3840, 1608, TrailerTransfer.PQ)
        val sdr = format(3840, 1608, TrailerTransfer.SDR)
        val qhd = format(2560, 1072, TrailerTransfer.SDR)
        val fullHd = format(1920, 1080, TrailerTransfer.SDR)
        val hlg = format(3840, 2160, TrailerTransfer.HLG)
        val unknown = format(3840, 1608, TrailerTransfer.UNKNOWN)
        assertEquals(listOf(hdr, sdr, qhd, fullHd, unknown),
            rankedFullscreenFormats(listOf(fullHd, qhd, sdr, hlg, unknown, hdr), hdrTv) { _, _ -> true })
        assertTrue(hdrTv.isOptimal(hdr.source))
        assertFalse(hdrTv.isOptimal(sdr.source))
        assertFalse(hdrTv.isOptimal(unknown.source))
        assertTrue(hdrTv.copy(supportsHdr10 = false).isOptimal(sdr.source))
    }

    @Test
    fun `missing audio, incompatible decoder, foreign URLs and oversized tracks cannot win`() {
        val fullHd = format(1920, 800, TrailerTransfer.SDR)
        val cropped4k = format(3840, 1608, TrailerTransfer.SDR)
        val noAudio = fullHd.copy(source = fullHd.source.copy(audioCodec = null))
        val other = fullHd.copy(source = fullHd.source.copy(audioUrl = "https://googlevideo.com.example.org/audio"))
        val av1 = fullHd.copy(source = fullHd.source.copy(videoCodec = "av01.0.08M.08"))
        val display = hdrTv.copy(maxWidth = 1920, maxHeight = 1080)
        val ranked = rankedFullscreenFormats(listOf(cropped4k, other, noAudio, av1, fullHd), display) { src, _ ->
            src.videoCodec == "vp9"
        }
        assertEquals(listOf(fullHd), ranked)
    }

    @Test
    fun `HDR needs actual PQ or HLG metadata`() {
        assertEquals(TrailerTransfer.PQ, trailerTransfer("COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084", "vp9"))
        assertEquals(TrailerTransfer.HLG, trailerTransfer("COLOR_TRANSFER_CHARACTERISTICS_ARIB_STD_B67", "vp9"))
        assertEquals(TrailerTransfer.SDR, trailerTransfer("COLOR_TRANSFER_CHARACTERISTICS_BT709", "vp9"))
        assertEquals(TrailerTransfer.UNKNOWN, trailerTransfer("HDR", "vp9"))
    }

    @Test
    fun `HLS pins the compatible variant and requested audio rendition`() {
        val formats = extractor.fullscreenHlsFormats(master, """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",LANGUAGE="en",DEFAULT=YES,URI="a-en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",LANGUAGE="da",DEFAULT=NO,URI="a-da.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=3840x1608,VIDEO-RANGE=PQ,CODECS="vp09.02.51.10,mp4a.40.2",AUDIO="g"
            hdr.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=2560x1072,CODECS="avc1.640033,mp4a.40.2"
            muxed.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=3840x2160,CODECS="avc1.640033,mp4a.40.2",AUDIO="missing"
            silent.m3u8
        """.trimIndent(), "da")
        assertEquals(2, formats.size)
        val hdr = formats[0].source
        assertEquals("https://manifest.googlevideo.com/trailer/hdr.m3u8", hdr.videoUrl)
        assertEquals("https://manifest.googlevideo.com/trailer/a-da.m3u8", hdr.audioUrl)
        assertEquals(TrailerTransfer.PQ, hdr.transfer)
        assertTrue(hdr.isHls)
        assertEquals(null, formats[1].source.audioUrl)
        assertEquals(TrailerTransfer.SDR, formats[1].source.transfer)
    }

    @Test
    fun `unreachable 4K alternatives leave a probe for every lower tier`() {
        val hdr = format(3840, 1608, TrailerTransfer.PQ)
        val variants = (1..6).map { hdr.copy(source = hdr.source.copy(videoUrl = "${hdr.source.videoUrl}-$it")) }
        val qhd = format(2560, 1072, TrailerTransfer.SDR)
        val fullHd = format(1920, 800, TrailerTransfer.SDR)
        val lower = format(1280, 536, TrailerTransfer.SDR)
        val choices = fullscreenProbeChoices(variants + qhd + fullHd + lower)
        assertEquals(listOf(variants.first(), qhd, fullHd, lower), choices.map { it.first() })
    }

    @Test
    fun `runtime decoder failure excludes same re-signed video format but permits another codec or size`() {
        val failed = format(3840, 1608, TrailerTransfer.PQ)
        val reissued = failed.copy(source = failed.source.copy(videoUrl = "https://media.googlevideo.com/refreshed"))
        val alternate = failed.copy(source = reissued.source.copy(videoCodec = "hvc1.2.4.L153.B0"))
        val smaller = format(2560, 1072, TrailerTransfer.SDR)
        assertEquals(listOf(alternate, smaller), rankedFullscreenFormats(
            listOf(failed, reissued, alternate, smaller), hdrTv, setOf(failed.source.videoFormat)
        ) { _, _ -> true })
    }

    private fun format(width: Int, height: Int, transfer: TrailerTransfer) = FullscreenTrailerFormat(
        TrailerPlaybackSource(
            videoUrl = "https://media.googlevideo.com/video-$width-$height-$transfer",
            audioUrl = "https://media.googlevideo.com/audio", width = width, height = height,
            videoCodec = "vp9", audioCodec = "opus", transfer = transfer
        ), fps = 24, bitrate = 1_000.0
    )
}
