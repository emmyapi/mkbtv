package com.nuvio.tv.data.trailer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YoutubeChunkedDataSourceTest {
    private val payload = ByteArray(15) { it.toByte() }
    private val uri = mockk<Uri>()
    private val ranges = mutableListOf<String>()
    private val upstream = mockk<DataSource>(relaxed = true)
    private val source = YoutubeChunkedDataSourceFactory.YoutubeChunkedDataSource(upstream, 4L)

    private fun open(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong(), failAt: Int? = null) {
        val requested = slot<String>()
        val builder = mockk<Uri.Builder>()
        every { uri.host } returns "r1.googlevideo.com"
        every { uri.buildUpon() } returns builder
        every { builder.appendQueryParameter("range", capture(requested)) } returns builder
        every { builder.build() } returns uri
        var stream = ByteArrayInputStream(byteArrayOf())
        every { upstream.open(any()) } answers {
            ranges += requested.captured
            val (start, end) = requested.captured.split('-').map(String::toInt)
            if (start == failAt) throw IOException("Connection interrupted")
            val count = (minOf(end + 1, payload.size) - start).coerceAtLeast(0)
            stream = ByteArrayInputStream(payload, start, count)
            count.toLong()
        }
        every { upstream.read(any(), any(), any()) } answers {
            stream.read(firstArg<ByteArray>(), secondArg<Int>(), thirdArg<Int>())
        }
        source.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build())
    }

    private fun readAll(): ByteArray {
        val buffer = ByteArray(3)
        val bytes = ByteArrayOutputStream()
        try {
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                bytes.write(buffer, 0, count)
            }
            assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 1))
            return bytes.toByteArray()
        } finally {
            source.close()
        }
    }

    @Test
    fun readsCompleteStreamAcrossFourChunks() {
        open()
        assertArrayEquals(payload, readAll())
        assertEquals(listOf("0-3", "4-7", "8-11", "12-15"), ranges)
    }

    @Test
    fun stopsAtRequestedLengthAfterSeek() {
        open(position = 2, length = 9)
        assertArrayEquals(payload.copyOfRange(2, 11), readAll())
        assertEquals(listOf("2-5", "6-9", "10-10"), ranges)
    }

    @Test
    fun openingNextChunkPropagatesNetworkFailure() {
        open(failAt = 4)
        assertThrows(IOException::class.java) { readAll() }
        assertEquals(listOf("0-3", "4-7"), ranges)
    }
}
