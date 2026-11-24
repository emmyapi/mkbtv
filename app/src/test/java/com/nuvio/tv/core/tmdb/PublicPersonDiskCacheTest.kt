package com.nuvio.tv.core.tmdb

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PublicPersonDiskCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun reopensVerifiedCreditsWithoutMixingNamesakesOrSavingPartialResults() {
        val key = "Example:false:tt123"
        val film = MetaPreview(id = "tt123", type = ContentType.MOVIE, name = "Origin", poster = null,
            posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
            releaseInfo = "2012", imdbRating = null, genres = emptyList())
        val person = PersonDetail(0, "Example", "A biography", null, null, null, null, "Actor",
            listOf(film), emptyList())
        val directory = folder.newFolder()
        fun cache() = PublicPersonDiskCache(directory, NetworkModule.provideMoshi())
        cache().put(key, person)
        cache().put(key, person.copy(partialFilmography = true, movieCredits = emptyList()))
        assertEquals(person, cache().get(key))
        assertNull(cache().get("Example:false:tt456"))
        assertNull(cache().get("Example:true:tt123"))
        directory.listFiles()!!.single().setLastModified(1)
        assertNull(cache().get(key))
    }
}
