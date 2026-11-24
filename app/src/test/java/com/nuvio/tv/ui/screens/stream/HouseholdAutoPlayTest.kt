package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.HOUSEHOLD_ADDON
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HouseholdAutoPlayTest {
    private val base = "http://10.0.0.2:4301/test".toHttpUrl()
    private val settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
    private val addons = setOf(HOUSEHOLD_ADDON)

    private fun offer(n: Int, group: String? = null, name: String = "1080p"): Stream {
        val id = n.toString(16).padStart(64, '0')
        return Stream(
            name, null, null, "$base/play/sample.$id", null, null, null, null,
            StreamBehaviorHints(null, group, null, null, filename = "$n.mkv", videoSize = 1024, nuvioSourceId = id),
            HOUSEHOLD_ADDON, null
        )
    }

    @Test fun onlyTheRegisteredPrivateGuardAndSignedPathCanBeProbed() {
        val valid = offer(1)
        val url = requireNotNull(valid.url)
        assertNotNull(HouseholdAutoPlay.guardEndpoint(base, valid))
        for (invalid in listOf(
            url.replace("/test/", "/other/"), url.replace("4301", "4302"), url.replace("http:", "https:"),
            url.replace("10.0.0.2", "10.0.0.3"), url.replace("/play/", "/stream/"),
            url.replace("/sample.", "/bad/path."), "$url?extra=1", "$url#part", url.replace("http://", "http://user@")
        )) assertNull(HouseholdAutoPlay.guardEndpoint(base, valid.copy(url = invalid)))
        for (host in listOf("example.test", "10.0.0.2.example.test", "8.8.8.8", "127.0.0.1", "169.254.1.1")) {
            val wrongBase = base.newBuilder().host(host).build()
            assertNull(HouseholdAutoPlay.guardEndpoint(wrongBase, valid.copy(url = url.replace(base.host, host))))
        }
        assertNull(HouseholdAutoPlay.guardEndpoint(base, valid.copy(addonName = "Other")))
        assertNull(HouseholdAutoPlay.guardEndpoint(base, valid.copy(behaviorHints = null)))
    }

    @Test fun exactPickThenEligiblePreferredGroupThenRankedOffersWithoutDuplicateFiles() = runBlocking {
        val exact = offer(3)
        val duplicate = offer(4).copy(behaviorHints = exact.behaviorHints!!.copy(nuvioSourceId = "4".repeat(64)))
        val excluded = offer(1, group = "same", name = "720p")
        val preferred = offer(2, group = "same")
        val firstRanked = offer(5)
        val seen = mutableListOf<String>()
        val result = HouseholdAutoPlay.verify(exact, listOf(duplicate, firstRanked, excluded, preferred, exact), base,
            settings.copy(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "1080p"),
            addons, "same") {
            seen += it.toString()
            listOf(409, 503, 302)[seen.lastIndex]
        }
        assertEquals(firstRanked, result)
        assertEquals(listOf(exact.url, preferred.url, firstRanked.url), seen)
    }

    @Test fun rejectedCandidatesAreBoundedAndUnexpectedResponsesStopImmediately() = runBlocking {
        val offers = (1..8).map { offer(it) }
        for ((status, expectedCount) in listOf(410 to 4, 503 to 4, 403 to 1, 200 to 1)) {
            var attempts = 0
            assertNull(HouseholdAutoPlay.verify(offers.first(), offers, base, settings, addons, null) {
                attempts++
                status
            })
            assertEquals(expectedCount, attempts)
        }
        var attempts = 0
        assertNull(HouseholdAutoPlay.verify(offers.first(), offers, base,
            settings.copy(streamAutoPlayMode = StreamAutoPlayMode.MANUAL), addons, null) {
            attempts++
            409
        })
        assertEquals(1, attempts)
    }

    @Test fun headDoesNotFollowTheMediaRedirect() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/media")))
            server.enqueue(MockResponse().setBody("media"))
            assertEquals(302, HouseholdAutoPlay.head(server.url("/signed")))
            assertEquals("HEAD", server.takeRequest(1, TimeUnit.SECONDS)?.method)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
