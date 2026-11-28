package com.nuvio.tv.domain.model

import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.data.mediaserver.ServerItemRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncReducerTest {
    @Test
    fun deletingFinalItemCreatesExplicitDeleteMutation() {
        val state = LibrarySyncState(
            items = listOf(libraryItem("item")),
            deltaInitialized = true
        )

        val result = LibrarySyncReducer.deleteLocal(
            state = state,
            contentId = "item",
            contentType = "movie"
        )

        assertTrue(result.items.isEmpty())
        assertTrue(result.pendingUpsertKeys.isEmpty())
        assertEquals(listOf("item"), result.pendingDeleteKeys.map { it.contentId })
    }

    @Test
    fun snapshotOverlaysPendingMutationsOnRemoteState() {
        val localChanged = libraryItem("changed", addedAt = 30L)
        val state = LibrarySyncState(
            items = listOf(localChanged),
            pendingUpsertKeys = listOf(localChanged.toLibrarySyncKey()),
            pendingDeleteKeys = listOf(
                LibrarySyncKey(contentId = "deleted", contentType = "movie")
            ),
            deltaInitialized = false,
            mutationRevision = 2L
        )

        val result = LibrarySyncReducer.applySnapshot(
            state = state,
            remoteItems = listOf(
                libraryItem("changed", addedAt = 10L),
                libraryItem("kept", addedAt = 20L),
                libraryItem("deleted", addedAt = 15L)
            ),
            cursorEventId = 8L
        )

        assertEquals(
            setOf("changed", "kept"),
            result.state.items.mapTo(mutableSetOf(), SavedLibraryItem::id)
        )
        assertEquals(30L, result.state.items.first { it.id == "changed" }.addedAt)
        assertEquals(8L, result.state.deltaCursorEventId)
        assertTrue(result.preservedLocalItems)
    }

    @Test
    fun emptyLegacySnapshotQueuesEveryDesiredLocalItem() {
        val legacy = libraryItem("legacy")
        val newlyAdded = libraryItem("new")
        val state = LibrarySyncState(
            items = listOf(legacy, newlyAdded),
            pendingUpsertKeys = listOf(newlyAdded.toLibrarySyncKey()),
            deltaInitialized = false,
            mutationRevision = 1L
        )

        val result = LibrarySyncReducer.applySnapshot(
            state = state,
            remoteItems = emptyList(),
            cursorEventId = 12L
        )

        assertEquals(
            setOf("legacy", "new"),
            result.state.items.mapTo(mutableSetOf(), SavedLibraryItem::id)
        )
        assertEquals(
            setOf("legacy", "new"),
            result.state.pendingUpsertKeys.mapTo(mutableSetOf()) { it.contentId }
        )
        assertEquals(2L, result.state.mutationRevision)
        assertTrue(result.preservedLocalItems)
    }

    @Test
    fun deltaAppliesInEventOrderAndPreservesLocalLogo() {
        val existing = libraryItem("existing", logo = "local-logo")
        val remoteExisting = libraryItem("existing", addedAt = 2L)
        val added = libraryItem("added", addedAt = 3L)
        val state = LibrarySyncState(
            items = listOf(existing),
            deltaCursorEventId = 5L,
            deltaInitialized = true
        )

        val result = LibrarySyncReducer.applyDelta(
            state = state,
            events = listOf(
                LibraryDeltaEvent(7L, "upsert", added),
                LibraryDeltaEvent(6L, "upsert", remoteExisting)
            )
        )

        assertEquals(setOf("existing", "added"), result.state.items.map { it.id }.toSet())
        assertEquals("local-logo", result.state.items.first { it.id == "existing" }.logo)
        assertEquals(7L, result.state.deltaCursorEventId)
        assertEquals(2, result.appliedUpserts)
    }

    @Test
    fun deltaPreservesPendingLocalMutationAndAdvancesCursor() {
        val local = libraryItem("local", addedAt = 20L)
        val state = LibrarySyncState(
            items = listOf(local),
            deltaCursorEventId = 10L,
            deltaInitialized = true,
            pendingUpsertKeys = listOf(local.toLibrarySyncKey()),
            mutationRevision = 1L
        )

        val result = LibrarySyncReducer.applyDelta(
            state = state,
            events = listOf(
                LibraryDeltaEvent(11L, "delete", local)
            )
        )

        assertEquals(listOf("local"), result.state.items.map { it.id })
        assertEquals(11L, result.state.deltaCursorEventId)
        assertEquals(0, result.appliedDeletes)
    }

    @Test
    fun stalePushAcknowledgementCannotClearNewerMutation() {
        val first = LibrarySyncReducer.upsertLocal(
            state = LibrarySyncState(deltaInitialized = true),
            item = libraryItem("first"),
            nowEpochMs = 1L
        )
        val second = LibrarySyncReducer.upsertLocal(
            state = first,
            item = libraryItem("second"),
            nowEpochMs = 2L
        )

        val acknowledged = LibrarySyncReducer.acknowledgePush(
            state = second,
            expectedMutationRevision = first.mutationRevision
        )

        assertNull(acknowledged)
        assertEquals(2, second.pendingUpsertKeys.size)
    }

    @Test
    fun sanitizingPersistedDeleteKeepsItemRemoved() {
        val state = LibrarySyncReducer.sanitize(
            LibrarySyncState(
                items = listOf(libraryItem("item")),
                pendingDeleteKeys = listOf(
                    LibrarySyncKey(contentId = "item", contentType = "movie")
                )
            )
        )

        assertTrue(state.items.isEmpty())
        assertFalse(state.pendingDeleteKeys.isEmpty())
    }

    @Test
    fun snapshotKeepsLocalServerTitles() {
        val serverItem = libraryItem(ServerItemRef("c1", "42").encode())
        val state = LibrarySyncState(
            items = listOf(serverItem, libraryItem("gone")),
            deltaInitialized = true
        )

        val result = LibrarySyncReducer.applySnapshot(
            state = state,
            remoteItems = listOf(libraryItem("remote")),
            cursorEventId = 3L
        )

        assertEquals(
            setOf("remote", serverItem.id),
            result.state.items.mapTo(mutableSetOf(), SavedLibraryItem::id)
        )
        assertFalse(result.preservedLocalItems)
    }

    @Test
    fun multipleMembershipsRetainOriginalSaveAndIndependentDates() {
        val item = libraryItem("movie", addedAt = 0L)
        val first = LibrarySyncReducer.changeMemberships(LibrarySyncState(), item, mapOf("sci-fi" to true), 10)
        val both = LibrarySyncReducer.changeMemberships(first, item, mapOf(LOCAL_LIBRARY_LIST_KEY to true), 20)
        val moved = LibrarySyncReducer.changeMemberships(both, item, mapOf("sci-fi" to false, "family" to true), 30)
        val customOnly = LibrarySyncReducer.changeMemberships(moved, item, mapOf(LOCAL_LIBRARY_LIST_KEY to false), 40)
        val empty = LibrarySyncReducer.changeMemberships(customOnly, item, mapOf("family" to false), 50)

        assertEquals(10L, customOnly.items.single().addedAt)
        assertEquals(mapOf("sci-fi" to 10L, LOCAL_LIBRARY_LIST_KEY to 20L), both.items.single().memberships)
        assertEquals(mapOf(LOCAL_LIBRARY_LIST_KEY to 20L, "family" to 30L), moved.items.single().memberships)
        assertEquals(mapOf("family" to 30L), customOnly.items.single().memberships)
        assertTrue(first.pendingUpsertKeys.isEmpty())
        assertEquals(listOf(item.toLibrarySyncKey()), both.pendingUpsertKeys)
        assertEquals(listOf(item.toLibrarySyncKey()), customOnly.pendingDeleteKeys)
        assertTrue(empty.items.isEmpty())
    }

    @Test
    fun flatCloudUpdatesTouchOnlyWatchLaterAndPreserveCustomLists() {
        val local = libraryItem("saved", addedAt = 10).copy(
            playlistAddedAt = mapOf("family" to 15L),
            imdbId = "tt123",
            tmdbId = 12,
            movieDistributionDates = MovieDistributionDates("2025-03-04", null)
        )
        val state = LibrarySyncState(items = listOf(local), deltaInitialized = true)
        val remote = libraryItem("saved", addedAt = 20).copy(name = "New title")
        val snapshot = LibrarySyncReducer.applySnapshot(state, listOf(remote), 3).state
        val refreshed = LibrarySyncReducer.applyDelta(snapshot, listOf(LibraryDeltaEvent(4, "upsert", remote))).state
        val deleted = LibrarySyncReducer.applyDelta(refreshed, listOf(LibraryDeltaEvent(5, "delete", remote))).state
        val missing = LibrarySyncReducer.applySnapshot(refreshed, emptyList(), 6).state

        assertEquals("New title", refreshed.items.single().name)
        assertEquals(local.imdbId, refreshed.items.single().imdbId)
        assertEquals(local.tmdbId, refreshed.items.single().tmdbId)
        assertEquals(local.movieDistributionDates, refreshed.items.single().movieDistributionDates)
        assertEquals(10L, refreshed.items.single().addedAt)
        assertEquals(mapOf("family" to 15L, LOCAL_LIBRARY_LIST_KEY to 20L), refreshed.items.single().memberships)
        assertEquals(mapOf("family" to 15L), deleted.items.single().memberships)
        assertEquals(deleted.items, missing.items)
        assertFalse(LibrarySyncReducer.queueAllItemsForPush(missing).hasPendingMutations)
    }

    @Test
    fun staleRemoteSnapshotCannotRestoreWatchLaterWhileCustomMembershipIsRetained() {
        val remote = libraryItem("saved", addedAt = 20)
        val local = remote.copy(playlistAddedAt = mapOf("family" to 22L))
        val state = LibrarySyncState(items = listOf(local), pendingDeleteKeys = listOf(local.toLibrarySyncKey()))
        val result = LibrarySyncReducer.applySnapshot(state, listOf(remote), 1).state
        assertEquals(listOf(local), result.items)
        assertEquals(state.pendingDeleteKeys, result.pendingDeleteKeys)
        assertTrue(result.pendingUpsertKeys.isEmpty())
    }

    private fun libraryItem(
        id: String,
        addedAt: Long = 1L,
        logo: String? = null
    ): SavedLibraryItem {
        return SavedLibraryItem(
            id = id,
            type = "movie",
            name = id,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            logo = logo,
            addedAt = addedAt
        )
    }
}
