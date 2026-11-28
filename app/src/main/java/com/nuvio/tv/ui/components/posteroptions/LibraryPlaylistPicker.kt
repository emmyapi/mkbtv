package com.nuvio.tv.ui.components.posteroptions

import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.core.tracking.TrackingMembershipMutationException
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.supportsMembershipFor
import com.nuvio.tv.core.tracking.toggleTrackingMembershipSelection
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal data class PlaylistPickerState(
    val input: LibraryEntryInput,
    val profileId: Int,
    val tabs: List<LibraryListTab> = emptyList(),
    val original: Map<String, Boolean>? = null,
    val membership: Map<String, Boolean> = emptyMap(),
    val pending: Boolean = false,
    val creating: Boolean = false,
    val unavailableProviders: Set<TrackingProviderId> = emptySet(),
    val error: Exception? = null,
    val confirmations: List<TrackingMembershipRemovalConfirmation> = emptyList()
) {
    val defaultsToWatchLater: Boolean
        get() = original?.values?.none { it } == true && membership.values.none { it }

    fun changes(): ListMembershipChanges = ListMembershipChanges(
        if (defaultsToWatchLater) mapOf(LOCAL_LIBRARY_LIST_KEY to true)
        else membership.filter { (key, desired) -> desired != (original?.get(key) == true) }
    )
}

/** One draft per open dialog. Only Save writes title memberships. */
internal class LibraryPlaylistPicker(
    private val repository: LibraryRepository,
    private val activeProfileId: StateFlow<Int>,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<PlaylistPickerState?>(null)
    val state = _state.asStateFlow()
    private var operation: Job? = null
    private var onSaved: (() -> Unit)? = null

    init {
        scope.launch {
            activeProfileId.collect { id ->
                if (_state.value?.let { it.profileId != id } == true) close()
            }
        }
    }

    fun open(input: LibraryEntryInput, onSaved: (() -> Unit)? = null) {
        if (_state.value != null) return
        this.onSaved = onSaved
        _state.value = PlaylistPickerState(input, activeProfileId.value)
        load()
    }

    fun load() {
        val draft = ready() ?: return
        if (draft.original != null) return
        runOperation(draft) { _state.value = readMembership(draft) }
    }

    private suspend fun readMembership(
        draft: PlaylistPickerState,
        edits: Map<String, Boolean> = emptyMap()
    ): PlaylistPickerState {
        val snapshot = repository.getMembershipSnapshot(draft.input)
        val serverTitle = ServerItemRef.isServerId(draft.input.itemId)
        val tabs = repository.membershipListTabs.first().filter { tab ->
            tab.supportsMembershipFor(draft.input.itemType) &&
                (!serverTitle || tab.trackingProviderId == null) &&
                snapshot.unavailableProviders.none { it.storageId == tab.trackingProviderId }
        }
        val editableKeys = tabs.mapTo(mutableSetOf()) { it.key }
        val actual = snapshot.listMembership.filterKeys { it in editableKeys }
        return draft.copy(
            tabs = tabs,
            original = actual,
            membership = actual + edits.filterKeys { it in editableKeys },
            confirmations = emptyList(),
            unavailableProviders = snapshot.unavailableProviders,
            error = null
        )
    }

    fun toggle(key: String) {
        val draft = ready() ?: return
        if (draft.original == null || draft.tabs.none { it.key == key }) return
        val next = toggleTrackingMembershipSelection(draft.tabs, draft.membership, key, draft.input.itemType)
            ?: return
        _state.value = draft.copy(membership = next, error = null)
    }

    fun setCreating(creating: Boolean) {
        val draft = ready() ?: return
        if (draft.original != null) _state.value = draft.copy(creating = creating, error = null)
    }

    fun create(name: String) {
        val draft = ready() ?: return
        if (!draft.creating || name.isBlank()) return
        runOperation(draft) {
            val tab = repository.createLocalPlaylist(name.trim())
            _state.value = draft.copy(
                tabs = draft.tabs.toMutableList().apply {
                    val firstProvider = indexOfFirst { it.trackingProviderId != null }
                    add(if (firstProvider < 0) size else firstProvider, tab)
                },
                membership = draft.membership + (tab.key to true),
                creating = false,
                error = null
            )
        }
    }

    fun save() {
        val draft = ready() ?: return
        if (draft.original == null || draft.creating) return
        val changes = draft.changes()
        runOperation(draft) {
            val confirmations = try {
                if (changes.desiredMembership.isEmpty()) emptyList() else {
                    repository.applyMembershipChanges(
                        draft.input, changes, draft.confirmations.mapTo(mutableSetOf()) { it.providerId }
                    ).requiredRemovalConfirmations
                }
            } catch (error: TrackingMembershipMutationException) {
                // Native changes and successful providers may already have committed.
                // Keep the user's choices while making the next Save start from actual memberships.
                _state.value = readMembership(draft, changes.desiredMembership).copy(error = error)
                return@runOperation
            }
            if (confirmations.isEmpty()) {
                val callback = onSaved
                _state.value = null
                onSaved = null
                callback?.invoke()
            } else {
                _state.value = draft.copy(confirmations = confirmations, error = null)
            }
        }
    }

    fun cancelConfirmation() {
        val draft = ready() ?: return
        _state.value = draft.copy(confirmations = emptyList())
    }

    fun dismiss() {
        val draft = _state.value ?: return
        if (!draft.pending || draft.original == null) close()
    }

    private fun ready(): PlaylistPickerState? {
        val draft = _state.value ?: return null
        if (draft.profileId != activeProfileId.value) {
            close()
            return null
        }
        return draft.takeUnless { it.pending }
    }

    private fun close() {
        operation?.cancel()
        _state.value = null
        onSaved = null
    }

    private fun runOperation(draft: PlaylistPickerState, action: suspend () -> Unit) {
        _state.value = draft.copy(pending = true, error = null)
        operation = scope.launch {
            if (activeProfileId.value != draft.profileId) {
                close()
                return@launch
            }
            try {
                action()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _state.value = draft.copy(error = error, confirmations = emptyList())
            }
        }
    }
}
