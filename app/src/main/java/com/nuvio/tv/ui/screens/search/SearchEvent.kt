package com.nuvio.tv.ui.screens.search

sealed interface SearchEvent {
    data class QueryChanged(val query: String) : SearchEvent
    data object SubmitSearch : SearchEvent
    /** Moving from the text input to the results confirms the current search (see #2928 review). */
    data object RememberSearchFromTextInput : SearchEvent
    data object ClearRecentSearches : SearchEvent
    data class RemoveRecentSearch(val query: String) : SearchEvent

    data class LoadMoreCatalog(val rowKey: String) : SearchEvent

    data class SelectDiscoverType(val type: String) : SearchEvent
    data class SelectDiscoverCatalog(val catalogKey: String) : SearchEvent
    data class SelectDiscoverGenre(val genre: String?) : SearchEvent
    data class SetDiscoverKeyword(val keyword: String) : SearchEvent
    data class ApplyDiscoverVoiceQuery(val phrase: String) : SearchEvent
    data class SelectSearchReleaseWindow(val window: ReleaseWindow) : SearchEvent
    data class SelectDiscoverReleaseWindow(val window: ReleaseWindow) : SearchEvent
    data class SelectDiscoverPeriod(val period: DiscoverPeriod) : SearchEvent
    data class SelectDiscoverMinRating(val rating: Int?) : SearchEvent
    data class SelectDiscoverWatch(val watch: DiscoverWatch) : SearchEvent
    data class SelectDiscoverSort(val sort: DiscoverSort) : SearchEvent
    data object ResetDiscoverFilters : SearchEvent
    data object RetryDiscover : SearchEvent
    data object LoadNextDiscoverResults : SearchEvent

    data object Retry : SearchEvent
}
