package com.nuvio.tv.ui.screens.search

import java.util.Locale

/** Apply one utterance as a single edit to the same criteria used by the Discover controls. */
internal object DiscoverVoiceQuery {
    private val command = regex("""^(?:(?:can you|could you|kan du|vil du) )?(?:please )?(?:search for|look for|finde?|show me|show|søg(?:e)? (?:efter|på)|vis(?:e)?(?: mig)?|jeg vil gerne se|I'd like to see) +""")
    private val literalTitle = regex("""^(?:title|keyword|titlen|søgeord)[: ]+(.+)$""")
    private val refinement = regex("""^(?:only|kun) +""")
    private val reset = regex("""(?:reset|clear|nulstil|ryd) (?:all |alle )?(?:filters?|filtre|filtrene)""")
    private val clearKeyword = regex("""(?:clear|remove|ryd|fjern) (?:the |mit )?(?:keyword|søgeord|søgeordet)""")
    private val leadingConnector = regex("""^(?:and|og) +""")
    private val trailingConnector = regex(""" +(?:and|og)$""")
    private val whitespace = Regex("""\s+""")

    private fun regex(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    // Only peel filters from the edges. Words inside a title (e.g. American Horror Story)
    // and their original spelling and punctuation survive.
    private enum class Position { EITHER_EDGE, END, WHOLE }

    private class Rule(
        pattern: String,
        private val position: Position,
        private val preserveSubtitle: Boolean,
        val apply: SearchUiState.(MatchResult) -> SearchUiState
    ) {
        private val start = regex("^(?:$pattern)(?=$|[ ,])")
        private val end = regex("(?<![^ ,])(?:$pattern)$")

        fun match(text: String): MatchResult? {
            if (position == Position.WHOLE) return start.matchEntire(text)
            if (position == Position.EITHER_EDGE) start.find(text)?.let { return it }
            // A labelled subtitle can end with a media description, e.g. "Spider-Man: The Animated Series".
            if (preserveSubtitle && ':' in text) return null
            return end.find(text)
        }
    }

    private class Names<T>(aliases: Map<T, String>) {
        private val values = buildMap {
            for ((value, words) in aliases) {
                words.split('|').forEach { put(it.lowercase(Locale.ROOT), value) }
            }
        }
        val pattern = values.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        operator fun get(spoken: String): T = values.getValue(spoken.lowercase(Locale.ROOT))
    }

    private val types = Names(mapOf(
        "movie" to "film|filmen|filmene|movie|movies|films",
        "series" to "serie|serier|serien|serierne|series|show|shows|tv series|tv-series|tv show|tv shows|tv-serie|tv serie|tv-serier|tv serier"
    ))
    private val services = Names(mapOf(
        DiscoverCatalogOption.DISNEY to "Disney+|Disney Plus",
        DiscoverCatalogOption.NETFLIX to "Netflix",
        DiscoverCatalogOption.HBO to "HBO|HBO Max|Max",
        DiscoverCatalogOption.PRIME_VIDEO to "Prime Video|Amazon Prime Video|Amazon Prime|Prime",
        DiscoverCatalogOption.HULU to "Hulu"
    ))
    private val sorts = Names(mapOf(
        DiscoverSort.TOP_RATED to "highest rating|highest IMDb rating|highest rated|highest-rated|top rating|top rated|top-rated|best rated|best-rated|rating|IMDb rating|højest rating|højeste rating|højeste IMDb-rating|højest ratede|bedst bedømte|bedst bedømt|højest bedømte|højest bedømt|højeste bedømmelse|bedømmelse|bedste rating|højest vurderede",
        DiscoverSort.POPULAR to "popular|popularitet|popularity|most popular|populære|mest populære",
        DiscoverSort.TRENDING to "trending|trends|trender",
        DiscoverSort.NEWEST to "newest|newest first|latest|most recent|nyeste|nyeste først|seneste",
        DiscoverSort.OLDEST to "oldest|oldest first|ældste|ældste først",
        DiscoverSort.TITLE to "title|title ascending|title A to Z|title A-Z|A to Z|A-Z|alphabetically|titel|titel A til Z|A til Z|alfabetisk",
        DiscoverSort.TITLE_DESC to "title descending|title Z to A|title Z-A|Z to A|Z-A|reverse alphabetical|titel Z til A|Z til A|omvendt alfabetisk"
    ))
    private val periods = Names(mapOf(
        DiscoverPeriod.DECADE_1980 to "eighties|firserne|80s|80erne|1980s|1980erne",
        DiscoverPeriod.DECADE_1990 to "nineties|halvfemserne|90s|90erne|1990s|1990erne",
        DiscoverPeriod.DECADE_2000 to "noughties|aughts|nullerne|00s|00erne|2000s|2000erne",
        DiscoverPeriod.DECADE_2010 to "twenty tens|tierne|10erne|2010s|2010erne",
        DiscoverPeriod.DECADE_2020 to "twenty twenties|tyverne|20erne|2020s|2020erne",
        DiscoverPeriod.THIS_YEAR to "this year|i år",
        DiscoverPeriod.LAST_FIVE_YEARS to "last five years|last 5 years|past five years|past 5 years|sidste fem år|sidste 5 år|seneste fem år|seneste 5 år",
        DiscoverPeriod.BEFORE_1980 to "before 1980|før 1980",
        DiscoverPeriod.BEFORE_2010 to "before 2010|før 2010"
    ).mapValues { (_, names) ->
        names.split('|').flatMap { word ->
            val digits = word.takeWhile(Char::isDigit)
            if (digits.isEmpty()) listOf(word) else listOf("", "'", "’", " ", "-").map { separator ->
                digits + separator + word.drop(digits.length)
            }
        }.joinToString("|")
    })

    private val genreTranslations = mapOf(
        "Action & Adventure" to "action og eventyr|action and adventure",
        "Adventure" to "eventyr", "Animation" to "animations|animerede|animated|tegne",
        "Comedy" to "komedie|komedier|comedies", "Crime" to "krimi", "Documentary" to "dokumentar|dokumentarer|documentaries",
        "Family" to "familie", "History" to "historie|historisk|historiske|historical",
        "Horror" to "gyser|gysere|gys", "Music" to "musik", "Mystery" to "mysterium|mysteries",
        "Romance" to "romantik|romantisk|romantiske|romantic", "Science Fiction" to "sci-fi|sci fi|sciencefiction",
        "TV Movie" to "tv-film", "War" to "krig|krigs", "Kids" to "børn|børne", "News" to "nyheder",
        "Sci-Fi & Fantasy" to "sci-fi og fantasy|sci fi and fantasy|sci-fi and fantasy",
        "Soap" to "sæbeopera", "War & Politics" to "krig og politik|war and politics",
        "Biography" to "biografi|biografisk", "Game Show" to "gameshow", "Short" to "kort",
        "Sport" to "sports"
    )
    private val genres = Names(discoverGenres.associateWith { genre ->
        listOfNotNull(genre, genreTranslations[genre]).joinToString("|")
    })

    private const val ONLY = "(?:(?:kun|only) )?"
    private const val PERIOD_PREFIX = "(?:(?:from|in|fra|i) )?(?:(?:the|de) )?"
    private const val KEYWORD_LINK = "(?:starring|with|about|called|named|titled|med titlen|med|om|der hedder|som hedder)"
    private const val SORT_PREFIX = "(?:sort(?:ed)?(?: (?:them|it|the results))?(?: by)?|order(?:ed)? by|sort[eé]r(?:et|e)?(?: (?:dem|det|resultaterne))?(?: efter| på)?)"
    private const val UNWATCHED = "(?:unwatched|unseen|uset|usete|(?:(?:that |which )?I (?:haven['’]t|have not|didn['’]t) (?:seen|see)(?: yet)?|(?:(?:som|der) )?jeg ikke har set(?: endnu)?))"
    private const val WATCHED = "(?:watched|seen|sete|(?:(?:that |which )?I(?:['’]ve| have)? (?:already )?(?:seen|watched)|(?:(?:som|der) )?jeg (?:allerede )?har set))"

    private val rules = buildList {
        fun rule(pattern: String, position: Position = Position.EITHER_EDGE, preserveSubtitle: Boolean = false,
            apply: SearchUiState.(MatchResult) -> SearchUiState) {
            add(Rule(pattern, position, preserveSubtitle, apply))
        }
        val type = "(${types.pattern})"
        val sort = "(${sorts.pattern})"
        val genre = "(${genres.pattern})"
        val service = "(${services.pattern})"
        val period = "(${periods.pattern})"

        rule("$SORT_PREFIX (?:the |den )?$sort") { copy(discoverSort = sorts[it.groupValues[1]]) }
        rule(sort, Position.WHOLE) { copy(discoverSort = sorts[it.value]) }
        rule("(?:highest[- ]rated|top[- ]rated|best[- ]rated|newest first|oldest first|bedst bedømte|højest bedømte|ældste først|nyeste først)", Position.END) {
            copy(discoverSort = sorts[it.value])
        }
        rule("(?:(?:the|de) )?$sort(?= (?:$type|$genre))") { copy(discoverSort = sorts[it.groupValues[1]]) }

        rule("$ONLY(?:on |på |from |fra |hos |catalog |katalog )$service") { copy(selectedDiscoverCatalogKey = services[it.groupValues[1]].key) }
        rule("$ONLY(Netflix|HBO(?: Max)?|Disney(?: Plus|\\+)|(?:Amazon )?Prime Video|Amazon Prime|Hulu)", Position.WHOLE) {
            copy(selectedDiscoverCatalogKey = services[it.groupValues[1]].key)
        }
        rule("$PERIOD_PREFIX$period") { copy(discoverPeriod = periods[it.groupValues[1]]) }
        rule("(?:all services|any service|alle tjenester|alle tjenesterne|all catalogs|alle kataloger)") {
            copy(selectedDiscoverCatalogKey = DiscoverCatalogOption.ALL.key)
        }
        rule("(?:any year|all years|alle år)") { copy(discoverPeriod = DiscoverPeriod.ANY) }
        rule("(?:all genres|any genre|alle genrer)") { copy(selectedDiscoverGenre = null) }
        rule("(?:film og serier|movies and (?:series|shows)|alle typer|all types)") { copy(selectedDiscoverType = DISCOVER_TYPE_ANY) }

        rule("$ONLY$UNWATCHED") { copy(discoverWatch = DiscoverWatch.UNWATCHED) }
        rule("$ONLY$WATCHED") { copy(discoverWatch = DiscoverWatch.WATCHED) }
        rule("${ONLY}set", Position.WHOLE) { copy(discoverWatch = DiscoverWatch.WATCHED) }
        val rating = "(?:IMDb[ -]?)?(?:rating|bedømmelse|karakter)"
        rule("(?:(?:with|med) (?:an? |en )?)?(?:minimum )?$rating(?: (?:of|på))?(?: (?:at least|mindst|minimum))? (10|[1-9])(?:\\+| (?:or higher|eller højere))?") {
            copy(discoverMinRating = it.groupValues[1].toInt())
        }
        rule("(?:at least|mindst|minimum) (10|[1-9]) (?:stars|stjerner|i $rating)") { copy(discoverMinRating = it.groupValues[1].toInt()) }

        rule("(?:in the genre |i genren |genre |genren )$genre") {
            copy(selectedDiscoverGenre = genres[it.groupValues[1]])
        }
        rule("$ONLY(?:(?:some|all|nogle|alle) )?$genre[ -]?$type(?: $KEYWORD_LINK)?", preserveSubtitle = true) {
            copy(selectedDiscoverGenre = genres[it.groupValues[1]], selectedDiscoverType = types[it.groupValues[2]])
        }
        rule("$ONLY$genre", Position.WHOLE) { copy(selectedDiscoverGenre = genres[it.groupValues[1]]) }
        rule("$ONLY(?:(?:a|the|en|den) )?$type $KEYWORD_LINK") { copy(selectedDiscoverType = types[it.groupValues[1]]) }
        rule("$ONLY$type", Position.WHOLE) { copy(selectedDiscoverType = types[it.groupValues[1]]) }
        // A trailing singular English "movie" is commonly part of a title ("The LEGO Movie").
        rule("$ONLY(film|filmen|filmene|serie|serien|serier|serierne|series|tv[ -]serier?|tv series|tv shows?|shows|movies|films)", Position.END, preserveSubtitle = true) {
            copy(selectedDiscoverType = types[it.groupValues[1]])
        }
        rule("$ONLY(serier|movies|films|shows)", preserveSubtitle = true) { copy(selectedDiscoverType = types[it.groupValues[1]]) }
    }

    fun apply(raw: String, current: SearchUiState): SearchUiState {
        val phrase = raw.trim().replace(whitespace, " ")
        if (phrase.isEmpty()) return current
        if (reset.matches(phrase)) return current.copy(
            selectedDiscoverType = DISCOVER_TYPE_ANY, selectedDiscoverCatalogKey = DiscoverCatalogOption.ALL.key,
            selectedDiscoverGenre = null, discoverKeyword = "", discoverPeriod = DiscoverPeriod.ANY,
            discoverMinRating = null, discoverWatch = DiscoverWatch.ANY, discoverSort = DiscoverSort.TRENDING
        )
        if (clearKeyword.matches(phrase)) return current.copy(discoverKeyword = "")

        val searchCommand = command.find(phrase)
        var remaining = phrase.removePrefix(searchCommand?.value.orEmpty())
        val newSearch = searchCommand != null && !refinement.containsMatchIn(remaining)
        literalTitle.matchEntire(remaining)?.let { return current.copy(discoverKeyword = it.groupValues[1]) }
        var criteria = current
        while (remaining.isNotEmpty()) {
            val clause = remaining.trimEnd('.', '!', '?')
            var matched = false
            for (rule in rules) {
                val match = rule.match(clause) ?: continue
                criteria = rule.apply(criteria, match)
                remaining = when {
                    match.value.length == clause.length -> ""
                    match.range.first == 0 -> remaining.drop(match.value.length).trimStart(' ', ',').replaceFirst(leadingConnector, "")
                    else -> remaining.take(match.range.first).trimEnd(' ', ',').replace(trailingConnector, "").trimEnd(' ', ',')
                }
                matched = true
                break
            }
            if (!matched) break
        }
        val catalog = DiscoverCatalogOption.entries.firstOrNull { it.key == criteria.selectedDiscoverCatalogKey && it.availableFor(criteria.selectedDiscoverType) }
        val keyword = when {
            remaining.isNotEmpty() -> remaining.removeSurrounding("\"").removeSurrounding("“", "”")
            newSearch -> ""
            else -> current.discoverKeyword
        }
        return criteria.copy(discoverKeyword = keyword, selectedDiscoverCatalogKey = (catalog ?: DiscoverCatalogOption.ALL).key)
    }
}
