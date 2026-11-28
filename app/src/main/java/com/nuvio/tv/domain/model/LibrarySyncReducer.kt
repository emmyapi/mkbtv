package com.nuvio.tv.domain.model

import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.data.mediaserver.ServerItemRef

object LibrarySyncReducer {
    fun sanitize(state: LibrarySyncState): LibrarySyncState {
        val items = state.items.toItemMap()
        val pendingUpserts = state.pendingUpsertKeys.toKeyMap()
        val pendingDeletes = state.pendingDeleteKeys.toKeyMap()

        pendingUpserts.keys.removeAll { identity ->
            LOCAL_LIBRARY_LIST_KEY !in items[identity]?.memberships.orEmpty()
        }
        pendingUpserts.keys.forEach(pendingDeletes::remove)
        pendingDeletes.keys.forEach { identity -> items.removeWatchLater(identity) }

        return state.copy(
            items = items.values.toList(),
            deltaCursorEventId = state.deltaCursorEventId.coerceAtLeast(0L),
            pendingUpsertKeys = pendingUpserts.values.toList(),
            pendingDeleteKeys = pendingDeletes.values.toList(),
            mutationRevision = state.mutationRevision.coerceAtLeast(0L)
        )
    }

    fun upsertLocal(
        state: LibrarySyncState,
        item: SavedLibraryItem,
        nowEpochMs: Long
    ): LibrarySyncState = changeMemberships(
        state, item, mapOf(LOCAL_LIBRARY_LIST_KEY to true), nowEpochMs
    )

    fun deleteLocal(
        state: LibrarySyncState,
        contentId: String,
        contentType: String
    ): LibrarySyncState {
        val current = sanitize(state)
        val item = NativeLibraryIdentity(contentId, contentType).find(current.items, forEdit = true) ?: return current
        return changeMemberships(current, item, mapOf(LOCAL_LIBRARY_LIST_KEY to false), 0L)
    }

    fun changeMemberships(
        state: LibrarySyncState,
        item: SavedLibraryItem,
        desired: Map<String, Boolean>,
        nowEpochMs: Long
    ): LibrarySyncState {
        val current = sanitize(state)
        if (item.id.isBlank() || item.type.isBlank()) return current
        val requested = item.nativeIdentity()
        val existing = requested.find(current.items, forEdit = true)
        val stored = existing ?: item
        val identity = librarySyncIdentity(stored.id, stored.type)
        val items = current.items.toItemMap()
        val before = existing?.memberships.orEmpty()
        val after = before.toMutableMap()
        val addedAt = existing?.addedAt ?: item.addedAt.takeIf { it > 0 } ?: nowEpochMs
        val membershipTime = if (existing == null) addedAt else nowEpochMs
        desired.forEach { (key, selected) ->
            if (selected) after.putIfAbsent(key, membershipTime) else after.remove(key)
        }
        if (after.isEmpty()) {
            if (existing == null) return current
            items.remove(identity)
        } else {
            val updated = stored.copy(
                addedAt = addedAt,
                playlistAddedAt = after,
                imdbId = existing?.imdbId ?: requested.imdb,
                tmdbId = existing?.tmdbId ?: requested.tmdb,
                movieDistributionDates = item.movieDistributionDates?.merge(existing?.movieDistributionDates)
                    ?: existing?.movieDistributionDates
            )
            if (updated == existing) return current
            items[identity] = updated
        }

        // The upstream API owns one flat Watch Later row. Custom-only edits stay in this profile.
        if ((LOCAL_LIBRARY_LIST_KEY in before) == (LOCAL_LIBRARY_LIST_KEY in after)) {
            return current.copy(items = items.values.toList())
        }
        val pendingUpserts = current.pendingUpsertKeys.toKeyMap()
        val pendingDeletes = current.pendingDeleteKeys.toKeyMap()
        if (LOCAL_LIBRARY_LIST_KEY in after) {
            pendingDeletes.remove(identity)
            pendingUpserts[identity] = stored.toLibrarySyncKey()
        } else {
            pendingUpserts.remove(identity)
            pendingDeletes[identity] = stored.toLibrarySyncKey()
        }
        return current.copy(
            items = items.values.toList(),
            pendingUpsertKeys = pendingUpserts.values.toList(),
            pendingDeleteKeys = pendingDeletes.values.toList(),
            mutationRevision = current.mutationRevision + 1L
        )
    }

    fun queueAllItemsForPush(state: LibrarySyncState): LibrarySyncState {
        val current = sanitize(state)
        val pendingUpserts = current.pendingUpsertKeys.toKeyMap()
        val pendingDeletes = current.pendingDeleteKeys.toKeyMap()
        val beforeUpserts = pendingUpserts.toMap()
        val beforeDeletes = pendingDeletes.toMap()

        current.items.filter { LOCAL_LIBRARY_LIST_KEY in it.memberships }.forEach { item ->
            val identity = librarySyncIdentity(item.id, item.type)
            pendingUpserts[identity] = item.toLibrarySyncKey()
            pendingDeletes.remove(identity)
        }

        if (pendingUpserts == beforeUpserts && pendingDeletes == beforeDeletes) {
            return current
        }

        return current.copy(
            pendingUpsertKeys = pendingUpserts.values.toList(),
            pendingDeleteKeys = pendingDeletes.values.toList(),
            mutationRevision = current.mutationRevision + 1L
        )
    }

    fun applySnapshot(
        state: LibrarySyncState,
        remoteItems: kotlin.collections.Collection<SavedLibraryItem>,
        cursorEventId: Long
    ): LibrarySnapshotApplyResult {
        val current = sanitize(state)
        val localItems = current.items.toItemMap()
        val remoteItemsByKey = linkedMapOf<String, SavedLibraryItem>()
        remoteItems.forEach { remoteItem ->
            if (remoteItem.id.isBlank() || remoteItem.type.isBlank()) return@forEach
            val identity = librarySyncIdentity(remoteItem.id, remoteItem.type)
            remoteItemsByKey[identity] = remoteItem.mergeRemoteWatchLater(localItems[identity])
        }
        val pendingUpserts = current.pendingUpsertKeys.toKeyMap()
        val pendingDeletes = current.pendingDeleteKeys.toKeyMap()
        val beforeUpserts = pendingUpserts.toMap()
        val migrateLegacyLocal =
            !current.deltaInitialized &&
                remoteItemsByKey.isEmpty() &&
                localItems.values.any { LOCAL_LIBRARY_LIST_KEY in it.memberships }

        val reconciledItems = if (migrateLegacyLocal) {
            localItems
        } else {
            pendingDeletes.keys.forEach(remoteItemsByKey::remove)
            pendingUpserts.keys.forEach { identity ->
                localItems[identity]?.let { localItem ->
                    remoteItemsByKey[identity] = localItem
                }
            }
            localItems.forEach { (identity, localItem) ->
                if (identity !in remoteItemsByKey) {
                    val retained = if (ServerItemRef.isServerId(localItem.id)) {
                        localItem
                    } else {
                        localItem.withoutPlaylist(LOCAL_LIBRARY_LIST_KEY)
                    }
                    if (retained != null) remoteItemsByKey[identity] = retained
                }
            }
            remoteItemsByKey
        }

        if (migrateLegacyLocal) {
            localItems.filterValues { LOCAL_LIBRARY_LIST_KEY in it.memberships }.forEach { (identity, item) ->
                pendingUpserts[identity] = item.toLibrarySyncKey()
            }
        }

        val pendingChanged = pendingUpserts != beforeUpserts
        return LibrarySnapshotApplyResult(
            state = current.copy(
                items = reconciledItems.values.toList(),
                deltaCursorEventId = cursorEventId.coerceAtLeast(0L),
                deltaInitialized = true,
                pendingUpsertKeys = pendingUpserts.values.toList(),
                pendingDeleteKeys = pendingDeletes.values.toList(),
                mutationRevision = current.mutationRevision + if (pendingChanged) 1L else 0L
            ),
            preservedLocalItems =
                migrateLegacyLocal ||
                    pendingUpserts.isNotEmpty() ||
                    pendingDeletes.isNotEmpty() ||
                    reconciledItems.values.any { item -> item.memberships.keys.any { it != LOCAL_LIBRARY_LIST_KEY } }
        )
    }

    fun applyDelta(
        state: LibrarySyncState,
        events: kotlin.collections.Collection<LibraryDeltaEvent>
    ): LibraryDeltaApplyResult {
        val current = sanitize(state)
        val items = current.items.toItemMap()
        val pendingUpserts = current.pendingUpsertKeys.toKeyMap()
        val pendingDeletes = current.pendingDeleteKeys.toKeyMap()
        var appliedUpserts = 0
        var appliedDeletes = 0

        events.sortedBy(LibraryDeltaEvent::eventId).forEach { event ->
            val identity = librarySyncIdentity(event.item.id, event.item.type)
            if (identity in pendingUpserts || identity in pendingDeletes) return@forEach

            when (event.operation.trim().lowercase()) {
                "upsert" -> {
                    items[identity] = event.item.mergeRemoteWatchLater(items[identity])
                    appliedUpserts += 1
                }
                "delete" -> {
                    items.removeWatchLater(identity)
                    appliedDeletes += 1
                }
            }
        }

        val cursor = maxOf(
            current.deltaCursorEventId,
            events.maxOfOrNull(LibraryDeltaEvent::eventId) ?: current.deltaCursorEventId
        )
        return LibraryDeltaApplyResult(
            state = current.copy(
                items = items.values.toList(),
                deltaCursorEventId = cursor,
                deltaInitialized = true
            ),
            appliedUpserts = appliedUpserts,
            appliedDeletes = appliedDeletes
        )
    }

    fun acknowledgePush(
        state: LibrarySyncState,
        expectedMutationRevision: Long
    ): LibrarySyncState? {
        val current = sanitize(state)
        if (current.mutationRevision != expectedMutationRevision) return null

        return current.copy(
            pendingUpsertKeys = emptyList(),
            pendingDeleteKeys = emptyList(),
            mutationRevision = current.mutationRevision + 1L
        )
    }

    fun pendingUpsertItems(state: LibrarySyncState): List<SavedLibraryItem> {
        val current = sanitize(state)
        val items = current.items.toItemMap()
        return current.pendingUpsertKeys.mapNotNull { key ->
            items[librarySyncIdentity(key.contentId, key.contentType)]
        }
    }
}

private fun kotlin.collections.Collection<SavedLibraryItem>.toItemMap(): LinkedHashMap<String, SavedLibraryItem> {
    val result = linkedMapOf<String, SavedLibraryItem>()
    forEach { item ->
        if (item.id.isNotBlank() && item.type.isNotBlank()) {
            result[librarySyncIdentity(item.id, item.type)] = item
        }
    }
    return result
}

private fun kotlin.collections.Collection<LibrarySyncKey>.toKeyMap(): LinkedHashMap<String, LibrarySyncKey> {
    val result = linkedMapOf<String, LibrarySyncKey>()
    forEach { key ->
        if (key.contentId.isNotBlank() && key.contentType.isNotBlank()) {
            result[librarySyncIdentity(key.contentId, key.contentType)] = key
        }
    }
    return result
}

private fun MutableMap<String, SavedLibraryItem>.removeWatchLater(identity: String) {
    val remaining = this[identity]?.withoutPlaylist(LOCAL_LIBRARY_LIST_KEY)
    if (remaining == null) remove(identity) else this[identity] = remaining
}

private fun SavedLibraryItem.mergeRemoteWatchLater(local: SavedLibraryItem?): SavedLibraryItem {
    if (local == null) return this
    val memberships = local.memberships.toMutableMap()
    if ((memberships[LOCAL_LIBRARY_LIST_KEY] ?: 0L) == 0L) memberships[LOCAL_LIBRARY_LIST_KEY] = addedAt
    val firstSaved = when {
        addedAt <= 0L -> local.addedAt
        local.addedAt <= 0L -> addedAt
        else -> minOf(local.addedAt, addedAt)
    }
    return copy(
        logo = logo ?: local.logo,
        addedAt = firstSaved,
        playlistAddedAt = memberships,
        imdbId = imdbId ?: local.imdbId,
        tmdbId = tmdbId ?: local.tmdbId,
        movieDistributionDates = movieDistributionDates?.merge(local.movieDistributionDates) ?: local.movieDistributionDates
    )
}
