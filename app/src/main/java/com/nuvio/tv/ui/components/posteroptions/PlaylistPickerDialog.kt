package com.nuvio.tv.ui.components.posteroptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.localizedMembershipTitle
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.collection.NuvioTextField
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlaylistPickerHost(picker: LibraryPlaylistPicker) {
    val current by picker.state.collectAsStateWithLifecycle()
    val draft = current ?: return
    val error = draft.error?.let { it.localizedMessage ?: stringResource(R.string.library_picker_error) }

    when {
        draft.original == null -> NuvioDialog(
            onDismiss = picker::dismiss,
            title = draft.input.title,
            subtitle = error ?: stringResource(R.string.library_picker_loading)
        ) {
            if (error != null) {
                Button(onClick = picker::load) { Text(stringResource(R.string.action_retry)) }
            }
            Button(onClick = picker::dismiss) { Text(stringResource(R.string.action_cancel)) }
        }

        draft.confirmations.isNotEmpty() -> TrackingRemovalConfirmationDialog(
            itemTitle = draft.input.title,
            confirmations = draft.confirmations,
            isPending = draft.pending,
            onConfirm = picker::save,
            onDismiss = picker::cancelConfirmation
        )

        draft.creating -> {
            var name by rememberSaveable { mutableStateOf("") }
            val nameFocus = remember { FocusRequester() }
            NuvioDialog(
                onDismiss = { picker.setCreating(false) },
                title = stringResource(R.string.library_picker_create)
            ) {
                NuvioTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.library_picker_name),
                    focusRequester = nameFocus,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) Text(error, color = NuvioTheme.colors.TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { picker.create(name) }, enabled = name.isNotBlank() && !draft.pending) {
                        Text(stringResource(R.string.library_list_create))
                    }
                    Button(onClick = { picker.setCreating(false) }, enabled = !draft.pending) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
                LaunchedEffect(Unit) { nameFocus.requestFocus() }
            }
        }

        else -> PosterListPickerDialog(
            title = draft.input.title,
            tabs = draft.tabs,
            membership = draft.membership,
            isPending = draft.pending,
            error = error,
            onToggle = picker::toggle,
            onSave = picker::save,
            onDismiss = picker::dismiss,
            onCreate = { picker.setCreating(true) },
            defaultsToWatchLater = draft.defaultsToWatchLater,
            unavailableProviders = draft.unavailableProviders
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PosterListPickerDialog(
    title: String,
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>,
    isPending: Boolean,
    error: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    defaultsToWatchLater: Boolean,
    unavailableProviders: Set<TrackingProviderId> = emptySet()
) {
    val saveFocus = remember { FocusRequester() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(
            if (defaultsToWatchLater) R.string.library_picker_default_hint else R.string.detail_lists_subtitle
        ),
        width = 500.dp
    ) {
        if (unavailableProviders.isNotEmpty()) {
            val names = unavailableProviders.map {
                stringResource(when (it) {
                    TrackingProviderId.TRAKT -> R.string.trakt_name
                    TrackingProviderId.SIMKL -> R.string.simkl_name
                    TrackingProviderId.MDBLIST -> R.string.mdblist_name
                })
            }
            Text(stringResource(R.string.library_picker_unavailable, names.joinToString()), style = MaterialTheme.typography.bodyMedium)
        }
        if (error != null) Text(error, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 260.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tabs, key = { it.key }) { tab ->
                val selected = membership[tab.key] == true
                Button(
                    onClick = { onToggle(tab.key) },
                    enabled = !isPending,
                    modifier = Modifier.fillMaxWidth().semantics {
                        role = Role.Checkbox
                        toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Icon(
                        imageVector = if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = null
                    )
                    Text(
                        text = tab.localizedMembershipTitle(),
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = onCreate, enabled = !isPending) { Text(stringResource(R.string.library_picker_create)) }
            Button(onClick = onSave, enabled = !isPending, modifier = Modifier.focusRequester(saveFocus)) {
                Text(stringResource(if (isPending) R.string.action_saving else R.string.action_save))
            }
        }
        LaunchedEffect(Unit) { saveFocus.requestFocus() }
    }
}
