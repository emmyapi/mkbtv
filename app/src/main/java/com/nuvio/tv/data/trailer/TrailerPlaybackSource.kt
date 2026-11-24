package com.nuvio.tv.data.trailer

enum class TrailerTransfer {
    SDR, PQ, HLG, UNKNOWN;

    val isHdr: Boolean get() = this == PQ || this == HLG
}

data class TrailerDisplayCapabilities(
    val maxWidth: Int,
    val maxHeight: Int,
    val supportsHdr10: Boolean,
    val supportsHlg: Boolean
) {
    internal fun supports(transfer: TrailerTransfer): Boolean = when (transfer) {
        TrailerTransfer.PQ -> supportsHdr10
        TrailerTransfer.HLG -> supportsHlg
        else -> true
    }

    internal fun isOptimal(source: TrailerPlaybackSource): Boolean {
        val bestClass = trailerResolutionClass(maxWidth, maxHeight)
        return source.transfer != TrailerTransfer.UNKNOWN && source.resolutionClass >= bestClass &&
            (bestClass != 2160 || source.transfer.isHdr || (source.transfer == TrailerTransfer.SDR && !supportsHdr10 && !supportsHlg))
    }
}

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val transfer: TrailerTransfer = TrailerTransfer.UNKNOWN,
    val youtubeVideoId: String? = null,
    val isHls: Boolean = false
) {
    internal val resolutionClass: Int get() = trailerResolutionClass(width, height)

    // Equal tiers retain the earlier official/localized upload in the catalog.
    internal val qualityRank: Int get() = (if (transfer == TrailerTransfer.UNKNOWN) 0 else 10_000) +
        resolutionClass * 3 + when {
        resolutionClass != 2160 -> 0
        transfer.isHdr -> 2
        transfer == TrailerTransfer.SDR -> 1
        else -> 0
    }
}

/** A scope trailer can be e.g. 3840x1608 or 2560x1072 with the letterbox bars cropped. */
internal fun trailerResolutionClass(width: Int, height: Int): Int = when {
    width >= 3840 || height >= 2160 -> 2160
    width >= 2560 || height >= 1440 -> 1440
    width >= 1920 || height >= 1080 -> 1080
    width >= 1280 || height >= 720 -> 720
    else -> height.coerceIn(0, 719)
}
