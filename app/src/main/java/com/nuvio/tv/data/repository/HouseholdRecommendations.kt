package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.domain.model.WatchedItem
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.IOException
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

private val token = Regex("[A-Za-z0-9_-]{43}")
private val contentId = Regex("(tt[0-9]{1,12}|tmdb:[0-9]{1,12})")

internal fun isHouseholdDiscoveryBase(
    baseUrl: String, origin: String = BuildConfig.MKBTV_DISCOVERY_ORIGIN
): Boolean {
    val allowed = origin.toHttpUrlOrNull() ?: return false
    if (allowed.encodedPath != "/" || allowed.encodedUsername.isNotEmpty() || allowed.encodedPassword.isNotEmpty() ||
        allowed.query != null || allowed.fragment != null) return false
    val prefix = allowed.toString()
    return baseUrl.startsWith(prefix) && token.matches(baseUrl.removePrefix(prefix))
}

internal fun householdSessionUrl(
    profileId: Int, addonId: String, baseUrl: String, origin: String = BuildConfig.MKBTV_DISCOVERY_ORIGIN
): HttpUrl? =
    if (addonId == "dk.mkb.nuvio.discovery.p$profileId" && isHouseholdDiscoveryBase(baseUrl, origin)) {
        "$baseUrl/session.json".toHttpUrl()
    } else null

@JsonClass(generateAdapter = true)
internal data class HouseholdWatch(val type: String, val id: String, val at: Long)

@JsonClass(generateAdapter = true)
internal data class HouseholdSession(
    val session: String,
    val watched: List<HouseholdWatch>,
    val seasonalWatched: List<HouseholdWatch>? = null,
    val seasonalUnwatched: List<HouseholdWatch>? = null,
    val seasonalInProgress: List<String>? = null
)

internal fun nextHouseholdCheckDelay(validUntil: Long, now: Long, retryAfter: Long? = null): Long =
    minOf(validUntil - now, retryAfter ?: Long.MAX_VALUE, TimeUnit.HOURS.toMillis(1)).coerceAtLeast(5_000L)

private data class HouseholdScope(
    val url: HttpUrl,
    val supportsSeasonal: Boolean,
    val movieIds: Set<String>,
    val seriesIds: Set<String>
)

internal data class SeasonalHistory(
    val profileId: Int,
    val baseUrl: String,
    val watched: Map<String, Long>,
    val startedSeries: Set<String>
)

internal fun seasonalProgressIds(progress: List<WatchProgress>, seriesIds: Set<String>): List<String> =
    progress.asSequence()
        .filter { it.contentId in seriesIds && it.contentType in setOf("tv", "series") &&
            it.season != null && it.episode != null && (it.isInProgress() || it.isCompleted()) }
        .map { it.contentId }.distinct().sorted().take(8).toList()

internal fun seasonalMovieWatches(items: List<WatchedItem>, ids: Set<String>, now: Long): List<HouseholdWatch> =
    items.asSequence()
        .filter { it.contentId in ids && it.contentType == "movie" && it.season == null && it.episode == null &&
            it.watchedAt in 1..now + TimeUnit.DAYS.toMillis(1) }
        .sortedWith(compareByDescending<WatchedItem> { it.watchedAt }.thenBy { it.contentId })
        .distinctBy { it.contentId }.take(512).map { HouseholdWatch("movie", it.contentId, it.watchedAt) }.toList()

private interface HouseholdDiscoveryApi {
    @POST
    suspend fun update(@Url url: HttpUrl, @Body session: HouseholdSession): Response<AddonManifestDto>
}

internal fun recentHouseholdWatches(items: List<WatchedItem>, now: Long): List<WatchedItem> =
    items.asSequence()
        .filter { it.watchedAt in 1..now + TimeUnit.DAYS.toMillis(1) && contentId.matches(it.contentId) }
        .mapNotNull { item ->
            when (item.contentType) {
                "movie", "series" -> item
                "tv" -> item.copy(contentType = "series")
                else -> null
            }
        }
        .sortedWith(compareByDescending<WatchedItem> { it.watchedAt }.thenBy { it.contentType }
            .thenBy { it.contentId }.thenByDescending { it.season }.thenByDescending { it.episode })
        .distinctBy { it.contentType to it.contentId }
        .take(100)
        .toList()

@Singleton
class HouseholdRecommendations @Inject constructor(
    private val profiles: ProfileDataStore,
    private val watchedItems: WatchedItemsPreferences,
    private val progress: WatchProgressPreferences,
    private val addons: AddonRepositoryImpl,
    moshi: Moshi
) {
    // The LAN addon is the only destination allowed to receive profile history.
    // Do not inherit addon interceptors, logging, proxies or redirect behavior.
    private val api = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(180, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build())
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build().create(HouseholdDiscoveryApi::class.java)

    private val _seasonalHistory = MutableStateFlow<SeasonalHistory?>(null)
    internal val seasonalHistory = _seasonalHistory.asStateFlow()
    private val _successfulChecks = MutableStateFlow(0L)
    internal val successfulChecks = _successfulChecks.asStateFlow()
    private val homeEntries = MutableStateFlow(0L)
    internal fun onHomeEntry() { homeEntries.update { it + 1 } }

    private var visit: String? = null

    fun endVisit() {
        visit = null
    }

    suspend fun observeVisit() {
        val session = visit ?: UUID.randomUUID().toString().also { visit = it }
        profiles.activeProfileId.distinctUntilChanged().collectLatest { profileId ->
            addons.getInstalledAddons().map { installed ->
                installed.firstNotNullOfOrNull { addon ->
                    if (!addon.enabled) return@firstNotNullOfOrNull null
                    val url = householdSessionUrl(profileId, addon.id, addon.baseUrl) ?: return@firstNotNullOfOrNull null
                    HouseholdScope(url, addon.seasonalValidUntil != null,
                        addon.seasonalMovieIds.orEmpty().take(512).filter(contentId::matches).toSet(),
                        addon.seasonalSeriesIds.orEmpty().take(8).filter(contentId::matches).toSet())
                }
            }.distinctUntilChanged().collectLatest { scope ->
                if (scope == null) {
                    _seasonalHistory.value = null
                    return@collectLatest
                }
                val series = if (scope.seriesIds.isEmpty()) flowOf(emptyList()) else {
                    progress.observeAllRawProgress(profileId)
                        .map { seasonalProgressIds(it, scope.seriesIds) }.distinctUntilChanged()
                }
                val inputs = combine(watchedItems.observeHouseholdHistory(profileId), series) { history, started ->
                    val (items, undos) = history
                    val now = System.currentTimeMillis()
                    val recent = recentHouseholdWatches(items, now).map { HouseholdWatch(it.contentType, it.contentId, it.watchedAt) }
                    if (!scope.supportsSeasonal) return@combine HouseholdSession(session, recent)
                    val watched = seasonalMovieWatches(items, scope.movieIds, now)
                    val unwatched = undos.entries.sortedBy { it.key }.take(512).map { HouseholdWatch("movie", it.key, it.value) }
                    HouseholdSession(session, recent, watched, unwatched, started)
                }.distinctUntilChanged().flowOn(Dispatchers.Default)
                combine(inputs, homeEntries) { input, _ -> input }.collectLatest { input ->
                    val base = scope.url.toString().removeSuffix("/session.json")
                    _seasonalHistory.value = if (scope.supportsSeasonal) {
                        val watched = (input.watched + input.seasonalWatched.orEmpty())
                            .filter { it.type == "movie" }.associate { it.id to it.at }
                        SeasonalHistory(profileId, base, watched, input.seasonalInProgress.orEmpty().toSet())
                    } else null
                    refresh(profileId, scope.url, input)
                }
            }
        }
    }

    private suspend fun refresh(profileId: Int, url: HttpUrl, input: HouseholdSession) {
        var retryMs = 5_000L
        while (true) {
            try {
                val response = api.update(url, input)
                val manifest = response.body()
                if (response.isSuccessful && manifest != null &&
                    manifest.id == "dk.mkb.nuvio.discovery.p$profileId" && manifest.configVersion != null
                ) {
                    val base = url.toString().removeSuffix("/session.json")
                    if (manifest.seasonalValidUntil != null) {
                        watchedItems.acceptHouseholdFeedback(profileId, input.seasonalUnwatched.orEmpty().associate { it.id to it.at })
                    }
                    addons.acceptHouseholdManifest(manifest.toDomain(base))
                    val boundary = manifest.seasonalValidUntil ?: return
                    _successfulChecks.update { it + 1 }
                    // Also notice daytime pool imports, even when this profile currently has no row.
                    delay(nextHouseholdCheckDelay(boundary, System.currentTimeMillis(), manifest.householdRetryAfterMs))
                    retryMs = 5_000L
                    continue
                }
                response.errorBody()?.close()
                if (response.code() !in 500..599 && response.code() != 429) return
            } catch (_: IOException) {
                // Retry while the same visit, profile, addon and watch input are active.
            } catch (_: com.squareup.moshi.JsonDataException) {
                return
            }
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(300_000L)
        }
    }
}
