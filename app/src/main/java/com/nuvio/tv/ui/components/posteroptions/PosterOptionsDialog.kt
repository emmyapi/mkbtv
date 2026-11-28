package com.nuvio.tv.ui.components.posteroptions

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalImpact
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.ui.components.NuvioDialog

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterOptionsDialog(
    title: String,
    isInLibrary: Boolean,
    isMovie: Boolean,
    isSeries: Boolean = false,
    isWatched: Boolean,
    isWatchedPending: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onToggleWatched: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.home_poster_dialog_subtitle)
    ) {
        Button(
            onClick = onDetails,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.cw_action_go_to_details))
        }

        Button(
            onClick = onOpenPlaylists,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(if (isInLibrary) R.string.library_picker_edit else R.string.library_picker_add))
        }

        if (isMovie || isSeries) {
            Button(
                onClick = onToggleWatched,
                enabled = !isWatchedPending,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(
                    if (isWatched) {
                        stringResource(R.string.hero_mark_unwatched)
                    } else {
                        stringResource(R.string.hero_mark_watched)
                    }
                )
            }
        }
    }
}

@Composable
fun PosterOptionsHost(
    state: PosterOptionsState,
    controller: PosterOptionsController,
    onNavigateToDetail: (id: String, type: String, addonBaseUrl: String) -> Unit
) {
    val target = state.target
    if (target != null) {
        val isMovie = target.apiType.equals("movie", ignoreCase = true)
        val isSeries = target.apiType.equals("series", ignoreCase = true) ||
            target.apiType.equals("tv", ignoreCase = true) ||
            target.apiType.equals("anime", ignoreCase = true)
        PosterOptionsDialog(
            title = target.name,
            isInLibrary = state.isInLibrary,
            isMovie = isMovie,
            isSeries = isSeries,
            isWatched = state.isWatched,
            isWatchedPending = state.isWatchedPending,
            onDismiss = { controller.dismiss() },
            onDetails = {
                onNavigateToDetail(target.id, target.apiType, state.addonBaseUrl)
                controller.dismiss()
            },
            onOpenPlaylists = controller::openListPicker,
            onToggleWatched = {
                if (isMovie) {
                    controller.toggleMovieWatched()
                } else {
                    controller.toggleSeriesWatched()
                }
                controller.dismiss()
            }
        )
    }

    PlaylistPickerHost(controller.playlistPicker)
}

@Composable
fun TrackingRemovalConfirmationDialog(
    itemTitle: String,
    confirmations: List<TrackingMembershipRemovalConfirmation>,
    isPending: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val providers = confirmations.joinToString { confirmation ->
        confirmation.providerId.storageId.replaceFirstChar(Char::uppercase)
    }
    val impacts = confirmations.flatMapTo(linkedSetOf()) { confirmation -> confirmation.impacts }
    val impact = when (impacts) {
        setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY) ->
            stringResource(R.string.tracking_removal_impact_history)
        setOf(TrackingMembershipRemovalImpact.RATING) ->
            stringResource(R.string.tracking_removal_impact_rating)
        else -> stringResource(R.string.tracking_removal_impact_history_rating)
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.tracking_removal_title, providers),
        subtitle = stringResource(R.string.tracking_removal_message, itemTitle, providers, impact),
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Button(
            onClick = onConfirm,
            enabled = !isPending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_remove_anyway))
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}
