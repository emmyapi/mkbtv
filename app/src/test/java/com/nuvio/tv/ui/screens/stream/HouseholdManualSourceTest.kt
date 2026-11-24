package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.BehaviorHintsDto
import com.nuvio.tv.domain.model.HOUSEHOLD_ADDON
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HouseholdManualSourceTest {
    private val firstId = "1".repeat(64)
    private val chosenId = "2".repeat(64)
    private val settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
    private val installed = setOf(HOUSEHOLD_ADDON)

    private fun offer(id: String?, url: String) = Stream(
        name = "Comet · HD", title = null, description = null, url = url, ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null, addonName = HOUSEHOLD_ADDON,
        addonLogo = null, behaviorHints = BehaviorHintsDto(filename = "same.mkv", nuvioSourceId = id).toDomain()
    )

    @Test fun renewedUrlResolvesTheExactManualChoiceAmongSimilarlyNamedFiles() {
        val first = offer(firstId, "https://example.test/first")
        val old = offer(chosenId, "https://example.test/expired")
        val fresh = old.copy(url = "https://example.test/renewed")
        assertEquals(fresh, selectRememberedHouseholdSource(
            listOf(first, fresh), requireNotNull(old.householdManualSourceId()), settings, installed
        ))
        assertNull(selectRememberedHouseholdSource(listOf(first), chosenId, settings, installed))
    }

    @Test fun onlyFreshPlayableOffersFromTheEligibleAddonCanMatch() {
        val fresh = offer(chosenId, "https://example.test/renewed")
        assertNull(selectRememberedHouseholdSource(listOf(fresh.copy(addonName = "Other")), chosenId, settings, installed))
        assertNull(selectRememberedHouseholdSource(listOf(fresh.copy(url = null)), chosenId, settings, installed))
        assertNull(selectRememberedHouseholdSource(listOf(fresh), chosenId, settings, emptySet()))
        assertNull(selectRememberedHouseholdSource(listOf(fresh), chosenId,
            settings.copy(streamAutoPlaySelectedAddons = setOf("Other")), installed))
        assertNull(selectRememberedHouseholdSource(listOf(fresh), chosenId,
            settings.copy(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "2160p"), installed))
    }

    @Test fun missingMalformedOrBearerHintsCannotBecomeRememberedChoices() {
        for (invalid in listOf(null, "", "https://example.test/play/token", "f".repeat(63))) {
            assertNull(offer(invalid, "https://example.test/stream").householdManualSourceId())
        }
    }
}
