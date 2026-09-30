package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * A single wrapping line that interleaves free-typed text with removable artist chips, backed by
 * [ChipTextSegments]. Chips are only ever added by tapping a suggestion below the field (never by
 * typing a delimiter) and always append at the end - see [ChipTextSegments.addChip] - while every
 * gap stays freely editable, so a user can type before, between, or after any chip (e.g. "feat",
 * "(remix)") without losing the chip's own removability.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ArtistChipTextField(
    segments: ChipTextSegments,
    onSegmentsChange: (ChipTextSegments) -> Unit,
    existingValues: List<String>,
    label: String,
    placeholder: String,
    icon: ImageVector,
    tint: Color,
    textFieldColors: TextFieldColors,
    textFieldShape: Shape,
    modifier: Modifier = Modifier,
) {
    var helpDialogVisible by remember { mutableStateOf(false) }
    var pickerSheetVisible by remember { mutableStateOf(false) }
    var trailingGapFocused by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.padding(start = 4.dp),
                text = label,
                color = tint,
                style = MaterialTheme.typography.labelLarge
            )
            IconButton(
                onClick = { helpDialogVisible = true },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Rounded.Info,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = stringResource(R.string.artist_chip_help_cd),
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { pickerSheetVisible = true }) {
                Icon(
                    Icons.Rounded.List,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.artist_chip_browse_list_cd), style = MaterialTheme.typography.labelMedium)
            }
        }

        Surface(
            color = textFieldColors.unfocusedContainerColor,
            shape = textFieldShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, tint = tint, contentDescription = label)
                Spacer(modifier = Modifier.width(12.dp))
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    segments.gaps.forEachIndexed { index, gapText ->
                        val isTrailingGap = index == segments.gaps.lastIndex
                        ChipTextGapField(
                            text = gapText,
                            onTextChange = { newText -> onSegmentsChange(segments.setGap(index, newText)) },
                            placeholder = if (index == 0 && segments.chips.isEmpty()) placeholder else null,
                            modifier = if (isTrailingGap) {
                                Modifier.onFocusChanged { trailingGapFocused = it.isFocused }
                            } else {
                                Modifier
                            }
                        )
                        if (index < segments.chips.size) {
                            InputChip(
                                selected = false,
                                onClick = { onSegmentsChange(segments.removeChipAt(index)) },
                                label = { Text(segments.chips[index]) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.tag_chip_remove, segments.chips[index]),
                                        modifier = Modifier.size(InputChipDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        val trailingQuery = segments.gaps.lastOrNull().orEmpty()
        val suggestions = remember(trailingQuery, existingValues, segments.chips) {
            filterTagSuggestions(trailingQuery, existingValues, alreadyAdded = segments.chips)
        }
        if (trailingGapFocused && suggestions.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(suggestions) { suggestion ->
                    SuggestionChip(
                        onClick = {
                            val cleared = segments.setGap(segments.gaps.lastIndex, "")
                            onSegmentsChange(cleared.addChip(suggestion))
                        },
                        label = { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
    }

    if (helpDialogVisible) {
        AlertDialog(
            onDismissRequest = { helpDialogVisible = false },
            icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
            title = { Text(stringResource(R.string.artist_chip_help_title)) },
            text = { Text(stringResource(R.string.artist_chip_help_body)) },
            confirmButton = {
                TextButton(onClick = { helpDialogVisible = false }) {
                    Text(stringResource(R.string.edit_song_dialog_got_it))
                }
            }
        )
    }

    if (pickerSheetVisible) {
        ArtistPickerBottomSheet(
            title = label,
            segments = segments,
            onSegmentsChange = onSegmentsChange,
            existingValues = existingValues,
            textFieldColors = textFieldColors,
            textFieldShape = textFieldShape,
            onDismiss = { pickerSheetVisible = false }
        )
    }
}

/**
 * The full-list picker, restoring the "browse and tap to add" interaction genre editing already
 * has, on top of [ChipTextSegments] - chips picked here are appended live (same as tapping an
 * inline suggestion), so the field behind the sheet updates immediately as each one is added.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ArtistPickerBottomSheet(
    title: String,
    segments: ChipTextSegments,
    onSegmentsChange: (ChipTextSegments) -> Unit,
    existingValues: List<String>,
    textFieldColors: TextFieldColors,
    textFieldShape: Shape,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun close() {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { close() },
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

            if (segments.chips.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    segments.chips.forEachIndexed { index, chip ->
                        InputChip(
                            selected = false,
                            onClick = { onSegmentsChange(segments.removeChipAt(index)) },
                            label = { Text(chip) },
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.tag_chip_remove, chip),
                                    modifier = Modifier.size(InputChipDefaults.IconSize)
                                )
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
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

            val suggestions = remember(query, existingValues, segments.chips) {
                filterTagSuggestions(query, existingValues, alreadyAdded = segments.chips)
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
                                onSegmentsChange(segments.addChip(suggestion))
                                query = ""
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = suggestion, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Button(
                onClick = { close() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                shape = AbsoluteSmoothCornerShape(16.dp, 60)
            ) {
                Text(stringResource(R.string.common_done))
            }
        }
    }
}

/**
 * A text field sized to fit its own content, rather than expanding to fill its container, so
 * several can sit inline with chips on a single wrapping [FlowRow] line.
 */
@Composable
private fun ChipTextGapField(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val measuredText = text.ifEmpty { placeholder.orEmpty() }.ifEmpty { " " }
    val measuredWidthPx = remember(measuredText, textStyle) {
        textMeasurer.measure(measuredText, textStyle).size.width
    }
    val fieldWidth = with(density) { measuredWidthPx.toDp() } + 6.dp

    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier.width(fieldWidth.coerceAtLeast(10.dp)),
        textStyle = textStyle,
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box {
                if (text.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
                innerTextField()
            }
        }
    )
}
