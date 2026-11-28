package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.MovieRelease
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun AgeStatusBadge(
    ageRating: String?,
    status: String?,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    ageColor: Color = NuvioTheme.colors.TextSecondary,
    compact: Boolean = false
) {
    if (ageRating == null && status == null) return
    val statusColor = NuvioTheme.colors.TextPrimary
    val borderColor = (if (ageRating != null) ageColor else statusColor).copy(alpha = 0.55f)
    val spacing = if (compact) NuvioTheme.spacing.xs else NuvioTheme.spacing.sm
    Row(
        modifier = modifier.border(BorderStroke(NuvioTheme.spacing.hairline, borderColor), RoundedCornerShape(6.dp))
            .padding(horizontal = spacing, vertical = if (compact) 2.dp else NuvioTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        if (ageRating != null) {
            Text(text = ageRating, style = textStyle, color = ageColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ageRating != null && status != null) {
            Box(Modifier.width(NuvioTheme.spacing.hairline).height(NuvioTheme.spacing.md).background(borderColor))
        }
        if (status != null) {
            Text(text = status, style = textStyle, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun CardAgeStatusBadge(ageRating: String?, release: MovieRelease?) {
    // Keep the row height stable while the visible movies' release dates arrive.
    Box(Modifier.padding(top = NuvioTheme.spacing.xs).heightIn(min = 22.dp)) {
        AgeStatusBadge(
            ageRating = ageRating?.trim()?.takeIf { it.isNotEmpty() } ?: release?.ageRating,
            status = release.phaseLabel(),
            textStyle = MaterialTheme.typography.labelSmall,
            compact = true
        )
    }
}

@Composable
internal fun MovieRelease?.phaseLabel(): String? = when (this?.status) {
    "upcoming" -> stringResource(R.string.movie_release_upcoming)
    "cinema" -> stringResource(R.string.movie_release_cinema)
    "home" -> stringResource(R.string.movie_release_home)
    else -> null
}

private val shortDate = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val fullDate = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

@Composable
internal fun MovieRelease?.nextDateLabel(): String? {
    if (this == null || (status != "upcoming" && status != "cinema")) return null
    val today = LocalDate.now(ZoneOffset.UTC)
    val next = remember(this, today) {
        fun future(raw: String?) = raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { it > today }
        val home = future(homeDate)
        val cinema = future(cinemaDate)?.takeIf { status == "upcoming" }
        when {
            cinema != null && (home == null || cinema < home) -> R.string.movie_release_cinema_date to cinema
            home != null -> R.string.movie_release_home_date to home
            else -> null
        }
    } ?: return null
    return stringResource(next.first, next.second.format(if (next.second.year == today.year) shortDate else fullDate))
}

@Composable
internal fun catalogStatusLabel(raw: String?, isSeries: Boolean): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val id = when (value.lowercase(Locale.ROOT)) {
        "ended" -> if (isSeries) R.string.series_status_ended else R.string.movie_status_ended
        "continuing", "returning series" -> if (isSeries) R.string.series_status_continuing else R.string.movie_status_continuing
        "current" -> if (isSeries) R.string.series_status_current else R.string.movie_status_current
        "cancelled", "canceled" -> if (isSeries) R.string.series_status_cancelled else R.string.movie_status_cancelled
        "released" -> if (isSeries) R.string.series_status_released else R.string.movie_status_released
        "planned" -> if (isSeries) R.string.series_status_planned else R.string.movie_status_planned
        "rumored" -> if (isSeries) R.string.series_status_rumored else R.string.movie_status_rumored
        "in production" -> if (isSeries) R.string.series_status_in_production else R.string.movie_status_in_production
        "post production" -> if (isSeries) R.string.series_status_post_production else R.string.movie_status_post_production
        else -> return value.uppercase()
    }
    return stringResource(id).uppercase()
}
