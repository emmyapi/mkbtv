package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.core.tracking.TrackingListManager
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingMembershipApplyResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.core.tracking.TrackingMembershipMutationException
import com.nuvio.tv.core.tracking.dispatchTrackingMembershipChanges
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.effectiveLibrarySourceMode
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.core.tracking.supportsMembershipFor
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import com.nuvio.tv.domain.model.LocalPlaylist
import com.nuvio.tv.domain.model.NativeLibraryIdentity
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.LibraryListPrivacy
import com.nuvio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryImpl @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val libraryPreferences: LibraryPreferences,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val librarySyncService: LibrarySyncService,
    private val authManager: AuthManager,
    private val metaRepository: MetaRepository,
    private val trackingProviders: TrackingLibraryProviderRegistry,
    private val profileManager: ProfileManager,
) : LibraryRepository {

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hydratedLogoIds = mutableSetOf<String>()
    private var syncJob: Job? = null
    private val _isSyncingFromRemote = MutableStateFlow(false)
    var isSyncingFromRemote: Boolean
        get() = _isSyncingFromRemote.value
        set(value) { _isSyncingFromRemote.value = value }
    @Volatile
    var hasCompletedInitialPull = false

    private fun triggerRemoteSync(profileId: Int) {
        if (!hasCompletedInitialPull) return
        if (!authManager.isAuthenticated) return
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(500)
            librarySyncService.pushToRemote(profileId)
        }
    }

    private val providerConnections = combine(
        trackingProviders.providers().map { provider ->
            provider.isAuthenticated.map { authenticated -> provider.providerId to authenticated }
        }
    ) { states -> states.toMap() }

    override val sourceMode: Flow<LibrarySourceMode> = combine(
        traktSettingsDataStore.librarySourceMode,
        providerConnections
    ) { mode, connections ->
        effectiveLibrarySourceMode(mode) { providerId -> connections[providerId] == true }
    }.distinctUntilChanged()

    override val isSyncing: Flow<Boolean> = sourceMode
        .flatMapLatest { mode ->
            mode.providerId
                ?.let(trackingProviders::provider)
                ?.isRefreshing
                ?: _isSyncingFromRemote
        }
        .distinctUntilChanged()

    override val localLibraryItems = libraryPreferences.libraryItems.map { items ->
        items.map { saved ->
            val memberships = saved.memberships
            LibraryEntry(
                id = saved.id,
                type = saved.type,
                name = saved.name,
                poster = saved.poster,
                posterShape = saved.posterShape,
                background = saved.background,
                logo = saved.logo,
                description = saved.description,
                releaseInfo = saved.releaseInfo,
                imdbRating = saved.imdbRating,
                genres = saved.genres,
                addonBaseUrl = saved.addonBaseUrl,
                imdbId = saved.imdbId,
                tmdbId = saved.tmdbId,
                listKeys = memberships.keys,
                listedAt = saved.addedAt,
                listAddedAt = memberships,
                rawPosterUrl = saved.poster,
                movieDistributionDates = saved.movieDistributionDates
            )
        }
    }.distinctUntilChanged()

    override val localListTabs = libraryPreferences.playlists.map { playlists ->
        listOf(LibraryListTab(
            key = LOCAL_LIBRARY_LIST_KEY,
            title = appContext.getString(R.string.library_picker_watch_later),
            type = LibraryListTab.Type.WATCHLIST
        )) + playlists.map(LocalPlaylist::toTab)
    }.distinctUntilChanged()

    override val libraryItems: Flow<List<LibraryEntry>> = sourceMode.flatMapLatest { mode ->
        mode.providerId?.let(trackingProviders::provider)?.items ?: localLibraryItems
    }.distinctUntilChanged()

    override val listTabs: Flow<List<LibraryListTab>> = sourceMode.flatMapLatest { mode ->
        mode.providerId?.let(trackingProviders::provider)?.tabs ?: localListTabs
    }.distinctUntilChanged()

    override val membershipListTabs: Flow<List<LibraryListTab>> = combine(
        listOf(localListTabs) + trackingProviders.providers().map { provider ->
            provider.isAuthenticated.flatMapLatest { authenticated ->
                if (authenticated) provider.tabs else flowOf(emptyList())
            }.catch { error ->
                if (error is CancellationException) throw error
                emit(emptyList())
            }
        }
    ) { tabs -> tabs.flatMap { it } }.distinctUntilChanged()

    override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> =
        isInLibrary(LibraryEntryInput(itemId, itemType, title = ""))

    override fun isInLibrary(item: LibraryEntryInput): Flow<Boolean> {
        val local = libraryPreferences.observeMembership(item.itemId, item.itemType, item.imdbId, item.tmdbId)
            .map { it.isNotEmpty() }.distinctUntilChanged()
        if (item.isServerItem) return local

        val trackingItem = item.forTracking()
        val connected = trackingProviders.providers().map { provider ->
            provider.isAuthenticated.flatMapLatest { authenticated ->
                if (!authenticated) flowOf(false)
                else combine(provider.tabs, provider.observeMembership(trackingItem.itemId, item.itemType)) { tabs, keys ->
                    tabs.any { tab -> tab.key in keys && tab.supportsMembershipFor(item.itemType) }
                }.catch { error ->
                    if (error is CancellationException) throw error
                    emit(false)
                }
            }.catch { error ->
                if (error is CancellationException) throw error
                emit(false)
            }
        }
        return local.flatMapLatest { saved ->
            if (saved || connected.isEmpty()) flowOf(saved)
            else combine(connected) { memberships -> memberships.any { it } }
        }.distinctUntilChanged()
    }

    override fun isInWatchlist(itemId: String, itemType: String): Flow<Boolean> {
        return sourceMode.flatMapLatest { mode ->
            val provider = mode.providerId?.let(trackingProviders::provider)
            if (provider != null) {
                combine(
                    provider.observeMembership(itemId, itemType),
                    provider.tabs
                ) { memberships, tabs ->
                    tabs.any { tab ->
                        tab.type == LibraryListTab.Type.WATCHLIST && tab.key in memberships
                    }
                }
            } else {
                libraryPreferences.observeMembership(itemId, itemType).map { LOCAL_LIBRARY_LIST_KEY in it }
            }
        }.distinctUntilChanged()
    }

    override suspend fun toggleDefault(
        item: LibraryEntryInput,
        confirmedRemovalProviders: Set<TrackingProviderId>
    ): TrackingMembershipApplyResult {
        val profileId = profileManager.activeProfileId.value
        val provider = sourceMode.first().providerId?.let(trackingProviders::provider)
        if (provider != null) {
            if (item.isServerItem) throw IllegalStateException(appContext.getString(R.string.library_server_title_unsupported))
            val trackingItem = item.forTracking()
            val currentMembership = provider.getMembershipSnapshot(trackingItem).listMembership
            val changes = ListMembershipChanges(
                provider.toggledDefaultMembership(currentMembership)
            )
            val confirmation = provider.membershipRemovalConfirmation(trackingItem, changes)
                ?.takeUnless { required ->
                    required.providerId in confirmedRemovalProviders
                }
            if (confirmation != null) {
                return TrackingMembershipApplyResult(listOf(confirmation))
            }
            check(profileManager.activeProfileId.value == profileId) { "Profile changed" }
            provider.applyMembershipChanges(
                item = trackingItem,
                changes = changes,
                destructiveRemovalConfirmed = provider.providerId in confirmedRemovalProviders
            )
            return TrackingMembershipApplyResult()
        }

        check(profileManager.activeProfileId.value == profileId) { "Profile changed" }
        val syncNeeded = libraryPreferences.editMemberships(item.toSavedLibraryItem(), profileId) { selected ->
            mapOf(LOCAL_LIBRARY_LIST_KEY to (LOCAL_LIBRARY_LIST_KEY !in selected))
        }
        if (syncNeeded) triggerRemoteSync(profileId)
        return TrackingMembershipApplyResult()
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        val profileId = profileManager.activeProfileId.value
        val membership = libraryPreferences.membershipSnapshot(item.itemId, item.itemType, profileId, item.imdbId, item.tmdbId).toMutableMap()

        val unavailable = mutableSetOf<TrackingProviderId>()
        if (!item.isServerItem) {
            val trackingItem = item.forTracking()
            trackingProviders.providers().forEach { provider ->
                try {
                    if (provider.isAuthenticated.first()) {
                        membership.putAll(provider.getMembershipSnapshot(trackingItem).listMembership)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    unavailable.add(provider.providerId)
                }
            }
        }

        check(profileManager.activeProfileId.value == profileId) { "Profile changed" }
        return ListMembershipSnapshot(listMembership = membership, unavailableProviders = unavailable)
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        confirmedRemovalProviders: Set<TrackingProviderId>
    ): TrackingMembershipApplyResult {
        val profileId = profileManager.activeProfileId.value
        val desired = changes.desiredMembership
        val requestedChanges = trackingProviders.providers().mapNotNull { provider ->
            val providerMembership = desired.filterKeys(provider::recognizesListKey)
            if (providerMembership.isEmpty()) return@mapNotNull null
            check(provider.isAuthenticated.first()) { "Connect the library account first" }
            provider to ListMembershipChanges(providerMembership)
        }
        if (item.isServerItem && requestedChanges.any { (_, change) -> change.desiredMembership.values.any { it } }) {
            throw IllegalStateException(appContext.getString(R.string.library_server_title_unsupported))
        }
        val providerChanges = if (item.isServerItem) emptyList() else requestedChanges
        val trackingItem = item.forTracking()
        val requiredConfirmations = providerChanges.mapNotNull { (provider, providerChange) ->
            provider.membershipRemovalConfirmation(trackingItem, providerChange)
                ?.takeUnless { confirmation -> confirmation.providerId in confirmedRemovalProviders }
        }
        if (requiredConfirmations.isNotEmpty()) {
            return TrackingMembershipApplyResult(requiredConfirmations)
        }

        check(profileManager.activeProfileId.value == profileId) { "Profile changed" }
        val localChanges = desired.filterKeys { it == LOCAL_LIBRARY_LIST_KEY || it.startsWith(LocalPlaylist.KEY_PREFIX) }
        if (localChanges.isNotEmpty()) {
            val syncNeeded = libraryPreferences.editMemberships(item.toSavedLibraryItem(), profileId) { localChanges }
            if (syncNeeded) triggerRemoteSync(profileId)
        }

        check(profileManager.activeProfileId.value == profileId) { "Profile changed" }
        val failures = dispatchTrackingMembershipChanges(
            item = trackingItem,
            providerChanges = providerChanges,
            confirmedRemovalProviders = confirmedRemovalProviders
        )
        if (failures.isNotEmpty()) {
            throw TrackingMembershipMutationException(failures)
        }
        return TrackingMembershipApplyResult()
    }

    override suspend fun createLocalPlaylist(name: String): LibraryListTab =
        libraryPreferences.createPlaylist(name, profileManager.activeProfileId.value).toTab()

    override suspend fun createPersonalList(name: String, description: String?, privacy: LibraryListPrivacy, source: LibrarySourceMode) {
        if (source == LibrarySourceMode.LOCAL) createLocalPlaylist(name)
        else selectedListManager(source).createList(name, description, privacy)
    }

    override suspend fun updatePersonalList(
        listId: String,
        name: String,
        description: String?,
        privacy: LibraryListPrivacy,
        source: LibrarySourceMode
    ) {
        if (source == LibrarySourceMode.LOCAL) libraryPreferences.renamePlaylist(listId, name, profileManager.activeProfileId.value)
        else selectedListManager(source, listOf(listId)).updateList(listId, name, description, privacy)
    }

    override suspend fun deletePersonalList(listId: String, source: LibrarySourceMode) {
        if (source == LibrarySourceMode.LOCAL) libraryPreferences.deletePlaylist(listId, profileManager.activeProfileId.value)
        else selectedListManager(source, listOf(listId)).deleteList(listId)
    }

    override suspend fun reorderPersonalLists(orderedListIds: List<String>, source: LibrarySourceMode) {
        if (source == LibrarySourceMode.LOCAL) libraryPreferences.reorderPlaylists(orderedListIds, profileManager.activeProfileId.value)
        else selectedListManager(source, orderedListIds).reorderLists(orderedListIds)
    }

    private suspend fun selectedListManager(
        source: LibrarySourceMode,
        keys: List<String> = emptyList()
    ): TrackingListManager {
        val profileId = profileManager.activeProfileId.value
        check(sourceMode.first() == source) { "Library source changed" }
        val provider = source.providerId?.let(trackingProviders::provider)
            ?: throw IllegalStateException("This library does not support list management")
        check(provider.isAuthenticated.first()) { "Connect the library account first" }
        require(keys.all(provider::recognizesListKey)) { "Invalid list for the library provider" }
        check(profileManager.activeProfileId.value == profileId) { "Profile changed" }
        return provider.listManager ?: throw IllegalStateException("This provider does not support list management")
    }

    override suspend fun refreshNow() {
        sourceMode.first().providerId
            ?.let(trackingProviders::provider)
            ?.refresh(TrackingRefreshIntent.USER_INITIATED)
    }


    private fun LibraryEntryInput.toSavedLibraryItem(): SavedLibraryItem {
        return SavedLibraryItem(
            id = itemId,
            type = itemType,
            name = title,
            poster = poster,
            posterShape = posterShape,
            background = background,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = addonBaseUrl,
            logo = logo,
            imdbId = imdbId,
            tmdbId = tmdbId,
            movieDistributionDates = movieDistributionDates
        )
    }

    private suspend fun hydrateLibraryLogos(items: List<LibraryEntry>) {
        items.take(20).forEach { entry ->
            hydratedLogoIds.add(entry.id)
            runCatching {
                val result = metaRepository.getMetaFromPrimaryAddon(entry.type, entry.id)
                    .firstOrNull { it is NetworkResult.Success }
                val logo = (result as? NetworkResult.Success)?.data?.logo
                if (logo != null) {
                    libraryPreferences.updateLogo(entry.id, entry.type, logo)
                }
            }.onFailure { Log.w("LibraryRepo", "Logo hydration failed for ${entry.id}", it) }
        }
    }
}

private fun LibraryEntryInput.forTracking(): LibraryEntryInput {
    if (isServerItem) return this
    val identity = NativeLibraryIdentity(itemId, itemType, imdbId, tmdbId)
    val externalId = identity.imdb ?: identity.tmdb?.let { "tmdb:$it" } ?: itemId
    return copy(itemId = externalId, imdbId = identity.imdb, tmdbId = identity.tmdb)
}

private val LibraryEntryInput.isServerItem: Boolean
    get() = ServerItemRef.isServerId(itemId)
