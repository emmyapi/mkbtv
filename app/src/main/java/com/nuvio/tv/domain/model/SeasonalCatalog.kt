package com.nuvio.tv.domain.model

private val discoveryId = Regex("dk\\.mkb\\.nuvio\\.discovery\\.p[0-9]+")
private val seasonalKey = Regex("dk\\.mkb\\.nuvio\\.discovery\\.p[0-9]+_mixed_seasonal")

internal fun isSeasonalCatalog(addonId: String?, catalogId: String?): Boolean =
    catalogId == "seasonal" && addonId != null && discoveryId.matches(addonId)

internal fun seasonalHomeOrder(keys: List<String>, saved: List<String> = emptyList()): List<String> {
    val seasonal = keys.firstOrNull(seasonalKey::matches) ?: return keys
    return if (seasonal in saved || keys.first() == seasonal) keys else listOf(seasonal) + (keys - seasonal)
}

// Keep the preference addressable even in calendar gaps and for an exhausted profile.
internal fun Addon.catalogsForHomeSettings(): List<CatalogDescriptor> {
    if (seasonalValidUntil == null || !discoveryId.matches(id) || catalogs.any { it.id == "seasonal" }) return catalogs
    return catalogs + CatalogDescriptor(ContentType.UNKNOWN, rawType = "mixed", id = "seasonal",
        name = "Seasonal recommendations")
}
