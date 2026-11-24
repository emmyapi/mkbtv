package com.nuvio.tv.data.trailer

import java.net.URI

/** Track metadata and URLs come from the YouTube player response for one video ID. */
internal data class FullscreenTrailerFormat(
    val source: TrailerPlaybackSource,
    val fps: Int,
    val bitrate: Double
)

/** A refreshed signature or an alternative audio URL does not repair a video decoder failure. */
internal data class TrailerVideoFormat(val width: Int, val height: Int, val codec: String?, val transfer: TrailerTransfer)

internal val TrailerPlaybackSource.videoFormat: TrailerVideoFormat
    get() = TrailerVideoFormat(width, height, videoCodec, transfer)

/** Reserve one reachable-video attempt per quality tier before spending spare attempts on alternatives. */
internal fun fullscreenProbeChoices(ranked: List<FullscreenTrailerFormat>): List<List<FullscreenTrailerFormat>> {
    val byVideo = ranked.groupBy { it.source.videoUrl }.values
    val tiers = byVideo.groupBy { choices ->
        val source = choices.first().source
        // All sub-720p variants are one fallback tier. Unknown transfer is a last resort.
        when {
            source.transfer == TrailerTransfer.UNKNOWN -> -1
            source.resolutionClass < 720 -> 0
            source.resolutionClass == 2160 && source.transfer.isHdr -> 2161
            else -> source.resolutionClass
        }
    }
    var spare = (3 - tiers.size).coerceAtLeast(0)
    return tiers.values.flatMap { videos ->
        val extra = minOf(spare, videos.size - 1)
        spare -= extra
        videos.take(1 + extra)
    }
}

internal fun isYouTubeMediaUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    val host = uri.host?.lowercase().orEmpty()
    uri.scheme == "https" && uri.userInfo == null &&
        (host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
            host == "youtube.com" || host.endsWith(".youtube.com"))
}.getOrDefault(false)

internal fun trailerTransfer(transfer: String?, videoCodec: String?, hls: Boolean = false): TrailerTransfer {
    val value = transfer.orEmpty().uppercase()
    return when {
        value == "PQ" || "SMPTEST2084" in value || "SMPTE2084" in value -> TrailerTransfer.PQ
        value == "HLG" || "ARIB_STD_B67" in value -> TrailerTransfer.HLG
        value == "SDR" || "BT709" in value || "IEC61966" in value -> TrailerTransfer.SDR
        // A missing VIDEO-RANGE on an HLS variant means SDR. AVC YouTube streams are SDR.
        value.isEmpty() && (hls || videoCodec.orEmpty().startsWith("avc1")) -> TrailerTransfer.SDR
        else -> TrailerTransfer.UNKNOWN
    }
}

internal fun rankedFullscreenFormats(
    formats: List<FullscreenTrailerFormat>,
    display: TrailerDisplayCapabilities,
    excluded: Set<TrailerVideoFormat> = emptySet(),
    canPlay: (TrailerPlaybackSource, Int) -> Boolean
): List<FullscreenTrailerFormat> = formats.asSequence()
    .filter { (source) ->
        source.width in 1..display.maxWidth && source.height in 1..display.maxHeight &&
            source.videoFormat !in excluded && display.supports(source.transfer) && source.videoCodec != null && source.audioCodec != null &&
            isYouTubeMediaUrl(source.videoUrl) && (source.audioUrl == null || isYouTubeMediaUrl(source.audioUrl))
    }
    .filter { canPlay(it.source, it.fps) }
    .distinctBy { it.source.videoUrl to it.source.audioUrl }
    .sortedWith(compareByDescending<FullscreenTrailerFormat> { it.source.qualityRank }
        .thenByDescending { it.fps }.thenByDescending { it.bitrate })
    .toList()
