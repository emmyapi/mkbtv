package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.HOUSEHOLD_ADDON
import com.nuvio.tv.domain.model.Stream

private val sourceIdPattern = Regex("[0-9a-f]{64}")

internal fun Stream.householdManualSourceId(): String? =
    if (addonName == HOUSEHOLD_ADDON && !isExternal() && !getStreamUrl().isNullOrBlank()) {
        behaviorHints?.nuvioSourceId?.takeIf(sourceIdPattern::matches)
    } else null

internal fun selectRememberedHouseholdSource(
    streams: List<Stream>,
    sourceId: String,
    settings: PlayerSettings,
    installedAddonNames: Set<String>
): Stream? {
    if (HOUSEHOLD_ADDON !in installedAddonNames) return null
    val matches = streams.filter { it.householdManualSourceId() == sourceId }
    // An explicit choice may be reused in manual mode, with the configured addon and regex scope.
    val mode = if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) StreamAutoPlayMode.FIRST_STREAM
        else settings.streamAutoPlayMode
    return StreamAutoPlaySelector.selectAutoPlayStream(
        streams = matches,
        mode = mode,
        regexPattern = settings.streamAutoPlayRegex,
        source = settings.streamAutoPlaySource,
        installedAddonNames = installedAddonNames,
        selectedAddons = settings.streamAutoPlaySelectedAddons,
        selectedPlugins = settings.streamAutoPlaySelectedPlugins
    )
}
