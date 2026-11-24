package com.nuvio.tv.data.trailer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * A DataSource.Factory that wraps DefaultHttpDataSource and appends YouTube's
 * `&range=start-end` query parameter on each request. YouTube throttles (and
 * kills) connections that try to download full adaptive streams in one shot,
 * but honours chunked range-param requests at full speed.
 *
 * Only activates for googlevideo.com URLs; all other URLs pass through untouched.
 */
@UnstableApi
class YoutubeChunkedDataSourceFactory(
    private val chunkSizeBytes: Long = CHUNK_SIZE
) : DataSource.Factory {

    companion object {
        /** 10 MB chunks – large enough to avoid too many requests, small enough to dodge throttle. */
        private const val CHUNK_SIZE = 10L * 1024 * 1024
    }

    override fun createDataSource(): DataSource {
        val upstream = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
        return YoutubeChunkedDataSource(upstream, chunkSizeBytes)
    }

    internal class YoutubeChunkedDataSource(
        private val upstream: DataSource,
        private val chunkSize: Long
    ) : DataSource {

        private var currentUri: Uri? = null
        private var isYouTubeStream = false
        private var exhausted = false
        private var remainingLength = C.LENGTH_UNSET.toLong()
        private var currentChunkStart = 0L
        private var currentChunkEnd = 0L
        private var bytesReadInChunk = 0L
        private var originalDataSpec: DataSpec? = null

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri
            val host = uri.host.orEmpty()
            isYouTubeStream = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
            currentUri = uri
            exhausted = false

            if (!isYouTubeStream) {
                return upstream.open(dataSpec)
            }

            originalDataSpec = dataSpec
            currentChunkStart = dataSpec.position
            remainingLength = dataSpec.length

            return openNextChunk()
        }

        private fun openNextChunk(): Long {
            val spec = originalDataSpec ?: throw IllegalStateException("No DataSpec")
            val end = if (remainingLength != C.LENGTH_UNSET.toLong()) {
                minOf(currentChunkStart + chunkSize - 1, currentChunkStart + remainingLength - 1)
            } else {
                currentChunkStart + chunkSize - 1
            }
            currentChunkEnd = end

            // Append &range=start-end to the URL (YouTube's own range param, not HTTP Range header)
            val rangedUri = spec.uri.buildUpon()
                .appendQueryParameter("range", "$currentChunkStart-$currentChunkEnd")
                .build()

            val chunkedSpec = spec.buildUpon()
                .setUri(rangedUri)
                .setPosition(0)           // position within this chunk's response
                .setLength(C.LENGTH_UNSET.toLong()) // let the server decide
                .build()

            bytesReadInChunk = 0
            upstream.open(chunkedSpec)
            return remainingLength
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!isYouTubeStream) {
                return upstream.read(buffer, offset, length)
            }

            if (length == 0) return 0
            if (exhausted) return C.RESULT_END_OF_INPUT
            while (true) {
                val count = upstream.read(buffer, offset, length)
                if (count != C.RESULT_END_OF_INPUT) {
                    bytesReadInChunk += count
                    return count
                }

                val requested = currentChunkEnd - currentChunkStart + 1
                if (bytesReadInChunk < requested || bytesReadInChunk == remainingLength) {
                    exhausted = true
                    return C.RESULT_END_OF_INPUT
                }

                currentChunkStart += bytesReadInChunk
                if (remainingLength != C.LENGTH_UNSET.toLong()) remainingLength -= bytesReadInChunk
                upstream.close()
                // The first read of the next chunk follows the same counting path as every other
                // read. An upstream failure must reach Media3 so it can retry at the last offset.
                openNextChunk()
            }
        }

        override fun getUri(): Uri? = upstream.uri ?: currentUri

        override fun close() {
            upstream.close()
            currentUri = null
            originalDataSpec = null
        }
    }
}
