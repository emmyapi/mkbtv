package com.nuvio.tv.data.trailer

import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.concurrent.ConcurrentHashMap

private val CODECS_PARAM_REGEX = Regex("codecs=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

/** The `codecs` parameter of a YouTube format's `mimeType`, e.g. `av01.0.12M.10`. */
internal fun videoCodecsParam(formatMimeType: String): String? =
    CODECS_PARAM_REGEX.find(formatMimeType)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The decoder MIME type for a `codecs` string, e.g. `av01.0.12M.10` -> `video/av01`. Null when
 * the codec isn't one we know, which callers treat as playable.
 */
internal fun videoCodecMimeType(codecs: String): String? {
    val codec = codecs.trim().lowercase()
    return when {
        codec.startsWith("av01") -> MimeTypes.VIDEO_AV1
        codec.startsWith("vp09") || codec.startsWith("vp9") -> MimeTypes.VIDEO_VP9
        codec.startsWith("avc1") -> MimeTypes.VIDEO_H264
        codec.startsWith("hev1") || codec.startsWith("hvc1") -> MimeTypes.VIDEO_H265
        else -> null
    }
}

/**
 * Asks the same question the player's video renderer asks: is there a decoder for this codec,
 * profile and level at this size and frame rate? YouTube serves many 4K trailers only as AV1,
 * which TV boxes like the SHIELD have no decoder for, so the trailer played its audio over a
 * black screen.
 */
internal object VideoDecoderSupport {
    private val cache = ConcurrentHashMap<String, Boolean>()

    /** Manual selection needs affirmative support for both tracks, including HDR profile support. */
    @OptIn(UnstableApi::class)
    fun canPlayFullscreen(source: TrailerPlaybackSource, fps: Int): Boolean {
        val video = source.videoCodec ?: return false
        val audio = source.audioCodec ?: return false
        val videoMime = videoCodecMimeType(video) ?: return false
        val audioMime = MimeTypes.getMediaMimeType(audio)?.takeIf { MimeTypes.isAudio(it) } ?: return false
        val audioSupported = cache.getOrPut("audio|$audio") {
            supportsFormat(Format.Builder().setSampleMimeType(audioMime).setCodecs(audio).build())
        }
        if (!audioSupported) return false

        return cache.getOrPut("fullscreen|$video|${source.width}|${source.height}|$fps|${source.transfer}") {
            val format = Format.Builder()
                .setSampleMimeType(videoMime)
                .setCodecs(video)
                .setWidth(source.width)
                .setHeight(source.height)
                .apply {
                    if (fps > 0) setFrameRate(fps.toFloat())
                    if (source.transfer.isHdr) {
                        val transfer = if (source.transfer == TrailerTransfer.HLG) C.COLOR_TRANSFER_HLG else C.COLOR_TRANSFER_ST2084
                        setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(transfer).build())
                    }
                }.build()
            supportsFormat(format, source.transfer)
        }
    }

    @OptIn(UnstableApi::class)
    private fun supportsFormat(format: Format, transfer: TrailerTransfer = TrailerTransfer.UNKNOWN): Boolean {
        val mime = format.sampleMimeType ?: return false
        return try {
            MediaCodecUtil.getDecoderInfos(mime, false, false).any { decoder ->
                decoder.isFormatSupported(format) &&
                    (!transfer.isHdr || decoder.profileLevels.any { supportsHdrProfile(mime, it.profile, transfer) })
            }
        } catch (_: Exception) {
            false
        }
    }

    // Android lists HDR10 separately from plain 10-bit profiles. HLG can use the 10-bit profile.
    private fun supportsHdrProfile(mime: String, profile: Int, transfer: TrailerTransfer): Boolean =
        when (mime) {
            MimeTypes.VIDEO_VP9 -> profile == CodecProfileLevel.VP9Profile2HDR ||
                profile == CodecProfileLevel.VP9Profile2HDR10Plus ||
                (transfer == TrailerTransfer.HLG && profile == CodecProfileLevel.VP9Profile2)
            MimeTypes.VIDEO_H265 -> profile == CodecProfileLevel.HEVCProfileMain10HDR10 ||
                profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus ||
                (transfer == TrailerTransfer.HLG && profile == CodecProfileLevel.HEVCProfileMain10)
            MimeTypes.VIDEO_AV1 -> profile == CodecProfileLevel.AV1ProfileMain10HDR10 ||
                profile == CodecProfileLevel.AV1ProfileMain10HDR10Plus ||
                (transfer == TrailerTransfer.HLG && profile == CodecProfileLevel.AV1ProfileMain10)
            else -> false
        }

    @OptIn(UnstableApi::class)
    fun canDecode(codecs: String, width: Int, height: Int, fps: Int): Boolean =
        cache.getOrPut("$codecs|$width|$height|$fps") {
            val mimeType = videoCodecMimeType(codecs) ?: return@getOrPut true
            val format = Format.Builder()
                .setSampleMimeType(mimeType)
                .setCodecs(codecs)
                .apply {
                    if (width > 0 && height > 0) setWidth(width).setHeight(height)
                    if (fps > 0) setFrameRate(fps.toFloat())
                }
                .build()
            try {
                MediaCodecUtil.getDecoderInfos(mimeType, /* secure= */ false, /* tunneling= */ false)
                    .any { it.isFormatSupported(format) }
            } catch (e: Exception) {
                // If the device can't be asked, don't drop streams on a guess.
                true
            }
        }
}
