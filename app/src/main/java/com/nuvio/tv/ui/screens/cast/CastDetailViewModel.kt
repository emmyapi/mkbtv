package com.nuvio.tv.ui.screens.cast

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.PublicPersonService
import com.nuvio.tv.core.tmdb.PublicPersonRateLimited
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import javax.inject.Inject

@HiltViewModel
class CastDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbMetadataService: TmdbMetadataService,
    metaRepository: MetaRepository,
    private val publicPersonService: PublicPersonService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val personId: Int = savedStateHandle.get<String>("personId")?.toIntOrNull() ?: 0
    val personName: String = savedStateHandle.get<String>("personName").orEmpty()
    private val preferCrew: Boolean = savedStateHandle.get<Boolean>("preferCrew") ?: false
    private val sourceContentId: String = savedStateHandle.get<String>("sourceContentId").orEmpty()
    private val sourceDetail = sourcePersonDetail(savedStateHandle, metaRepository)
    private var loadJob: Job? = null

    private val initialState = sourceDetail?.let { CastDetailUiState.Success(it) } ?: CastDetailUiState.Loading
    private val _uiState = MutableStateFlow<CastDetailUiState>(initialState)
    val uiState: StateFlow<CastDetailUiState> = _uiState.asStateFlow()

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    private val _posterCardCornerRadiusDp = MutableStateFlow(12)
    val posterCardCornerRadiusDp: StateFlow<Int> = _posterCardCornerRadiusDp.asStateFlow()

    init {
        posterOptions.bind(viewModelScope)
        loadPersonDetail()
        observeWatchedStatus()
        viewModelScope.launch {
            layoutPreferenceDataStore.posterCardCornerRadiusDp
                .collect { _posterCardCornerRadiusDp.value = it }
        }
    }

    // The user reached this page by selecting a credited person on this title.
    // Name, type and ID survive process restoration even if the title cache is empty.
    private fun sourcePersonDetail(args: SavedStateHandle, repository: MetaRepository): PersonDetail? {
        val id = args.get<String>("sourceId")?.takeIf { it.isNotBlank() } ?: return null
        val title = args.get<String>("sourceTitle")?.takeIf { it.isNotBlank() } ?: return null
        val rawType = args.get<String>("sourceType")?.takeIf { it.isNotBlank() } ?: return null
        val type = ContentType.fromString(rawType)
        val meta = repository.getCachedMeta(rawType, id)
        val credit = MetaPreview(
            id = id, type = type, rawType = rawType, name = title,
            poster = meta?.poster, posterShape = meta?.posterShape ?: PosterShape.POSTER,
            background = meta?.background, logo = meta?.logo, description = meta?.description,
            releaseInfo = meta?.releaseInfo, imdbRating = meta?.imdbRating, genres = meta?.genres.orEmpty(),
            imdbId = meta?.imdbId ?: sourceContentId.takeIf { it.matches(Regex("tt[0-9]+")) }
        )
        return PersonDetail(
            tmdbId = personId, name = personName, biography = null,
            birthday = null, deathday = null, placeOfBirth = null,
            profilePhoto = meta?.castMembers?.firstOrNull { it.name == personName }?.photo,
            knownFor = null,
            movieCredits = if (type == ContentType.MOVIE) listOf(credit) else emptyList(),
            tvCredits = if (type == ContentType.MOVIE) emptyList() else listOf(credit),
            partialFilmography = true
        )
    }

    private fun PersonDetail.withKnownCredit(): PersonDetail {
        val source = sourceDetail ?: return this
        val credit = source.movieCredits.firstOrNull() ?: source.tvCredits.single()
        fun matches(item: MetaPreview) = item.id == credit.id || (credit.imdbId != null &&
            (item.id == credit.imdbId || item.imdbId == credit.imdbId))
        val match = movieCredits.firstOrNull(::matches) ?: tvCredits.firstOrNull(::matches)
        // Keep the originating library ID for navigation and focus even when public credits use IMDb.
        val knownCredit = match?.copy(
            id = credit.id, type = credit.type, rawType = credit.rawType,
            poster = credit.poster ?: match.poster, imdbId = credit.imdbId ?: match.imdbId
        ) ?: credit
        return copy(
            profilePhoto = profilePhoto ?: source.profilePhoto,
            movieCredits = movieCredits.filterNot(::matches) + listOfNotNull(knownCredit.takeIf { source.movieCredits.isNotEmpty() }),
            tvCredits = tvCredits.filterNot(::matches) + listOfNotNull(knownCredit.takeIf { source.tvCredits.isNotEmpty() })
        )
    }

    private fun observeWatchedStatus() {
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids -> _watchedMovieIds.value = ids }
        }
    }

    fun retry() {
        _uiState.value = initialState
        loadPersonDetail()
    }

    private fun loadPersonDetail() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
          while (isActive) {
            try {
                val nativeDetail = if (personId > 0 && BuildConfig.TMDB_API_KEY.isNotBlank()) tmdbMetadataService.fetchPersonDetail(
                    personId = personId,
                    preferCrewCredits = preferCrew,
                    language = tmdbSettingsDataStore.settings.first().language
                ) else null
                val fetched = nativeDetail ?: publicPersonService.fetch(personName, preferCrew, sourceContentId)
                if (fetched != null) {
                    val detail = fetched.withKnownCredit()
                    val pattern = layoutPreferenceDataStore.customPosterUrlPattern.first()
                    val enabledScreens = layoutPreferenceDataStore.customPosterEnabledScreens.first()
                    val effectivePattern = com.nuvio.tv.core.poster.patternForScreen(pattern, com.nuvio.tv.core.poster.CustomPosterScreen.DETAILS, enabledScreens)
                    val overlaidDetail = if (effectivePattern.isNotBlank()) {
                        detail.copy(
                            movieCredits = detail.movieCredits.withCustomPosterUrls(effectivePattern),
                            tvCredits = detail.tvCredits.withCustomPosterUrls(effectivePattern)
                        )
                    } else detail
                    _uiState.value = CastDetailUiState.Success(overlaidDetail)
                } else if (sourceDetail == null) {
                    _uiState.value = CastDetailUiState.Error(
                        context.getString(R.string.cast_error_load_details_for, personName)
                    )
                }
                return@launch
            } catch (e: PublicPersonRateLimited) {
                // Keep the already known cast page interactive while enrichment is paused.
                if (sourceDetail == null) {
                    _uiState.value = CastDetailUiState.Error(context.getString(R.string.cast_error_load_details_for, personName))
                }
                delay((e.retryAtElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sourceDetail == null) {
                    _uiState.value = CastDetailUiState.Error(
                        e.message ?: context.getString(R.string.error_unknown)
                    )
                }
                return@launch
            }
          }
        }
    }
}
