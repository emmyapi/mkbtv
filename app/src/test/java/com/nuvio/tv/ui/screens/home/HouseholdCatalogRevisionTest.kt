package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.Addon
import org.junit.Assert.assertEquals
import org.junit.Test

class HouseholdCatalogRevisionTest {
    @Test fun oldSavedPositionExpandsToThreeRowsAndLaterCustomOrderIsRespected() {
        val first = "dk.mkb.nuvio.discovery.p1_mixed_because-watched"
        val second = "$first-2"
        val third = "$first-3"
        val manifest = listOf(first, second, third, "genre")
        assertEquals(manifest, insertNewBecauseWatchedSlots(listOf(first, "genre"), manifest))
        val manual = listOf(third, "genre", first, second)
        assertEquals(manual, insertNewBecauseWatchedSlots(manual, manifest))
        assertEquals(listOf(first, third, "genre"), insertNewBecauseWatchedSlots(listOf(first, "genre"), manifest - second))
    }

    @Test fun contentRevisionRefreshesJustChangedSourceAndStructuralChangesUseFullReload() {
        fun addon(id: String) = Addon(id = id, name = id, version = "1", description = null,
            logo = null, baseUrl = "https://$id.test", catalogs = emptyList(), resources = emptyList(), types = emptyList())
        val other = addon("other")
        val old = addon("private")
        val revised = old.copy(configVersion = 1)
        val previous = listOf(other, old)
        assertEquals(setOf(revised.baseUrl), changedCatalogRevisions(previous, listOf(other, revised)))
        assertEquals(emptySet<String>(), changedCatalogRevisions(previous, listOf(old, other)))
        assertEquals(emptySet<String>(), changedCatalogRevisions(previous, listOf(revised)))
        assertEquals(emptySet<String>(), changedCatalogRevisions(previous, listOf(other.copy(version = "2"), revised)))
    }
}
