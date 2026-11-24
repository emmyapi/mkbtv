package com.nuvio.tv.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverVoiceQueryTest {
    private val defaults = SearchUiState()
    private fun speak(phrase: String, state: SearchUiState = defaults) = DiscoverVoiceQuery.apply(phrase, state)

    @Test fun spokenDanishAndEnglishSetTheSameControlsAsTheFilters() {
        val expected = defaults.copy(discoverKeyword = "Spiderman", selectedDiscoverType = "series",
            discoverPeriod = DiscoverPeriod.DECADE_1990, discoverSort = DiscoverSort.TOP_RATED)
        for (phrase in listOf(
            "søg efter Spiderman serie fra 90erne og sorter efter højest rating",
            "Vis mig Spiderman serier fra 90’erne, sortér efter højeste bedømmelse",
            "find Spiderman series from the nineties and sort them by highest rated",
            "search for Spiderman TV shows from the 1990's sorted by rating",
            "Kan du søge efter Spiderman serie fra halvfemserne og sortere efter højeste rating?"
        )) assertEquals(phrase, expected, speak(phrase))
    }

    @Test fun genreTypeServiceAndCastCanBeCombinedInBothLanguages() {
        val expected = defaults.copy(discoverKeyword = "Sigourney Weaver", selectedDiscoverType = "movie",
            selectedDiscoverGenre = "Science Fiction", selectedDiscoverCatalogKey = "disney-plus")
        assertEquals(expected, speak("find science fiction movies starring Sigourney Weaver on Disney Plus"))
        assertEquals(expected, speak("vis mig sciencefictionfilm med Sigourney Weaver på Disney+"))
        assertEquals(defaults.copy(discoverKeyword = "Tom Hanks", discoverSort = DiscoverSort.TOP_RATED,
            selectedDiscoverType = "movie"), speak("show me the top rated movies starring Tom Hanks"))
        assertEquals(defaults.copy(discoverKeyword = "Batman", selectedDiscoverType = "movie",
            discoverPeriod = DiscoverPeriod.DECADE_1980), speak("søg efter Batman film fra firserne"))
    }

    @Test fun aSpokenRefinementOnlyUpdatesTheNamedFilters() {
        val previous = defaults.copy(discoverKeyword = "Spider-Man", selectedDiscoverType = "series",
            selectedDiscoverCatalogKey = "netflix", selectedDiscoverGenre = "Animation",
            discoverSort = DiscoverSort.OLDEST, discoverPeriod = DiscoverPeriod.DECADE_1990)
        assertEquals(previous.copy(discoverSort = DiscoverSort.TOP_RATED), speak("sorter efter højeste rating", previous))
        assertEquals(previous.copy(selectedDiscoverCatalogKey = "hbo"), speak("kun på HBO Max", previous))
        assertEquals(previous.copy(discoverWatch = DiscoverWatch.UNWATCHED), speak("vis mig kun usete", previous))
        assertEquals(previous.copy(discoverMinRating = 8, discoverWatch = DiscoverWatch.UNWATCHED),
            speak("som jeg ikke har set og med en IMDb-rating på mindst 8", previous))
        assertEquals(previous.copy(discoverPeriod = DiscoverPeriod.LAST_FIVE_YEARS), speak("from the last 5 years", previous))
        assertEquals(previous.copy(discoverKeyword = "WALL·E"), speak("WALL·E", previous))
    }

    @Test fun genreTypeAndSortVocabularyInsideATitleRemainSearchable() {
        for (title in listOf("American Horror Story", "A Series of Unfortunate Events", "The LEGO Movie",
            "Action Jackson", "War of the Worlds", "The Truman Show", "Set It Up", "Mad Max", "Max", "Spider-Man: The Animated Series")) {
            assertEquals(title, defaults.copy(discoverKeyword = title), speak(title))
        }
        assertEquals(defaults.copy(discoverKeyword = "Popular"), speak("søg efter titlen Popular"))
        assertEquals(defaults.copy(discoverKeyword = "Drama"), speak("search for \"Drama\""))
    }

    @Test fun anExplicitNewFilterSearchReplacesTheKeyword() {
        val previous = defaults.copy(discoverKeyword = "Blade Runner", discoverSort = DiscoverSort.OLDEST)
        assertEquals(previous.copy(discoverKeyword = "", selectedDiscoverType = "movie", selectedDiscoverGenre = "Comedy",
            selectedDiscoverCatalogKey = "netflix"), speak("vis mig komediefilm på Netflix", previous))
        assertEquals(previous.copy(selectedDiscoverGenre = "Drama"), speak("genre drama", previous))
    }

    @Test fun changesRespectCatalogAvailabilityAndResetCommands() {
        val previous = defaults.copy(selectedDiscoverType = "series", selectedDiscoverCatalogKey = "airing-today",
            discoverKeyword = "The Bear", discoverPeriod = DiscoverPeriod.DECADE_2020)
        assertEquals(previous.copy(selectedDiscoverType = "movie", selectedDiscoverCatalogKey = "all"), speak("movies", previous))
        assertEquals(previous.copy(discoverKeyword = ""), speak("ryd søgeord", previous))
        assertEquals(defaults, speak("nulstil alle filtre", previous))
        assertEquals(previous, speak("  ", previous))
    }
}
