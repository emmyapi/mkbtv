package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.ProfileDataStore
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
private val contentId = Regex("(tt[0-9]+|tmdb:[0-9]+)")

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
internal data class HouseholdSession(val session: String, val watched: List<HouseholdWatch>)

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

    private var visit: String? = null

    fun endVisit() {
        visit = null
    }

    suspend fun observeVisit() {
        val session = visit ?: UUID.randomUUID().toString().also { visit = it }
        profiles.activeProfileId.distinctUntilChanged().collectLatest { profileId ->
            addons.getInstalledAddons().map { installed ->
                installed.firstNotNullOfOrNull { addon ->
                    if (addon.enabled) householdSessionUrl(profileId, addon.id, addon.baseUrl) else null
                }
            }.distinctUntilChanged().collectLatest { url ->
                if (url != null) {
                    watchedItems.observeAllItems(profileId)
                        .map { items ->
                            recentHouseholdWatches(items, System.currentTimeMillis()).map {
                                HouseholdWatch(it.contentType, it.contentId, it.watchedAt)
                            }
                        }
                        .distinctUntilChanged()
                        .flowOn(Dispatchers.Default)
                        .collectLatest { watches -> refresh(profileId, url, HouseholdSession(session, watches)) }
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
                    addons.acceptHouseholdManifest(manifest.toDomain(base))
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
