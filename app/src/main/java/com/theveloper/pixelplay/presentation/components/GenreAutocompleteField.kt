package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Ranks existing genre names against a typed query: prefix matches first (most likely what
 * the user means), then mid-string matches, both case-insensitive. A blank query returns every
 * existing genre unfiltered (the "show me what's already there" starting state).
 */
internal fun filterGenreSuggestions(query: String, existingGenres: List<String>): List<String> {
    if (query.isBlank()) return existingGenres
    val startsWith = existingGenres.filter { it.startsWith(query, ignoreCase = true) }
    val containsOnly = existingGenres.filter {
        it.contains(query, ignoreCase = true) && !it.startsWith(query, ignoreCase = true)
    }
    return startsWith + containsOnly
}

/**
 * A genre text field that suggests already-known genre names as the user types, so editing a
 * song's genre can reuse an existing one (avoiding "Latin" vs "Latn"-style typo duplicates)
 * while still allowing a brand new genre to be typed freely - selecting a suggestion just fills
 * the field, it does not restrict what can be saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    existingGenres: List<String>,
    label: String,
    placeholder: String,
    icon: ImageVector,
    tint: Color,
    textFieldColors: TextFieldColors,
    textFieldShape: Shape,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val suggestions = remember(value, existingGenres) {
        filterGenreSuggestions(value, existingGenres).take(8)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            modifier = Modifier.padding(start = 4.dp),
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelLarge
        )
        ExposedDropdownMenuBox(
            expanded = expanded && suggestions.isNotEmpty(),
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    onValueChange(it)
                    expanded = true
                },
                colors = textFieldColors,
                shape = textFieldShape,
                placeholder = { Text(placeholder) },
                leadingIcon = { Icon(icon, tint = tint, contentDescription = label) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            ExposedDropdownMenu(
                expanded = expanded && suggestions.isNotEmpty(),
                onDismissRequest = { expanded = false }
            ) {
                suggestions.forEach { suggestion ->
                    DropdownMenuItem(
                        text = { Text(suggestion) },
                        onClick = {
                            onValueChange(suggestion)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
