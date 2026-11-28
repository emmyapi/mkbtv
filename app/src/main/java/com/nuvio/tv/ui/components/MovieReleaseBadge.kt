package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.MovieRelease
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val shortDate = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val fullDate = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

@Composable
internal fun MovieReleaseBadge(release: MovieRelease, modifier: Modifier = Modifier) {
    val title = when (release.status) {
        "home" -> R.string.movie_release_home
        "cinema" -> R.string.movie_release_cinema
        "upcoming" -> R.string.movie_release_upcoming
        else -> return
    }
    val homeDate = remember(release) {
        release.homeDate?.takeUnless { release.status == "home" }?.let { value ->
            runCatching { LocalDate.parse(value) }.getOrNull()?.let { date ->
                date.format(if (date.year == LocalDate.now().year) shortDate else fullDate)
            }
        }
    }
    val label = stringResource(title)
    val text = if (homeDate == null) label else label + "\n" + stringResource(R.string.movie_release_home_date, homeDate)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier.background(Color.Black.copy(alpha = 0.84f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}
