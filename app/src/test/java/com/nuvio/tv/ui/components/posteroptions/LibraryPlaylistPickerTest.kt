package com.nuvio.tv.ui.components.posteroptions

import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.core.tracking.TrackingMembershipApplyResult
import com.nuvio.tv.core.tracking.TrackingMembershipMutationException
import com.nuvio.tv.core.tracking.TrackingMembershipMutationFailure
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalImpact
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import com.nuvio.tv.domain.repository.LibraryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPlaylistPickerTest {
    private val item = LibraryEntryInput("tt123", "movie", "A movie")
    private val watchLater = LibraryListTab(LOCAL_LIBRARY_LIST_KEY, "Watch Later", LibraryListTab.Type.WATCHLIST)
    private val first = LibraryListTab("local:playlist:1", "Classics", LibraryListTab.Type.PERSONAL)
    private val second = LibraryListTab("local:playlist:2", "Family", LibraryListTab.Type.PERSONAL)
    private val provider = LibraryListTab("simkl:status:completed", "Completed", LibraryListTab.Type.STATUS, trackingProviderId = "simkl")
    private val repository = mockk<LibraryRepository>()
    private val profileId = MutableStateFlow(1)

    private fun TestScope.open(vararg selected: String, input: LibraryEntryInput = item): LibraryPlaylistPicker {
        every { repository.membershipListTabs } returns flowOf(listOf(watchLater, first, second, provider))
        coEvery { repository.getMembershipSnapshot(input) } returns ListMembershipSnapshot(selected.associateWith { true })
        coEvery { repository.applyMembershipChanges(any(), any(), any()) } returns TrackingMembershipApplyResult()
        return LibraryPlaylistPicker(repository, profileId, backgroundScope).also {
            it.open(input)
            runCurrent()
        }
    }

    @Test
    fun newTitleDefaultsToWatchLaterButExplicitPlaylistSavesOnlyThere() = runTest {
        val picker = open()
        picker.save()
        runCurrent()
        coVerify(exactly = 1) {
            repository.applyMembershipChanges(item, ListMembershipChanges(mapOf(watchLater.key to true)), emptySet())
        }
        picker.open(item)
        runCurrent()
        picker.toggle(first.key)
        picker.save()
        runCurrent()
        coVerify(exactly = 1) {
            repository.applyMembershipChanges(item, ListMembershipChanges(mapOf(first.key to true)), emptySet())
        }
        assertNull(picker.state.value)
    }

    @Test
    fun createAndSelectSeveralListsRetainsEarlierChoices() = runTest {
        val picker = open()
        val created = LibraryListTab("local:playlist:3", "Weekend", LibraryListTab.Type.PERSONAL)
        coEvery { repository.createLocalPlaylist("Weekend") } returns created
        picker.toggle(watchLater.key)
        picker.toggle(first.key)
        picker.setCreating(true)
        picker.create(" Weekend ")
        runCurrent()
        val draft = requireNotNull(picker.state.value)
        assertFalse(draft.creating)
        assertEquals(listOf(watchLater, first, second, created, provider), draft.tabs)
        coVerify(exactly = 0) { repository.applyMembershipChanges(any(), any(), any()) }
        picker.save()
        runCurrent()
        coVerify {
            repository.applyMembershipChanges(item, ListMembershipChanges(
                mapOf(watchLater.key to true, first.key to true, created.key to true)
            ), emptySet())
        }
    }

    @Test
    fun moveAndRemoveLastListSendOnlyEditedMemberships() = runTest {
        val picker = open(first.key, provider.key)
        assertEquals(mapOf(first.key to true, provider.key to true), picker.state.value?.membership)
        picker.toggle(first.key)
        picker.toggle(second.key)
        picker.save()
        runCurrent()
        coVerify {
            repository.applyMembershipChanges(item, ListMembershipChanges(mapOf(first.key to false, second.key to true)), emptySet())
        }
        coEvery { repository.getMembershipSnapshot(item) } returns ListMembershipSnapshot(mapOf(second.key to true))
        picker.open(item)
        runCurrent()
        picker.toggle(second.key)
        picker.save()
        runCurrent()
        coVerify {
            repository.applyMembershipChanges(item, ListMembershipChanges(mapOf(second.key to false)), emptySet())
        }
    }

    @Test
    fun backingOutAndSwitchingProfileDiscardUnsavedEdits() = runTest {
        val picker = open()
        picker.toggle(first.key)
        picker.dismiss()
        assertNull(picker.state.value)
        picker.open(item)
        runCurrent()
        picker.toggle(second.key)
        profileId.value = 2
        picker.save()
        runCurrent()
        assertNull(picker.state.value)
        coVerify(exactly = 0) { repository.applyMembershipChanges(any(), any(), any()) }
    }

    @Test
    fun failureOrCancelledLoadCannotSaveAnEmptySnapshot() = runTest {
        every { repository.membershipListTabs } returns flowOf(listOf(watchLater))
        coEvery { repository.getMembershipSnapshot(item) } throws IllegalStateException("Unavailable")
        val picker = LibraryPlaylistPicker(repository, profileId, backgroundScope)
        picker.open(item)
        runCurrent()
        assertNotNull(picker.state.value?.error)
        picker.save()
        val response = CompletableDeferred<ListMembershipSnapshot>()
        coEvery { repository.getMembershipSnapshot(item) } coAnswers { response.await() }
        picker.load()
        runCurrent()
        picker.dismiss()
        response.complete(ListMembershipSnapshot())
        runCurrent()
        assertNull(picker.state.value)
        coVerify(exactly = 0) { repository.applyMembershipChanges(any(), any(), any()) }
    }

    @Test
    fun serverTitlesOfferOnlyNativePlaylists() = runTest {
        val input = item.copy(itemId = "srv1:server:42")
        val picker = open(input = input)
        assertEquals(listOf(watchLater, first, second), picker.state.value?.tabs)
        picker.toggle(provider.key)
        picker.toggle(first.key)
        picker.save()
        runCurrent()
        coVerify { repository.applyMembershipChanges(input, ListMembershipChanges(mapOf(first.key to true)), emptySet()) }
    }

    @Test
    fun membershipInReadOnlyOrHiddenListsStillAllowsTheWatchLaterDefault() = runTest {
        val readOnly = provider.copy(key = "remote:shared", isMembershipDestination = false)
        val seriesOnly = provider.copy(key = "remote:series", supportedContentTypes = setOf("series"))
        every { repository.membershipListTabs } returns flowOf(listOf(watchLater, first, readOnly, seriesOnly))
        coEvery { repository.getMembershipSnapshot(item) } returns ListMembershipSnapshot(
            mapOf(readOnly.key to true, seriesOnly.key to true, "remote:hidden" to true)
        )
        coEvery { repository.applyMembershipChanges(any(), any(), any()) } returns TrackingMembershipApplyResult()
        val picker = LibraryPlaylistPicker(repository, profileId, backgroundScope)
        picker.open(item)
        runCurrent()
        assertEquals(listOf(watchLater, first), picker.state.value?.tabs)
        assertEquals(emptyMap<String, Boolean>(), picker.state.value?.membership)
        picker.save()
        runCurrent()
        coVerify { repository.applyMembershipChanges(item, ListMembershipChanges(mapOf(watchLater.key to true)), emptySet()) }
    }

    @Test
    fun unavailableProviderDoesNotBlockNativeSelections() = runTest {
        every { repository.membershipListTabs } returns flowOf(listOf(watchLater, first, provider))
        coEvery { repository.getMembershipSnapshot(item) } returns ListMembershipSnapshot(
            listMembership = mapOf(watchLater.key to true),
            unavailableProviders = setOf(TrackingProviderId.SIMKL)
        )
        coEvery { repository.applyMembershipChanges(any(), any(), any()) } returns TrackingMembershipApplyResult()
        val picker = LibraryPlaylistPicker(repository, profileId, backgroundScope)
        picker.open(item)
        runCurrent()
        assertEquals(listOf(watchLater, first), picker.state.value?.tabs)
        assertEquals(setOf(TrackingProviderId.SIMKL), picker.state.value?.unavailableProviders)
        picker.toggle(first.key)
        picker.save()
        runCurrent()
        coVerify { repository.applyMembershipChanges(item, ListMembershipChanges(mapOf(first.key to true)), emptySet()) }
    }

    @Test
    fun retryCanUndoANativeChangeThatCommittedBeforeAProviderFailed() = runTest {
        val picker = open()
        val initialChanges = ListMembershipChanges(mapOf(first.key to true, provider.key to true))
        coEvery { repository.applyMembershipChanges(item, initialChanges, emptySet()) } throws
            TrackingMembershipMutationException(listOf(
                TrackingMembershipMutationFailure(TrackingProviderId.SIMKL, IllegalStateException("Unavailable"))
            ))
        coEvery { repository.getMembershipSnapshot(item) } returns ListMembershipSnapshot(
            mapOf(first.key to true, "remote:hidden" to true)
        )
        picker.toggle(first.key)
        picker.toggle(provider.key)
        picker.save()
        runCurrent()
        assertEquals(initialChanges.desiredMembership, picker.state.value?.membership)
        assertNotNull(picker.state.value?.error)
        picker.toggle(first.key)
        picker.save()
        runCurrent()
        coVerify { repository.applyMembershipChanges(
            item, ListMembershipChanges(mapOf(first.key to false, provider.key to true)), emptySet()
        ) }
        assertNull(picker.state.value)
    }

    @Test
    fun providerRemovalCanBeCancelledOrExplicitlyConfirmed() = runTest {
        val picker = open(provider.key)
        val changes = ListMembershipChanges(mapOf(provider.key to false))
        val confirmation = TrackingMembershipRemovalConfirmation(TrackingProviderId.SIMKL, setOf(TrackingMembershipRemovalImpact.RATING))
        coEvery { repository.applyMembershipChanges(item, changes, emptySet()) } returns TrackingMembershipApplyResult(listOf(confirmation))
        picker.toggle(provider.key)
        picker.save()
        runCurrent()
        assertEquals(listOf(confirmation), picker.state.value?.confirmations)
        picker.cancelConfirmation()
        assertEquals(emptyList<TrackingMembershipRemovalConfirmation>(), picker.state.value?.confirmations)
        coVerify(exactly = 0) { repository.applyMembershipChanges(item, changes, setOf(TrackingProviderId.SIMKL)) }
        picker.save()
        runCurrent()
        picker.save()
        runCurrent()
        coVerify(exactly = 1) { repository.applyMembershipChanges(item, changes, setOf(TrackingProviderId.SIMKL)) }
        assertNull(picker.state.value)
    }
}
