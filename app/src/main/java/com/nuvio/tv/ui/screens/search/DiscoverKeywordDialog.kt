package com.nuvio.tv.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DiscoverKeywordDialog(keyword: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var draft by rememberSaveable(keyword) { mutableStateOf(keyword) }
    val value = draft.trim()
    val field = remember { FocusRequester() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.discover_keyword_title),
        subtitle = stringResource(R.string.discover_keyword_hint)
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().focusRequester(field),
            label = { Text(stringResource(R.string.discover_keyword_field), color = NuvioTheme.colors.TextSecondary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onApply(value) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuvioTheme.colors.TextPrimary,
                unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                focusedBorderColor = NuvioTheme.colors.FocusRing,
                unfocusedBorderColor = NuvioTheme.colors.Border,
                cursorColor = NuvioTheme.colors.FocusRing
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            Button(onClick = { onApply(value) }) { Text(stringResource(R.string.discover_apply)) }
            if (keyword.isNotEmpty()) {
                Button(onClick = { onApply("") }) { Text(stringResource(R.string.discover_clear_keyword)) }
            }
            Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
        LaunchedEffect(Unit) { field.requestFocus() }
    }
}
