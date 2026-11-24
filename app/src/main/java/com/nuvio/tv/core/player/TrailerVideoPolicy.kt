package com.nuvio.tv.core.player

import android.content.Context
import android.os.Build
import android.view.Display
import com.nuvio.tv.data.trailer.TrailerDisplayCapabilities
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper

/**
 * Automatic previews render through a TextureView on top of the home UI, so a 4K trailer costs a few hundred
 * MB of decoder and GPU memory that full-screen playback (SurfaceView) never pays. The preview setting
 * controls whether those previews may go above 1080p.
 */
object TrailerVideoPolicy {
    const val MAX_TRAILER_HEIGHT_WITHOUT_4K = 1080

    // Same 2 GB / 3 GB boundary as NuvioExoPlayerPerformanceHelper.getFriendlyRamLabel. Devices
    // report less than their marketed RAM (a 2 GB stick ~1.8-1.95 GB, a 3 GB SHIELD ~2.88 GB).
    private const val UP_TO_2GB_THRESHOLD_BYTES = 2_355L * 1024L * 1024L

    /** Default for the "4K trailers" setting: off up to 2 GB of RAM, on above that or when unknown. */
    fun default4kTrailers(context: Context): Boolean {
        val totalRam = NuvioExoPlayerPerformanceHelper.getDevicePhysicalRamBytes(context)
        return totalRam !in 1 until UP_TO_2GB_THRESHOLD_BYTES
    }

    /** Tallest trailer video to play; [Int.MAX_VALUE] means no cap. */
    fun maxTrailerVideoHeight(allow4k: Boolean): Int =
        if (allow4k) Int.MAX_VALUE else MAX_TRAILER_HEIGHT_WITHOUT_4K
}

/** Active HDMI mode, which can differ from the resolution of the Android TV UI. */
@Suppress("DEPRECATION")
internal fun Display?.trailerDisplayCapabilities(): TrailerDisplayCapabilities {
    val mode = this?.mode
    val hdrTypes = if (Build.VERSION.SDK_INT >= 34) mode?.supportedHdrTypes else this?.hdrCapabilities?.supportedHdrTypes
    return TrailerDisplayCapabilities(
        maxWidth = mode?.physicalWidth ?: 1920,
        maxHeight = mode?.physicalHeight ?: 1080,
        supportsHdr10 = hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true,
        supportsHlg = hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HLG) == true
    )
}
