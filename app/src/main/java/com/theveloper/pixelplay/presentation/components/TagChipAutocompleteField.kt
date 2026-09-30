package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.utils.splitByDelimiters
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Ranks existing tag values against a typed query: prefix matches first (most likely what the
 * user means), then mid-string matches, both case-insensitive - and excludes anything already
 * added as a chip, so a confirmed value doesn't also show up as a suggestion for itself. A blank
 * query returns every not-yet-added existing tag (the "show me what's already there" state).
 */
internal fun filterTagSuggestions(
    query: String,
    existingTags: List<String>,
    alreadyAdded: List<String> = emptyList()
): List<String> {
    val candidates = existingTags.filter { candidate ->
        alreadyAdded.none { it.equals(candidate, ignoreCase = true) }
    }
    if (query.isBlank()) return candidates
    val startsWith = candidates.filter { it.startsWith(query, ignoreCase = true) }
    val containsOnly = candidates.filter {
        it.contains(query, ignoreCase = true) && !it.startsWith(query, ignoreCase = true)
    }
    return startsWith + containsOnly
}

/** Adds [candidate] as a new chip, trimmed, unless it's blank or already present (case-insensitive). */
internal fun addTag(tags: List<String>, candidate: String): List<String> {
    val trimmed = candidate.trim()
    if (trimmed.isBlank()) return tags
    if (tags.any { it.equals(trimmed, ignoreCase = true) }) return tags
    return tags + trimmed
}

/** Removes the exact [tag] from [tags], if present. */
internal fun removeTag(tags: List<String>, tag: String): List<String> = tags - tag

internal data class TagInputSplit(val confirmedTags: List<String>, val remainingText: String)

/**
 * Splits live text-field input into newly-confirmed tags plus whatever's still being typed -
 * reuses [splitByDelimiters] so a typed or pasted delimiter behaves identically to the same
 * delimiter used elsewhere (settings-configurable, escape sequences, etc.). A delimiter at the
 * very end of [text] confirms every segment (the user just finished a tag); otherwise the last
 * segment is still in progress and stays as [TagInputSplit.remainingText].
 */
internal fun splitPendingTagInput(text: String, delimiters: List<String>): TagInputSplit {
    val activeDelimiters = delimiters.filter { it.isNotEmpty() }
    if (activeDelimiters.none { text.contains(it) }) {
        return TagInputSplit(emptyList(), text)
    }
    val segments = text.splitByDelimiters(activeDelimiters)
    val endsWithDelimiter = activeDelimiters.any { text.endsWith(it) }
    return if (endsWithDelimiter) {
        TagInputSplit(confirmedTags = segments, remainingText = "")
    } else {
        TagInputSplit(confirmedTags = segments.dropLast(1), remainingText = segments.lastOrNull() ?: "")
    }
}

/**
 * A collapsed field that opens a [ModalBottomSheet] for picking or typing multiple values (e.g.
 * genres, artists) as removable chips. [value] stays the single source of truth as a delimiter
 * -joined string (same contract a plain text field would have), so callers don't need to change
 * their own state model - this composable owns the chip/text split internally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagChipAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    existingValues: List<String>,
    delimiters: List<String>,
    label: String,
    placeholder: String,
    icon: ImageVector,
    tint: Color,
    textFieldColors: TextFieldColors,
    textFieldShape: Shape,
    modifier: Modifier = Modifier,
    joinDelimiter: String = ", ",
    // Fired with the final chip list once the sheet commits (not live per-tap) - lets a caller
    // derive another field from the selection, e.g. auto-building a display-artist string from
    // the picked ARTISTS chips. Genre doesn't need this (default no-op).
    onTagsChanged: (List<String>) -> Unit = {}
) {
    var sheetVisible by remember { mutableStateOf(false) }
    val tags = remember(value, delimiters) {
        value.splitByDelimiters(delimiters)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            modifier = Modifier.padding(start = 4.dp),
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelLarge
        )
        Surface(
            onClick = { sheetVisible = true },
            color = textFieldColors.unfocusedContainerColor,
            shape = textFieldShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, tint = tint, contentDescription = label)
                Spacer(modifier = Modifier.width(12.dp))
                if (tags.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    Text(
                        text = tags.joinToString(joinDelimiter),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1
                    )
                }
            }
        }
    }

    if (sheetVisible) {
        TagChipBottomSheet(
            title = label,
            initialTags = tags,
            existingValues = existingValues,
            delimiters = delimiters,
            textFieldColors = textFieldColors,
            textFieldShape = textFieldShape,
            onDismiss = { finalTags ->
                onValueChange(finalTags.joinToString(joinDelimiter))
                onTagsChanged(finalTags)
                sheetVisible = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagChipBottomSheet(
    title: String,
    initialTags: List<String>,
    existingValues: List<String>,
    delimiters: List<String>,
    textFieldColors: TextFieldColors,
    textFieldShape: Shape,
    onDismiss: (List<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var tags by remember { mutableStateOf(initialTags) }
    var pendingText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun commitAndDismiss() {
        val finalTags = addTag(tags, pendingText)
        scope.launch {
            sheetState.hide()
            onDismiss(finalTags)
        }
    }

    ModalBottomSheet(
        onDismissRequest = { commitAndDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .heightIn(min = 320.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (tags.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tags.forEach { tag ->
                        InputChip(
                            selected = false,
                            onClick = { tags = removeTag(tags, tag) },
                            label = { Text(tag) },
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.tag_chip_remove, tag),
                                    modifier = Modifier.size(InputChipDefaults.IconSize)
                                )
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = pendingText,
                onValueChange = { newText ->
                    val split = splitPendingTagInput(newText, delimiters)
                    if (split.confirmedTags.isNotEmpty()) {
                        tags = split.confirmedTags.fold(tags) { acc, candidate -> addTag(acc, candidate) }
                    }
                    pendingText = split.remainingText
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.tag_chip_input_placeholder)) },
                singleLine = true,
                shape = textFieldShape,
                colors = textFieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }

            val suggestions = remember(pendingText, existingValues, tags) {
                filterTagSuggestions(pendingText, existingValues, alreadyAdded = tags)
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(top = 8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(suggestions) { suggestion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                tags = addTag(tags, suggestion)
                                pendingText = ""
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = suggestion, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Button(
                onClick = { commitAndDismiss() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                shape = AbsoluteSmoothCornerShape(16.dp, 60)
            ) {
                Text(stringResource(R.string.common_done))
            }
        }
    }
}
