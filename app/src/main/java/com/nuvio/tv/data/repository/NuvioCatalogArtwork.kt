package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.streams.supportsResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

internal const val NUVIO_CATALOG_ID = "com.nuvio.tmdb.catalogs"

/** Search catalog artwork and series badge fields, before profile-specific presentation. */
@Singleton
class NuvioCatalogArtwork @Inject constructor(
    private val addons: AddonRepository,
    private val metadata: MetaRepository
) {
    private data class Key(val baseUrl: String, val type: String, val id: String)
    private data class Art(
        val poster: String?, val backdrop: String?, val landscape: String?, val logo: String?, val imdb: String?,
        val ageRating: String?, val status: String?
    ) {
        val hasSeriesBadge get() = !ageRating.isNullOrBlank() || !status.isNullOrBlank()
    }
    private data class CachedArtwork(val art: Art?, val expires: Long, val badgeCheckedUntil: Long = 0)

    private val cache = object : LinkedHashMap<Key, CachedArtwork>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CachedArtwork>?) = size > 1000
    }
    private val lookups = Semaphore(3)
    private val imdbId = Regex("tt[0-9]+")
    private val tmdbId = Regex("tmdb:[0-9]+")

    internal fun record(baseUrl: String, items: List<MetaPreview>) {
        synchronized(cache) {
            for (item in items) {
                val poster = item.poster?.takeIf { it.isNotBlank() } ?: continue
                val imdb = item.imdbId ?: item.id.takeIf(imdbId::matches)
                val type = if (item.type == ContentType.TV) "series" else item.apiType
                val key = Key(baseUrl, type, item.id)
                val imdbKey = imdb?.let { Key(baseUrl, type, it) }
                val now = System.currentTimeMillis()
                val prior = (cache[key] ?: imdbKey?.let { cache[it] })?.takeIf { it.badgeCheckedUntil > now }
                val art = Art(poster, item.background, item.landscapePoster, item.logo, imdb,
                    item.ageRating ?: prior?.art?.ageRating, item.status ?: prior?.art?.status)
                val entry = CachedArtwork(art, now + 3_600_000, prior?.badgeCheckedUntil ?: 0)
                cache[key] = entry
                if (imdbKey != null) cache[imdbKey] = entry
            }
        }
    }

    suspend fun resolve(item: MetaPreview, fillSeriesBadge: Boolean = false): MetaPreview? {
        val type = when (item.type) {
            ContentType.MOVIE -> "movie"
            ContentType.SERIES, ContentType.TV -> "series"
            else -> return null
        }
        val publicId = item.imdbId?.takeIf(imdbId::matches)
            ?: item.id.takeIf { imdbId.matches(it) || tmdbId.matches(it) }
        val badgeLookup = fillSeriesBadge && type == "series"
        if (publicId == null && (!badgeLookup || item.sourceAddonBaseUrl == null)) return null
        // Installed manifests can arrive after the household Discover results on a cold start.
        val addon = withTimeoutOrNull(5_000) {
            addons.getInstalledAddons().mapNotNull { installed ->
                installed.firstOrNull { publicId != null && it.enabled && it.id == NUVIO_CATALOG_ID }
                    ?: installed.firstOrNull {
                        badgeLookup && it.enabled && it.baseUrl == item.sourceAddonBaseUrl &&
                            it.supportsResource("meta", type, item.id)
                    }
            }.firstOrNull()
        } ?: return null
        // Provider-native identifiers belong to the addon that supplied the search result.
        val id = if (addon.id == NUVIO_CATALOG_ID) publicId ?: item.id else item.id
        val key = Key(addon.baseUrl, type, id)
        val art = lookups.withPermit {
            val now = System.currentTimeMillis()
            val cached = synchronized(cache) { cache[key] }?.takeIf { it.expires > now }
            val needsBadge = badgeLookup &&
                ((item.ageRating.isNullOrBlank() && cached?.art?.ageRating.isNullOrBlank()) ||
                 (item.status.isNullOrBlank() && cached?.art?.status.isNullOrBlank()))
            if (cached != null && (!needsBadge || cached.badgeCheckedUntil > now)) return@withPermit cached.art

            suspend fun fetchMeta() = metadata.getMeta(addon.baseUrl, type, id).first { it !is NetworkResult.Loading }
            // Start the badge timeout after acquiring a slot so a busy viewport still fills.
            val result = if (badgeLookup) withTimeoutOrNull(6_000) { fetchMeta() } else fetchMeta()
            val meta = (result as? NetworkResult.Success)?.data
            // The provider's exact TMDB-ID endpoint returns its canonical IMDb ID.
            val matches = meta != null && (meta.apiType == type || (type == "series" && meta.apiType == "tv")) &&
                (id == meta.id || id == meta.imdbId || (addon.id == NUVIO_CATALOG_ID && tmdbId.matches(id) && imdbId.matches(meta.id)))
            synchronized(cache) {
                // A catalog row can supply its poster while the series detail request is in flight.
                val prior = cache[key]?.takeIf { it.expires > now }?.art
                val resolved = if (meta != null && matches) {
                    Art(
                        poster = prior?.poster ?: meta.poster?.takeIf { it.isNotBlank() },
                        backdrop = prior?.backdrop ?: meta.background,
                        landscape = prior?.landscape ?: meta.landscapePoster,
                        logo = prior?.logo ?: meta.logo,
                        imdb = prior?.imdb ?: meta.imdbId ?: meta.id.takeIf(imdbId::matches),
                        ageRating = prior?.ageRating ?: if (type == "series") meta.ageRating else null,
                        status = prior?.status ?: if (type == "series") meta.status else null
                    ).takeIf { it.poster != null || it.hasSeriesBadge }
                } else prior
                val expires = now + if (resolved != null) 3_600_000 else 60_000
                val entry = CachedArtwork(resolved, expires, if (matches) expires else now + 60_000)
                cache[key] = entry
                resolved?.imdb?.let { cache[Key(addon.baseUrl, type, it)] = entry }
                resolved
            }
        } ?: return null
        if (art.poster == null && !fillSeriesBadge) return null
        return item.copy(
            poster = art.poster ?: item.poster, rawPosterUrl = art.poster ?: item.rawPosterUrl,
            posterShape = if (art.poster != null) PosterShape.POSTER else item.posterShape,
            background = art.backdrop ?: item.background, landscapePoster = art.landscape ?: item.landscapePoster,
            logo = art.logo ?: item.logo, imdbId = art.imdb ?: item.imdbId,
            ageRating = item.ageRating?.takeIf { it.isNotBlank() } ?: art.ageRating,
            status = item.status?.takeIf { it.isNotBlank() } ?: art.status
        )
    }
}
