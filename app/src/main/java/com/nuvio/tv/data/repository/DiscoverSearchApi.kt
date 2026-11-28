package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.MovieDistributionDates
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.AddonRepository
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.IOException
import java.net.Proxy
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val watchedMovies: List<String>, val watchedSeries: List<String>, val cursor: String? = null,
    val releaseWindow: String = "any"
)

enum class DiscoverSource(val label: String, val url: String) {
    JUSTWATCH("JustWatch", "https://www.justwatch.com/us")
}

data class DiscoverSearchPage(
    val items: List<MetaPreview>, val total: Int, val nextCursor: String?, val sources: List<DiscoverSource> = emptyList(),
    val releasePending: Boolean = false, val movieReleases: Map<String, MovieRelease> = emptyMap()
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
    val offset: Int, val snapshot: String?, val limit: Int = 60, val releaseWindow: String? = null
)

@JsonClass(generateAdapter = true)
internal data class DiscoverReply(
    val metas: List<MetaPreviewDto>, val total: Int, val snapshot: String,
    @Json(name = "next_offset") val nextOffset: Int?, val coverage: String? = null,
    val releasePending: Boolean = false, val movieReleases: Map<String, MovieRelease> = emptyMap()
)

internal const val MOVIE_RELEASE_BATCH_LIMIT = 100
private val movieReleaseIdPattern = Regex("(?:tt[0-9]{1,12}|tmdb:[1-9][0-9]{0,11})")
internal val MetaPreview.movieReleaseId: String?
    get() = movieReleaseId(type, id, imdbId)
internal val Meta.movieReleaseId: String?
    get() = movieReleaseId(type, id, imdbId)

private fun movieReleaseId(type: ContentType, id: String, imdbId: String?): String? {
    if (type != ContentType.MOVIE) return null
    return id.takeIf(movieReleaseIdPattern::matches) ?: imdbId?.takeIf(movieReleaseIdPattern::matches)
}

@JsonClass(generateAdapter = true)
data class MovieRelease(
    val status: String,
    val homeDate: String? = null,
    val cinemaDate: String? = null,
    val ageRating: String? = null
)

internal fun movieReleaseFromDates(
    local: MovieDistributionDates?,
    household: MovieRelease? = null,
    today: LocalDate? = null
): MovieRelease? {
    if (local == null || (local.cinemaDate == null && local.homeDate == null)) return household
    val dates = local.merge(household?.let { MovieDistributionDates(it.cinemaDate, it.homeDate) })
    val asOf = (today ?: LocalDate.now(ZoneOffset.UTC)).toString()
    val phase = when {
        household?.status == "home" || dates.homeDate?.let { it <= asOf } == true -> "home"
        household?.status == "cinema" || dates.cinemaDate?.let { it <= asOf } == true -> "cinema"
        else -> "upcoming"
    }
    return MovieRelease(phase, dates.homeDate, dates.cinemaDate, household?.ageRating)
}

@JsonClass(generateAdapter = true)
internal data class MovieReleaseRequest(val ids: List<String>)

@JsonClass(generateAdapter = true)
internal data class MovieReleaseReply(val releases: Map<String, MovieRelease>, val pending: Boolean)

private interface PrivateDiscoverEndpoint {
    @POST suspend fun libraryFacts(@Url url: HttpUrl, @Body request: DiscoverLibraryRequest): Response<DiscoverLibraryReply>
    @POST suspend fun releases(@Url url: HttpUrl, @Body request: MovieReleaseRequest): Response<MovieReleaseReply>
    @POST suspend fun page(@Url url: HttpUrl, @Body request: DiscoverRequestBody): Response<DiscoverReply>
}

private val snapshotPattern = Regex("[A-Za-z0-9._-]{1,256}")

internal fun discoverYearBounds(period: String, year: Int): Pair<Int?, Int?> = when (period) {
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

internal fun DiscoverSearchRequest.wire(year: Int): DiscoverRequestBody {
    val span = discoverYearBounds(period, year)
    val snapshot = cursor?.substringBefore(':')
    val offset = if (cursor == null) 0 else cursor.substringAfter(':', "").toIntOrNull()
        ?.takeIf { it > 0 && snapshot != null && snapshotPattern.matches(snapshot) }
        ?: throw IOException("Invalid Discover continuation")
    return DiscoverRequestBody(type, catalog, genre, keyword, span.first, span.second, minRating, watch, sort,
        hideUnreleased, if (watch == "any") emptyList() else watchedMovies,
        if (watch == "any") emptyList() else watchedSeries, offset, snapshot, releaseWindow = releaseWindow.takeUnless { it == "any" })
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
        (request.type != "any" && items.any { it.apiType != request.type }) ||
        (request.releaseWindow != null && items.any { it.type != ContentType.MOVIE })) {
        throw IOException("Invalid Discover title page")
    }
    val sources = DiscoverSource.entries.filter { source -> coverage?.contains(source.label, ignoreCase = true) == true }
    val localReleases = if (request.watch == "any" || movieReleases.isEmpty()) emptyMap() else {
        val pageIds = items.mapNotNullTo(hashSetOf()) { it.movieReleaseId }
        movieReleases.filterKeys { it in pageIds }
    }
    return DiscoverSearchPage(items, total, nextOffset?.let { "$snapshot:$it" }, sources, releasePending, localReleases)
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

    internal val movieReleaseProfile: Flow<Int?>
        get() = combine(profiles.activeProfileId, profiles.activeProfileReady, addons.getInstalledAddons()) { profile, ready, installed ->
            profile.takeIf { ready && installed.any { it.enabled && discoverSearchUrl(profile, it.id, it.baseUrl) != null } }
        }.distinctUntilChanged()

    private fun verifyProfile(profile: Int) {
        if (profiles.activeProfileId.value != profile || !profiles.activeProfileReady.value)
            throw IOException("Discover profile changed")
    }

    private suspend fun privateDiscoverUrl(profile: Int): HttpUrl {
        // The profile store loads asynchronously at startup. Its initial profile ID
        // is a placeholder until readiness is confirmed.
        if (withTimeoutOrNull(5_000) { profiles.activeProfileReady.first { it } } != true)
            throw IOException("Discover profile unavailable")
        verifyProfile(profile)
        val url = withTimeoutOrNull(5_000) {
            addons.getInstalledAddons().map { installed ->
                installed.firstNotNullOfOrNull { addon ->
                    if (addon.enabled) discoverSearchUrl(profile, addon.id, addon.baseUrl) else null
                }
            }.filterNotNull().firstOrNull()
        } ?: throw IOException("Private Discover service is unavailable")
        verifyProfile(profile)
        return url
    }

    internal suspend fun movieReleases(profile: Int, ids: List<String>): MovieReleaseReply {
        require(ids.size in 1..MOVIE_RELEASE_BATCH_LIMIT && ids.all(movieReleaseIdPattern::matches))
        val url = privateDiscoverUrl(profile).newBuilder().setPathSegment(1, "movie-releases.json").build()
        val response = endpoint.releases(url, MovieReleaseRequest(ids))
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            throw IOException("Movie releases unavailable")
        }
        verifyProfile(profile)
        return response.body() ?: throw IOException("Empty movie release response")
    }

    suspend fun libraryFacts(
        profile: Int, entries: List<LibraryEntry>, keyword: String = "",
        watchedMovies: Set<String> = emptySet(), watchedSeries: Set<String> = emptySet()
    ): DiscoverLibrarySnapshot {
        suspend fun readSnapshot() = collectDiscoverLibraryFacts(entries, keyword, watchedMovies, watchedSeries) { request ->
            val url = privateDiscoverUrl(profile).newBuilder().setPathSegment(1, "library-facts.json").build()
            val response = endpoint.libraryFacts(url, request)
            if (!response.isSuccessful) {
                val failure = response.errorBody().discoverFailure(moshi)
                throw DiscoverServiceException(failure)
            }
            verifyProfile(profile)
            response.body() ?: throw IOException("Empty Discover library response")
        }
        return try {
            readSnapshot()
        } catch (_: DiscoverSnapshotExpiredException) {
            readSnapshot()
        }
    }

    suspend fun search(request: DiscoverSearchRequest): DiscoverSearchPage {
        val profile = request.profileId
        val url = privateDiscoverUrl(profile)
        val wire = request.wire(LocalDate.now().year)
        val response = endpoint.page(url, wire)
        if (!response.isSuccessful) {
            val failure = if (response.code() == 503) response.errorBody().discoverFailure(moshi) else {
                response.errorBody()?.close()
                DiscoverFailure.OTHER
            }
            verifyProfile(profile)
            if (response.code() == 409) throw DiscoverSnapshotExpiredException()
            throw DiscoverServiceException(failure)
        }
        verifyProfile(profile)
        return response.body()?.page(wire) ?: throw IOException("Empty Discover response")
    }
}
