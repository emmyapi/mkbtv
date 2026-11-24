package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.AddonRepository
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.IOException
import java.net.Proxy
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

data class DiscoverSearchRequest(
    val profileId: Int, val type: String, val catalog: String, val genre: String?, val keyword: String,
    val period: String, val minRating: Int?, val watch: String, val sort: String, val hideUnreleased: Boolean,
    val watchedMovies: List<String>, val watchedSeries: List<String>, val cursor: String? = null
)

enum class DiscoverSource(val label: String, val url: String) {
    JUSTWATCH("JustWatch", "https://www.justwatch.com/us")
}

data class DiscoverSearchPage(
    val items: List<MetaPreview>, val total: Int, val nextCursor: String?, val sources: List<DiscoverSource> = emptyList()
)

enum class DiscoverFailure { CATALOG, RANKING, INDEX, WATCH, BUSY, OTHER }

internal class DiscoverServiceException(val failure: DiscoverFailure) : IOException("Discover service unavailable")

@JsonClass(generateAdapter = true)
internal data class DiscoverErrorBody(val error: String)

internal fun ResponseBody?.discoverFailure(moshi: Moshi): DiscoverFailure {
    val code = this?.use {
        val source = it.source()
        if (source.request(257)) null else runCatching {
            moshi.adapter(DiscoverErrorBody::class.java).fromJson(source)?.error
        }.getOrNull()
    }
    return when (code) {
        "catalog_unavailable" -> DiscoverFailure.CATALOG
        "ranking_unavailable" -> DiscoverFailure.RANKING
        "index_unavailable" -> DiscoverFailure.INDEX
        "watch_unavailable" -> DiscoverFailure.WATCH
        "busy" -> DiscoverFailure.BUSY
        else -> DiscoverFailure.OTHER
    }
}

class DiscoverSnapshotExpiredException : IOException("Discover snapshot changed")

@JsonClass(generateAdapter = true)
internal data class DiscoverRequestBody(
    val type: String, val catalog: String, val genre: String?, val keyword: String,
    @Json(name = "year_min") val yearMin: Int?, @Json(name = "year_max") val yearMax: Int?,
    @Json(name = "min_rating") val minRating: Int?, val watch: String, val sort: String,
    @Json(name = "hide_unreleased") val hideUnreleased: Boolean,
    @Json(name = "watched_movies") val watchedMovies: List<String>,
    @Json(name = "watched_series") val watchedSeries: List<String>,
    val offset: Int, val snapshot: String?, val limit: Int = 60
)

@JsonClass(generateAdapter = true)
internal data class DiscoverReply(
    val metas: List<MetaPreviewDto>, val total: Int, val snapshot: String,
    @Json(name = "next_offset") val nextOffset: Int?, val coverage: String? = null
)

private interface PrivateDiscoverEndpoint {
    @POST suspend fun page(@Url url: HttpUrl, @Body request: DiscoverRequestBody): Response<DiscoverReply>
}

private val snapshotPattern = Regex("[A-Za-z0-9._-]{1,256}")

internal fun DiscoverSearchRequest.wire(year: Int): DiscoverRequestBody {
    val span: Pair<Int?, Int?> = when (period) {
        "any" -> null to null
        "this_year" -> year to year
        "last_five_years" -> year - 4 to year
        "before_1980" -> null to 1979
        "before_2010" -> null to 2009
        "decade_1980", "decade_1990", "decade_2000", "decade_2010", "decade_2020" -> {
            val firstYear = period.takeLast(4).toInt()
            firstYear to firstYear + 9
        }
        else -> throw IOException("Unknown Discover release period")
    }
    val snapshot = cursor?.substringBefore(':')
    val offset = if (cursor == null) 0 else cursor.substringAfter(':', "").toIntOrNull()
        ?.takeIf { it > 0 && snapshot != null && snapshotPattern.matches(snapshot) }
        ?: throw IOException("Invalid Discover continuation")
    return DiscoverRequestBody(type, catalog, genre, keyword, span.first, span.second, minRating, watch, sort,
        hideUnreleased, if (watch == "any") emptyList() else watchedMovies,
        if (watch == "any") emptyList() else watchedSeries, offset, snapshot)
}

internal fun DiscoverReply.page(request: DiscoverRequestBody): DiscoverSearchPage {
    if (request.snapshot != null && request.snapshot != snapshot) throw DiscoverSnapshotExpiredException()
    if (!snapshotPattern.matches(snapshot) || total < 0 || request.offset > total ||
        metas.size != minOf(request.limit, total - request.offset)) throw IOException("Invalid Discover page")
    val end = request.offset + metas.size
    if (nextOffset != (if (end < total) end else null)) throw IOException("Invalid Discover continuation")
    val items = metas.map {
        if (it.type != "movie" && it.type != "series") throw IOException("Invalid Discover title type")
        it.toDomainOrNull(it.type) ?: throw IOException("Invalid Discover title")
    }
    if (items.distinctBy { it.apiType to it.id }.size != items.size ||
        (request.type != "any" && items.any { it.apiType != request.type })) {
        throw IOException("Invalid Discover title page")
    }
    val sources = DiscoverSource.entries.filter { source -> coverage?.contains(source.label, ignoreCase = true) == true }
    return DiscoverSearchPage(items, total, nextOffset?.let { "$snapshot:$it" }, sources)
}

internal fun discoverSearchUrl(
    profileId: Int, addonId: String, baseUrl: String, origin: String = BuildConfig.MKBTV_DISCOVERY_ORIGIN
): HttpUrl? =
    householdSessionUrl(profileId, addonId, baseUrl, origin)?.newBuilder()?.setPathSegment(1, "discover.json")?.build()

@Singleton
class DiscoverSearchApi @Inject constructor(
    private val profiles: ProfileManager,
    private val addons: AddonRepository,
    private val moshi: Moshi
) {
    // Watched IDs are private. The destination is a profile's installed LAN addon; no proxies, redirects or telemetry.
    private val endpoint = Retrofit.Builder().baseUrl("http://localhost/")
        .client(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build())
        .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(PrivateDiscoverEndpoint::class.java)

    suspend fun search(request: DiscoverSearchRequest): DiscoverSearchPage {
        val profile = request.profileId
        fun verifyProfile() {
            if (profiles.activeProfileId.value != profile || !profiles.activeProfileReady.value)
                throw IOException("Discover profile changed")
        }
        // The profile store loads asynchronously at startup. Its initial profile ID
        // is a placeholder until readiness is confirmed.
        if (withTimeoutOrNull(5_000) { profiles.activeProfileReady.first { it } } != true)
            throw IOException("Discover profile unavailable")
        verifyProfile()
        val url = withTimeoutOrNull(5_000) {
            addons.getInstalledAddons().map { installed ->
                installed.firstNotNullOfOrNull { addon ->
                    if (addon.enabled) discoverSearchUrl(profile, addon.id, addon.baseUrl) else null
                }
            }.filterNotNull().firstOrNull()
        } ?: throw IOException("Private Discover service is unavailable")
        verifyProfile()
        val wire = request.wire(LocalDate.now().year)
        val response = endpoint.page(url, wire)
        if (!response.isSuccessful) {
            val failure = if (response.code() == 503) response.errorBody().discoverFailure(moshi) else {
                response.errorBody()?.close()
                DiscoverFailure.OTHER
            }
            verifyProfile()
            if (response.code() == 409) throw DiscoverSnapshotExpiredException()
            throw DiscoverServiceException(failure)
        }
        verifyProfile()
        return response.body()?.page(wire) ?: throw IOException("Empty Discover response")
    }
}
