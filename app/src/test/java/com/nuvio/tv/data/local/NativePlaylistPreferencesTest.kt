package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.domain.model.MovieDistributionDates
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaylistPreferencesTest {
    private val active = MutableStateFlow(1)
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns active }
    private val backingStores = listOf(MemoryPreferences(), MemoryPreferences())
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(1, "library_preferences") } returns backingStores[0]
        every { get(2, "library_preferences") } returns backingStores[1]
    }
    private val legacy = SavedLibraryItem(
        id = "tt0001", type = "movie", name = "Film", poster = null, posterShape = PosterShape.POSTER,
        background = null, description = null, releaseInfo = null, imdbRating = null,
        genres = emptyList(), addonBaseUrl = null, addedAt = 17
    )

    @Test
    fun legacyRowsAndCustomMembershipsSurviveReopeningRenamingAndProfileSwitches() = runTest {
        backingStores.first().edit { it[stringSetPreferencesKey("library_items")] = setOf(Gson().toJson(legacy)) }

        val store = LibraryPreferences(factory, profiles)
        assertEquals(mapOf(LOCAL_LIBRARY_LIST_KEY to 17L), store.libraryItems.first().single().memberships)
        val playlist = store.createPlaylist("  Family  ", 1)
        assertEquals("Family", playlist.name)
        val knownDates = MovieDistributionDates(cinemaDate = "2025-03-01", homeDate = null)
        store.editMemberships(legacy.copy(movieDistributionDates = knownDates), 1) { mapOf(playlist.key to true) }
        val customOnly = legacy.copy(id = "tt0002")
        store.editMemberships(customOnly, 1) { mapOf(playlist.key to true) }
        store.renamePlaylist(playlist.key, "Weekend", 1)

        val reopened = LibraryPreferences(factory, profiles)
        assertEquals("Weekend", reopened.playlists.first().single().name)
        assertEquals(2, reopened.libraryItems.first().size)
        assertEquals(knownDates, reopened.getAllItems().single { it.id == legacy.id }.movieDistributionDates)
        assertTrue(reopened.membershipSnapshot(legacy.id, legacy.type, 1).values.all { it })
        active.value = 2
        assertTrue(reopened.playlists.first().isEmpty())
        assertTrue(reopened.libraryItems.first().isEmpty())

        active.value = 1
        reopened.deletePlaylist(playlist.key, 1)
        assertTrue(reopened.playlists.first().isEmpty())
        assertEquals(legacy.id, reopened.libraryItems.first().single().id)
        assertEquals(mapOf(LOCAL_LIBRARY_LIST_KEY to 17L), reopened.libraryItems.first().single().memberships)
        assertFalse(runCatching { reopened.deletePlaylist(LOCAL_LIBRARY_LIST_KEY, 1) }.isSuccess)
    }

    @Test
    fun localReorderRequiresTheCurrentPermutationAndOnlyWritesListOrder() = runTest {
        val store = LibraryPreferences(factory, profiles)
        val family = store.createPlaylist("Family", 1)
        val movies = store.createPlaylist("Films", 1)
        val other = store.createPlaylist("Other profile", 2)
        store.editMemberships(legacy, 1) { mapOf(family.key to true, movies.key to true) }
        val saved = store.getSyncState(1)

        val reordered = listOf(movies, family)
        store.reorderPlaylists(reordered.map { it.key }, 1)
        val invalid = listOf(
            listOf(family.key), listOf(family.key, family.key),
            listOf(family.key, other.key), listOf(family.key, LOCAL_LIBRARY_LIST_KEY)
        )
        for (keys in invalid) {
            assertTrue(runCatching { store.reorderPlaylists(keys, 1) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(reordered, LibraryPreferences(factory, profiles).playlists.first())
        assertEquals(saved, store.getSyncState(1))
        active.value = 2
        assertEquals(listOf(other), store.playlists.first())
    }

    @Test
    fun bothPublicIdsAddressTheSameMembershipAndTheOriginalCloudDeleteKey() = runTest {
        val store = LibraryPreferences(factory, profiles)
        val family = store.createPlaylist("Family", 1)
        val tmdb = legacy.copy(id = "tmdb:42")
        val imdb = legacy.copy(id = "imdb:tt0001", tmdbId = 42)
        for ((saved, alternate) in listOf(tmdb to imdb, imdb to tmdb)) {
            store.addItem(saved, 1)
            val indicator = store.observeMembership(alternate.id, alternate.type, alternate.imdbId, alternate.tmdbId).first()
            val snapshot = store.membershipSnapshot(alternate.id, alternate.type, 1, alternate.imdbId, alternate.tmdbId)
            assertEquals(setOf(LOCAL_LIBRARY_LIST_KEY), indicator)
            assertEquals(indicator, snapshot.filterValues { it }.keys)
            store.editMemberships(alternate, 1) { mapOf(family.key to true) }
            val stored = store.getAllItems().single()
            assertEquals(saved.id, stored.id)
            assertEquals(17L, stored.addedAt)
            assertEquals(store.observeMembership("tt0001", "movie").first(), stored.memberships.keys)
            assertTrue(store.observeMembership("tt0001", "series").first().isEmpty())

            store.editMemberships(alternate, 1) { mapOf(family.key to false, LOCAL_LIBRARY_LIST_KEY to false) }
            val state = store.getSyncState(1)
            assertTrue(state.items.isEmpty())
            assertEquals(listOf(saved.id), state.pendingDeleteKeys.map { it.contentId })
            assertTrue(store.acknowledgePush(1, state.mutationRevision))
        }
    }

    @Test
    fun serverCopiesAndAmbiguousPublicAliasesNeverEditAnotherSourceRow() = runTest {
        val store = LibraryPreferences(factory, profiles)
        val server1 = legacy.copy(id = "srv1:home:1", imdbId = "tt0001")
        val server2 = legacy.copy(id = "srv1:work:1", imdbId = "tt0001")
        store.addItem(server1, 1)
        assertTrue(store.observeMembership(server2.id, "movie", server2.imdbId).first().isEmpty())
        assertTrue(store.observeMembership("tt0001", "movie").first().isEmpty())
        store.addItem(server2, 1)
        store.addItem(legacy, 1)
        assertEquals(setOf(legacy.id, server1.id, server2.id), store.getAllItems().map { it.id }.toSet())

        // Existing versions could already have multiple source rows with the same public alias.
        val twins = listOf(legacy.copy(id = "tmdb:11", imdbId = "tt2000"), legacy.copy(id = "tmdb:12", imdbId = "tt2000"))
        backingStores[0].edit { it[stringSetPreferencesKey("library_items")] = twins.map(Gson()::toJson).toSet() }
        val ambiguous = legacy.copy(id = "tt2000")
        assertTrue(store.observeMembership(ambiguous.id, "movie").first().isEmpty())
        assertTrue(runCatching {
            store.editMemberships(ambiguous, 1) { mapOf(LOCAL_LIBRARY_LIST_KEY to false) }
        }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(twins.map { it.id }.toSet(), store.getAllItems().map { it.id }.toSet())

        store.editMemberships(twins[0], 1) { mapOf(LOCAL_LIBRARY_LIST_KEY to false) }
        assertEquals(twins[1].id, store.getAllItems().single().id)
        assertEquals(setOf(LOCAL_LIBRARY_LIST_KEY), store.observeMembership(ambiguous.id, "movie").first())
        assertTrue(store.observeMembership(ambiguous.id, "movie", tmdbId = 99).first().isEmpty())
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
