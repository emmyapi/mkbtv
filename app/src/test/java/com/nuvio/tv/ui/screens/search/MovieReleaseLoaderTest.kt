package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.data.repository.MovieRelease
import com.nuvio.tv.data.repository.MovieReleaseReply
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieReleaseLoaderTest {
    @Test fun batchesDistinctIdsAndRetriesColdOrFailedRequestsOnRevisit() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<List<String>>()
        val known = mutableMapOf<String, MovieRelease>()
        val ids = (1..110).map { "tt$it" }
        var cold = true
        val loader = MovieReleaseLoader(scope, { _, batch ->
            calls += batch
            if (cold && "tt110" in batch) throw IOException("temporarily offline")
            MovieReleaseReply(batch.associateWith { MovieRelease(if (cold && it != "tt1") "unknown" else "home") },
                pending = cold)
        }, { _, result -> known.putAll(result) }, { testScheduler.currentTime })

        loader.request(1, (ids + ids).asSequence())
        assertTrue(loader.isPending("tt110"))
        advanceUntilIdle()
        assertFalse(loader.isPending("tt110"))
        assertEquals(listOf(100, 10, 100, 100, 100), calls.map { it.size })
        assertEquals("home", known["tt1"]?.status)
        assertEquals("unknown", known["tt2"]?.status)
        assertFalse("tt110" in known)

        // Recomposition at the end of polling cannot immediately restart it.
        loader.request(1, ids.asSequence())
        advanceUntilIdle()
        assertEquals(5, calls.size)
        cold = false
        testScheduler.advanceTimeBy(15_001)
        loader.request(1, sequenceOf("tt2", "tt110"))
        advanceUntilIdle()
        assertEquals(listOf("tt2", "tt110"), calls.last())
        assertEquals("home", known["tt2"]?.status)
        assertEquals("home", known["tt110"]?.status)
        scope.cancel()
    }
}
