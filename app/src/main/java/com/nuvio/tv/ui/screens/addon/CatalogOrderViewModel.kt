package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.sync.homeLegacyDisabledCatalogKey
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.catalogsForHomeSettings
import com.nuvio.tv.domain.model.seasonalHomeOrder
import com.nuvio.tv.domain.model.isSeasonalHomeKey
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CatalogOrderViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val collectionsDataStore: CollectionsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    private val serverCatalog: ServerCatalog
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogOrderUiState())
    val uiState: StateFlow<CatalogOrderUiState> = _uiState.asStateFlow()
    private var disabledKeysCache: Set<String> = emptySet()

    init {
        observeCatalogs()
    }

    fun moveUp(key: String) {
        if (_uiState.value.followAddonsOrder) {
            moveCollectionBetweenAddons(key, -1)
        } else {
            moveCatalog(key, -1)
        }
    }

    fun moveDown(key: String) {
        if (_uiState.value.followAddonsOrder) {
            moveCollectionBetweenAddons(key, 1)
        } else {
            moveCatalog(key, 1)
        }
    }

    fun toggleFollowAddonsOrder(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setFollowAddonsOrder(enabled)
        }
    }

    fun toggleCatalogEnabled(disableKey: String) {
        val updatedDisabled = disabledKeysCache.toMutableSet().apply {
            if (disableKey in this) remove(disableKey) else add(disableKey)
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(updatedDisabled.toList())
            homeCatalogSettingsSyncService.triggerPush()
        }
    }

    private fun moveCatalog(key: String, direction: Int) {
        val currentKeys = _uiState.value.items.map { it.key }
        val currentIndex = currentKeys.indexOf(key)
        if (currentIndex == -1) return

        val newIndex = currentIndex + direction
        if (newIndex !in currentKeys.indices) return

        val reordered = currentKeys.toMutableList().apply {
            val item = removeAt(currentIndex)
            add(newIndex, item)
        }

        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
            homeCatalogSettingsSyncService.triggerPush()
        }
    }

    /**
     * When followAddonsOrder is enabled, collections jump between addon boundaries.
     * Moving up means jumping above the previous addon block (all catalogs from one addon).
     * Moving down means jumping below the next addon block.
     */
    private fun moveCollectionBetweenAddons(key: String, direction: Int) {
        if (!key.startsWith("collection_")) return

        val items = _uiState.value.items
        val currentIndex = items.indexOfFirst { it.key == key }
        if (currentIndex == -1) return

        val currentKeys = items.map { it.key }

        val newIndex = collectionMoveTarget(currentKeys, currentIndex, direction) { items[it].addonName } ?: return
        val reordered = currentKeys.toMutableList().apply {
            removeAt(currentIndex)
            add(newIndex, key)
        }

        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
            homeCatalogSettingsSyncService.triggerPush()
        }
    }

    private fun observeCatalogs() {
        viewModelScope.launch {
            combine(
                combine(addonRepository.getInstalledAddons(), serverCatalog.addons) { installed, servers ->
                    installed.enabledAddons() + servers
                },
                collectionsDataStore.collections,
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.customCatalogTitles,
                layoutPreferenceDataStore.followAddonsOrder
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val addons = values[0] as List<Addon>
                @Suppress("UNCHECKED_CAST")
                val collections = values[1] as List<Collection>
                @Suppress("UNCHECKED_CAST")
                val savedOrderKeys = values[2] as List<String>
                @Suppress("UNCHECKED_CAST")
                val disabledKeys = (values[3] as List<String>).toSet()
                @Suppress("UNCHECKED_CAST")
                val customTitles = values[4] as Map<String, String>
                val followAddons = values[5] as Boolean

                val items = buildOrderedCatalogItems(
                    addons = addons,
                    collections = collections,
                    savedOrderKeys = savedOrderKeys,
                    disabledKeys = disabledKeys,
                    customTitles = customTitles,
                    followAddonsOrder = followAddons
                )
                Pair(items, followAddons)
            }.collectLatest { (orderedItems, followAddons) ->
                disabledKeysCache = orderedItems.filter { it.isDisabled }.map { it.disableKey }.toSet()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = orderedItems,
                        followAddonsOrder = followAddons
                    )
                }
            }
        }
    }

    private fun buildOrderedCatalogItems(
        addons: List<Addon>,
        collections: List<Collection> = emptyList(),
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>,
        customTitles: Map<String, String> = emptyMap(),
        followAddonsOrder: Boolean = false
    ): List<CatalogOrderItem> {
        val defaultEntries = buildDefaultCatalogEntries(addons)
        val collectionEntries = collections.map { collection ->
            CatalogOrderEntry(
                key = "collection_${collection.id}",
                disableKey = "collection_${collection.id}",
                catalogName = collection.title,
                addonName = "${collection.folders.size} folder${if (collection.folders.size != 1) "s" else ""}",
                typeLabel = "collection"
            )
        }
        val allEntries = defaultEntries + collectionEntries
        val availableMap = allEntries.associateBy { it.key }
        val defaultOrderKeys = allEntries.map { it.key }

        val effectiveOrder: List<String>
        if (followAddonsOrder) {
            // In follow mode, addon catalogs stay in manifest order.
            // Collections are positioned based on their relative position in savedOrderKeys.
            val addonKeys = seasonalHomeOrder(defaultEntries.map { it.key })
            val collectionKeys = collectionEntries.map { it.key }.toSet()

            val savedValid = savedOrderKeys.filter { it in availableMap && !isSeasonalHomeKey(it) }.distinct()

            if (savedValid.isNotEmpty()) {
                // Rebuild order: take saved order but replace addon sequence with manifest order.
                // Strategy: walk through savedValid, output addon keys in manifest order,
                // insert collections at their saved positions relative to addon boundaries.
                val result = mutableListOf<String>()
                var addonPointer = 0 // pointer into addonKeys (manifest order)

                for (savedKey in savedValid) {
                    if (savedKey in collectionKeys) {
                        // Place collection here - but first flush any addon keys up to this point
                        // that haven't been placed yet
                        result.add(savedKey)
                    } else {
                        // It's an addon catalog key in saved order - advance manifest pointer
                        // to include all addon keys up to and including this one
                        val targetManifestIdx = addonKeys.indexOf(savedKey)
                        if (targetManifestIdx >= 0) {
                            while (addonPointer <= targetManifestIdx) {
                                val ak = addonKeys[addonPointer]
                                if (ak !in result) {
                                    result.add(ak)
                                }
                                addonPointer++
                            }
                        }
                    }
                }
                // Append any remaining addon keys not yet placed
                while (addonPointer < addonKeys.size) {
                    val ak = addonKeys[addonPointer]
                    if (ak !in result) {
                        result.add(ak)
                    }
                    addonPointer++
                }
                // Append any collections not in savedValid
                for (ck in collectionKeys) {
                    if (ck !in result) {
                        result.add(ck)
                    }
                }
                // Normalize: ensure collections sit at addon block boundaries, not mid-block.
                // If a collection is between two catalogs of the same addon, push it after that block.
                effectiveOrder = normalizeCollectionPositions(result, availableMap)
            } else {
                // No saved order - addon manifest order + collections at end
                effectiveOrder = addonKeys + collectionKeys.toList()
            }
        } else {
            val savedValid = savedOrderKeys
                .asSequence()
                .filter { it in availableMap }
                .distinct()
                .toList()

            val savedKeySet = savedValid.toSet()
            val missing = defaultOrderKeys.filterNot { it in savedKeySet }
            effectiveOrder = savedValid + missing
        }

        val displayOrder = seasonalHomeOrder(effectiveOrder, if (followAddonsOrder) emptyList() else savedOrderKeys)
        return displayOrder.mapIndexedNotNull { index, key ->
            val entry = availableMap[key] ?: return@mapIndexedNotNull null
            val displayName = customTitles[key]?.takeIf { it.isNotBlank() } ?: entry.catalogName
            fun canMove(direction: Int): Boolean {
                if (!followAddonsOrder) return index + direction in displayOrder.indices
                return collectionMoveTarget(displayOrder, index, direction) { availableMap.getValue(displayOrder[it]).addonName } != null
            }

            CatalogOrderItem(
                key = entry.key,
                disableKey = entry.disableKey,
                catalogName = displayName,
                addonName = entry.addonName,
                typeLabel = entry.typeLabel,
                isDisabled = entry.disableKey in disabledKeys ||
                    (entry.legacyDisableKey != null && entry.legacyDisableKey in disabledKeys),
                canMoveUp = canMove(-1),
                canMoveDown = canMove(1)
            )
        }
    }

    /**
     * Ensures collections are positioned at addon block boundaries.
     * If a collection ended up between two catalogs of the same addon,
     * push it to the end of that addon's block.
     */
    private fun normalizeCollectionPositions(
        order: List<String>,
        availableMap: Map<String, CatalogOrderEntry>
    ): List<String> {
        val result = order.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            var i = 0
            while (i < result.size) {
                val key = result[i]
                if (!key.startsWith("collection_")) {
                    i++
                    continue
                }
                val prevAddon = findAddonNameBefore(result, i, availableMap)
                val nextAddon = findAddonNameAfter(result, i, availableMap)
                if (prevAddon != null && nextAddon != null && prevAddon == nextAddon) {
                    result.removeAt(i)
                    var insertPos = i
                    while (insertPos < result.size &&
                        !result[insertPos].startsWith("collection_") &&
                        availableMap[result[insertPos]]?.addonName == prevAddon
                    ) {
                        insertPos++
                    }
                    result.add(insertPos, key)
                    if (insertPos != i) changed = true
                    i++
                } else {
                    i++
                }
            }
        }
        return result
    }

    private fun findAddonNameBefore(
        order: List<String>,
        index: Int,
        availableMap: Map<String, CatalogOrderEntry>
    ): String? {
        for (j in index - 1 downTo 0) {
            if (!order[j].startsWith("collection_")) {
                return availableMap[order[j]]?.addonName
            }
        }
        return null
    }

    private fun findAddonNameAfter(
        order: List<String>,
        index: Int,
        availableMap: Map<String, CatalogOrderEntry>
    ): String? {
        for (j in index + 1 until order.size) {
            if (!order[j].startsWith("collection_")) {
                return availableMap[order[j]]?.addonName
            }
        }
        return null
    }

    private fun buildDefaultCatalogEntries(addons: List<Addon>): List<CatalogOrderEntry> {
        val entries = mutableListOf<CatalogOrderEntry>()
        val seenKeys = mutableSetOf<String>()

        addons.forEach { addon ->
            addon.catalogsForHomeSettings()
                .filterNot { it.isSearchOnlyCatalog() }
                .forEach { catalog ->
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    if (seenKeys.add(key)) {
                        entries.add(
                            CatalogOrderEntry(
                                key = key,
                                disableKey = homeCatalogKey(
                                    addonId = addon.id,
                                    type = catalog.apiType,
                                    catalogId = catalog.id
                                ),
                                legacyDisableKey = homeLegacyDisabledCatalogKey(
                                    addonBaseUrl = addon.baseUrl,
                                    type = catalog.apiType,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name
                                ),
                                catalogName = catalog.name,
                                addonName = addon.displayName,
                                typeLabel = catalog.apiType
                            )
                        )
                    }
                }
        }

        return entries
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return homeCatalogKey(addonId, type, catalogId)
    }
}

/** Destination across the neighboring addon block in Follow Addons Order. */
internal fun collectionMoveTarget(keys: List<String>, index: Int, direction: Int, addonNameAt: (Int) -> String): Int? {
    if (keys.getOrNull(index)?.startsWith("collection_") != true || (direction != -1 && direction != 1)) return null
    var target = index + direction
    while (keys.getOrNull(target)?.startsWith("collection_") == true) target += direction
    val neighbor = keys.getOrNull(target) ?: return null
    if (isSeasonalHomeKey(neighbor)) return null
    val owner = addonNameAt(target)
    while (true) {
        val next = keys.getOrNull(target + direction) ?: break
        if (next.startsWith("collection_") || isSeasonalHomeKey(next) || addonNameAt(target + direction) != owner) break
        target += direction
    }
    return target
}

data class CatalogOrderUiState(
    val isLoading: Boolean = true,
    val items: List<CatalogOrderItem> = emptyList(),
    val followAddonsOrder: Boolean = false
)

data class CatalogOrderItem(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String,
    val isDisabled: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)

private data class CatalogOrderEntry(
    val key: String,
    val disableKey: String,
    val legacyDisableKey: String? = null,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String
)
