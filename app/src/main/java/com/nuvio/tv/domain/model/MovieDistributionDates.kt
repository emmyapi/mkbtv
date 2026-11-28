package com.nuvio.tv.domain.model

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Earliest dated theatrical and digital/physical distribution in any country. */
data class MovieDistributionDates(val cinemaDate: String?, val homeDate: String?) {
    fun merge(other: MovieDistributionDates?): MovieDistributionDates {
        if (other == null) return this
        return MovieDistributionDates(
            earliest(cinemaDate, other.cinemaDate),
            earliest(homeDate, other.homeDate)
        )
    }

    companion object {
        fun fromTypedDates(rows: Sequence<Pair<Int?, String?>>): MovieDistributionDates? {
            var cinema: String? = null
            var home: String? = null
            for ((type, raw) in rows) {
                if (type == null || type !in 2..5 || raw == null) continue
                val date = try {
                    if ('T' in raw) OffsetDateTime.parse(raw).toLocalDate() else LocalDate.parse(raw)
                } catch (_: DateTimeParseException) {
                    continue
                }.toString()
                when (type) {
                    2, 3 -> cinema = earliest(cinema, date)
                    4, 5 -> home = earliest(home, date)
                }
            }
            return if (cinema == null && home == null) null else MovieDistributionDates(cinema, home)
        }

        private fun earliest(first: String?, second: String?): String? = when {
            first == null -> second
            second == null -> first
            else -> minOf(first, second)
        }
    }
}
