package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.SeasonalHistory
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.isSeasonalCatalog
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.ui.util.asStable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun CatalogRow.validSeasonalWindow(addon: Addon?, profileId: Int, now: Long): Boolean =
    addon != null && addonId == "dk.mkb.nuvio.discovery.p$profileId" && addon.id == addonId &&
        addon.baseUrl == addonBaseUrl && !seasonalOccurrence.isNullOrBlank() &&
        seasonalOccurrence == addon.seasonalOccurrence && seasonalValidUntil == addon.seasonalValidUntil &&
        (seasonalValidUntil ?: 0L) > now

internal fun CatalogRow.excludeSeasonalHistory(history: SeasonalHistory?): CatalogRow? {
    if (history == null || addonId != "dk.mkb.nuvio.discovery.p${history.profileId}" ||
        addonBaseUrl != history.baseUrl) return null
    val eligible = items.filter { item ->
        val aliases = listOfNotNull(item.id, item.imdbId, item.seasonalTmdbId)
        when (item.apiType) {
            "movie" -> aliases.none { (history.watched[it] ?: 0L) > (item.seasonalRewatchAt ?: 0L) }
            "series" -> aliases.none { it in history.startedSeries }
            else -> false
        }
    }
    return if (eligible.size == items.size) this else copy(items = eligible)
}

private fun HomeViewModel.addonFor(row: CatalogRow) =
    addonsCache.firstOrNull { it.id == row.addonId && it.baseUrl == row.addonBaseUrl }

internal fun seasonalSnapshotForDisplay(
    latest: CatalogRow, addon: Addon?, profile: Int, now: Long, history: SeasonalHistory?,
    focused: CatalogRow?
): CatalogRow? {
    if (!latest.validSeasonalWindow(addon, profile, now)) return null
    // A focused row holds its cards until blur or Home re-entry. Profile and calendar
    // changes always expire the previous display, including an interrupted occurrence.
    if (focused != null && focused.validSeasonalWindow(addon, profile, now)) return focused
    if (addon == null || addon.catalogs.none { isSeasonalCatalog(addon.id, it.id) }) return null
    return latest.excludeSeasonalHistory(history)
}

internal fun HomeViewModel.visibleSeasonalCatalog(row: CatalogRow): CatalogRow? {
    val focused = if (!seasonalFocusReleased && row.stableKey() == liveFocusedRowKey) {
        _uiState.value.catalogRows.firstOrNull { it.stableKey() == liveFocusedRowKey }
    } else null
    return seasonalSnapshotForDisplay(row, addonFor(row), profileManager.activeProfileId.value,
        System.currentTimeMillis(), householdRecommendations.seasonalHistory.value, focused)
}

internal fun HomeViewModel.receiveSeasonalCatalog(key: String, fresh: CatalogRow, requestedAddon: Addon) {
    val current = addonFor(fresh) ?: return
    if (fresh.addonId == "dk.mkb.nuvio.discovery.p${profileManager.activeProfileId.value}" &&
        (fresh.seasonalRevision ?: -1) > (current.configVersion ?: -1)) {
        householdRecommendations.onHomeEntry()
    }
    if (current.configVersion == requestedAddon.configVersion && fresh.seasonalRevision == current.configVersion &&
        fresh.validSeasonalWindow(current, profileManager.activeProfileId.value, System.currentTimeMillis())) {
        replaceCatalogRow(key, fresh)
    }
}

internal fun HomeViewModel.releaseSeasonalFocus(previous: String?, returningToHome: Boolean = false) {
    if (previous == null || _uiState.value.catalogRows.none {
            isSeasonalCatalog(it.addonId, it.catalogId) && it.stableKey() == previous
        }) return
    // Focus restoration can run before the debounced presentation pass. Let that pass
    // publish the latest cache and watched marks even if the same row regained focus.
    seasonalFocusReleased = true
    if (returningToHome) liveFocusedRowKey = null
    scheduleUpdateCatalogRows()
}

internal fun HomeViewModel.expireSeasonalCatalogs() {
    val rows = snapshotCatalogState().second.values.filter { isSeasonalCatalog(it.addonId, it.catalogId) }
    val now = System.currentTimeMillis()
    val active = profileManager.activeProfileId.value
    val invalid = rows.filter { !it.validSeasonalWindow(addonFor(it), active, now) }
    if (invalid.isNotEmpty()) {
        for (row in invalid) {
            removeCatalogRow(row.legacyKey())
        }
        val keys = invalid.mapTo(mutableSetOf()) { it.stableKey() }
        val origins = invalid.mapTo(mutableSetOf()) { it.addonBaseUrl }
        val previousItems = invalid.flatMap { it.items }.mapTo(mutableSetOf()) { it.id }
        // Resume and profile changes can precede the debounced Home projection. Remove
        // the expired cards from the already-rendered surfaces before returning to them.
        _fullCatalogRows.update { rows -> rows.filterNot { it.stableKey() in keys } }
        _uiState.update { state ->
            val hero = state.heroItems.filterNot { it.sourceAddonBaseUrl in origins && it.id in previousItems }
            state.copy(
                catalogRows = state.catalogRows.filterNot { it.stableKey() in keys },
                homeRows = state.homeRows.filterNot { it is HomeRow.Catalog && it.row.stableKey() in keys },
                heroItems = hero,
                gridItems = state.gridItems.mapNotNull { item ->
                    when (item) {
                        is GridItem.Hero -> if (hero.isEmpty()) null else item.copy(items = hero)
                        is GridItem.SectionDivider -> item.takeUnless { it.addonBaseUrl in origins && isSeasonalCatalog(it.addonId, it.catalogId) }
                        is GridItem.Content -> item.takeUnless { it.addonBaseUrl in origins && isSeasonalCatalog(it.addonId, it.catalogId) }
                        is GridItem.SeeAll -> item.takeUnless { it.addonBaseUrl in origins && isSeasonalCatalog(it.addonId, it.catalogId) }
                        else -> item
                    }
                }
            )
        }
        _modernHomePresentation.update { state ->
            val rows = state.rows.list.filterNot { it.key in keys }
            if (rows.size == state.rows.list.size) state else ModernHomePresentationState(rows.asStable(), buildCarouselRowLookups(rows))
        }
        seasonalFocusReleased = true
        scheduleUpdateCatalogRows()
    }
}

internal fun HomeViewModel.scheduleSeasonalExpiry() {
    seasonalExpiryJob?.cancel()
    val profile = profileManager.activeProfileId.value
    val boundary = addonsCache.firstOrNull { it.id == "dk.mkb.nuvio.discovery.p$profile" }?.seasonalValidUntil ?: return
    val remaining = boundary - System.currentTimeMillis()
    if (remaining <= 0L) return
    seasonalExpiryJob = viewModelScope.launch {
        delay(remaining)
        expireSeasonalCatalogs()
        householdRecommendations.onHomeEntry()
    }
}

internal fun HomeViewModel.observeSeasonalHome() {
    viewModelScope.launch {
        householdRecommendations.seasonalHistory.collect {
            if (_uiState.value.catalogRows.any { isSeasonalCatalog(it.addonId, it.catalogId) } ||
                addonsCache.any { addon -> addon.seasonalValidUntil != null }) scheduleUpdateCatalogRows()
        }
    }
    viewModelScope.launch {
        householdRecommendations.successfulChecks.collect { checks ->
            if (checks == 0L) return@collect
            val profile = profileManager.activeProfileId.value
            val addon = addonsCache.firstOrNull { it.id == "dk.mkb.nuvio.discovery.p$profile" } ?: return@collect
            val catalog = addon.catalogs.firstOrNull { isSeasonalCatalog(addon.id, it.id) } ?: return@collect
            if (isCatalogDisabled(addon.baseUrl, addon.id, catalog.apiType, catalog.id, catalog.name)) return@collect
            val key = catalogKey(addon.id, catalog.apiType, catalog.id)
            val existing = readCatalogRow(key)
            if (!catalogsLoadInProgress && (existing?.seasonalRevision != addon.configVersion || existing?.items.isNullOrEmpty())) {
                pendingCatalogLoads++
                loadCatalogPipeline(addon, catalog, catalogLoadGeneration, isRefresh = true)
            }
        }
    }
}
