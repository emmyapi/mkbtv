package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
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

/** Raw Search artwork, before a profile applies its custom poster pattern. */
@Singleton
class NuvioCatalogArtwork @Inject constructor(
    private val addons: AddonRepository,
    private val metadata: MetaRepository
) {
    private data class Key(val baseUrl: String, val type: String, val id: String)
    private data class Art(val poster: String, val backdrop: String?, val landscape: String?, val logo: String?, val imdb: String?)
    private data class CachedArtwork(val art: Art?, val expires: Long)

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
                val art = Art(poster, item.background, item.landscapePoster, item.logo, imdb)
                val entry = CachedArtwork(art, System.currentTimeMillis() + 3_600_000)
                cache[Key(baseUrl, item.apiType, item.id)] = entry
                if (imdb != null) cache[Key(baseUrl, item.apiType, imdb)] = entry
            }
        }
    }

    suspend fun resolve(item: MetaPreview): MetaPreview? {
        val type = when (item.type) {
            ContentType.MOVIE -> "movie"
            ContentType.SERIES, ContentType.TV -> "series"
            else -> return null
        }
        val id = item.imdbId?.takeIf(imdbId::matches)
            ?: item.id.takeIf { imdbId.matches(it) || tmdbId.matches(it) } ?: return null
        // Installed manifests can arrive after the household Discover results on a cold start.
        val addon = withTimeoutOrNull(5_000) {
            addons.getInstalledAddons().mapNotNull { installed ->
                installed.firstOrNull { it.enabled && it.id == NUVIO_CATALOG_ID }
            }.firstOrNull()
        } ?: return null
        val key = Key(addon.baseUrl, type, id)
        val art = lookups.withPermit {
            synchronized(cache) { cache[key] }?.takeIf { it.expires > System.currentTimeMillis() }?.let {
                return@withPermit it.art
            }
            val result = metadata.getMeta(addon.baseUrl, type, id).first { it !is NetworkResult.Loading }
            val meta = (result as? NetworkResult.Success)?.data
            // The provider's exact TMDB-ID endpoint returns its canonical IMDb ID.
            val matches = meta != null && meta.apiType == type &&
                (id == meta.id || id == meta.imdbId || (tmdbId.matches(id) && imdbId.matches(meta.id)))
            val resolved = if (meta != null && matches && !meta.poster.isNullOrBlank()) {
                Art(meta.poster, meta.background, meta.landscapePoster, meta.logo, meta.imdbId ?: meta.id.takeIf(imdbId::matches))
            } else null
            synchronized(cache) {
                val entry = CachedArtwork(resolved, System.currentTimeMillis() + if (resolved != null) 3_600_000 else 60_000)
                cache[key] = entry
                resolved?.imdb?.let { cache[Key(addon.baseUrl, type, it)] = entry }
            }
            resolved
        } ?: return null
        return item.copy(poster = art.poster, rawPosterUrl = art.poster, posterShape = PosterShape.POSTER,
            background = art.backdrop, landscapePoster = art.landscape, logo = art.logo, imdbId = art.imdb ?: item.imdbId)
    }
}
