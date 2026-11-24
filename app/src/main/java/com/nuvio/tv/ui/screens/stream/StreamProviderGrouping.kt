package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.HOUSEHOLD_ADDON
import com.nuvio.tv.domain.model.Stream

internal fun groupStreamsByProvider(streams: List<Stream>): List<Stream> =
    streams.groupBy { it.addonName }.values.flatMap { addonStreams ->
        addonStreams.groupBy { it.pluginSourceName?.takeIf(String::isNotBlank) ?: it.addonName }
            .entries.sortedBy { it.key.lowercase() }
            .flatMap { it.value }
    }

internal fun streamProviderHeader(stream: Stream, previous: Stream? = null): String? =
    stream.pluginSourceName?.takeIf {
        it.isNotBlank() && it != stream.addonName &&
            (previous?.addonName != stream.addonName || previous.pluginSourceName != it)
    }

private val householdSources = setOf("Torrentio", "Comet", "MediaFusion")

internal fun Stream.pickerSource(): String {
    if (addonName != HOUSEHOLD_ADDON) return addonName
    return name?.substringBefore(" · ", "")?.takeIf { it in householdSources } ?: addonName
}

internal fun AddonStreams.pickerSources(): List<String> =
    if (addonName == HOUSEHOLD_ADDON && streams.isNotEmpty()) streams.map { it.pickerSource() }.distinct()
    else listOf(addonName)
