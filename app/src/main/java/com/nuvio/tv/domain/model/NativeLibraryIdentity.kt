package com.nuvio.tv.domain.model

import com.nuvio.tv.data.mediaserver.ServerItemRef

/** Exact source IDs own the saved row. Public title IDs can locate it from another surface. */
internal class NativeLibraryIdentity(
    private val id: String,
    private val type: String,
    imdbId: String? = null,
    tmdbId: Int? = null
) {
    val imdb = normalizedImdb(id) ?: normalizedImdb(imdbId)
    val tmdb = tmdbPrefix.matchEntire(id.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        ?: tmdbId?.takeIf { it > 0 }

    fun find(items: List<SavedLibraryItem>, forEdit: Boolean = false): SavedLibraryItem? {
        val raw = id.trim()
        val rawType = type.trim()
        items.firstOrNull { it.id.trim() == raw && it.type.trim().equals(rawType, ignoreCase = true) }?.let { return it }

        val kind = mediaKind(type) ?: return null
        val candidates = items.asSequence().filter { mediaKind(it.type) == kind }
        val sameId = candidates.filter { it.id.trim() == raw }.toList()
        if (sameId.isNotEmpty()) return unique(sameId, forEdit)

        if (ServerItemRef.isServerId(raw) || (imdb == null && tmdb == null)) return null
        val matches = candidates.filterNot { ServerItemRef.isServerId(it.id) }.filter { item ->
            val other = item.nativeIdentity()
            val imdbConflict = imdb != null && other.imdb != null && imdb != other.imdb
            val tmdbConflict = tmdb != null && other.tmdb != null && tmdb != other.tmdb
            !imdbConflict && !tmdbConflict &&
                ((imdb != null && imdb == other.imdb) || (tmdb != null && tmdb == other.tmdb))
        }.take(2).toList()
        return unique(matches, forEdit)
    }

    private fun unique(matches: List<SavedLibraryItem>, forEdit: Boolean): SavedLibraryItem? {
        require(!forEdit || matches.size <= 1) { "Open the saved title from its playlist to edit these memberships" }
        return matches.singleOrNull()
    }

    private companion object {
        val imdbPattern = Regex("(?:imdb:)?(tt[0-9]+)", RegexOption.IGNORE_CASE)
        val tmdbPrefix = Regex("tmdb:([0-9]+)", RegexOption.IGNORE_CASE)

        fun normalizedImdb(id: String?) = id?.trim()?.let { imdbPattern.matchEntire(it) }?.groupValues?.get(1)?.lowercase()

        fun mediaKind(type: String): String? = when (type.trim().lowercase()) {
            "movie", "film" -> "movie"
            "series", "tv", "show", "anime" -> "series"
            else -> null
        }
    }
}

internal fun SavedLibraryItem.nativeIdentity() = NativeLibraryIdentity(id, type, imdbId, tmdbId)
