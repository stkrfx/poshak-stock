package com.poshak.stock.ui.main.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Reusable Material 3 field-specific autocomplete input component.
 *
 * Strictly enforces Section 1.4 & Section 9.2 of the specification:
 * 1. Dedicated trailing Clear (`X`) button whenever its text or selected value is non-empty.
 * 2. Persistent Dropdown on Exact Match:
 *    Even if the text currently typed in the input equals one of the suggestions
 *    (e.g., user types `1578` in `Article`, and the suggestion list has `1578` and `15789`),
 *    the autocomplete suggestion dropdown stays visible showing both `1578` and `15789`.
 * 3. Unfocus / Blur Auto-Resolution Rule:
 *    When the user unfocuses/leaves the input field without explicitly tapping a suggestion item:
 *    - Checks if the trimmed typed text matches any suggestion in that field's current suggestion list
 *      (case-insensitive exact match).
 *    - If an exact match exists, automatically selects that canonical suggestion value.
 *    - If no exact match exists, automatically resets the input field to blank (`""`).
 */
@Composable
fun AutocompleteInputField(
    label: String,
    placeholder: String,
    typedText: String,
    committedValue: String,
    suggestions: List<String>,
    isLoadingSuggestions: Boolean,
    enabled: Boolean = true,
    disabledHelperHint: String? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    imeAction: ImeAction = ImeAction.Next,
    onTextChanged: (String) -> Unit,
    onSuggestionSelected: (String) -> Unit,
    onClearClicked: () -> Unit,
    onFieldUnfocused: () -> Unit,
    onImeActionTriggered: () -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }
    var hadFocusPreviously by remember { mutableStateOf(false) }

    val showDropdown = enabled &&
        isFocused &&
        typedText.trim().isNotEmpty() &&
        suggestions.isNotEmpty()

    val isExactMatchHighlighted = committedValue.isNotBlank() &&
        typedText.trim().equals(committedValue.trim(), ignoreCase = true)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = typedText,
            onValueChange = onTextChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        isFocused = true
                        hadFocusPreviously = true
                    } else {
                        isFocused = false
                        if (hadFocusPreviously) {
                            hadFocusPreviously = false
                            onFieldUnfocused()
                        }
                    }
                },
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            supportingText = if (!enabled && !disabledHelperHint.isNullOrBlank()) {
                {
                    Text(
                        text = disabledHelperHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                null
            },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (isLoadingSuggestions && isFocused) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else if (isExactMatchHighlighted) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selected Filter",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (typedText.isNotEmpty() || committedValue.isNotEmpty()) {
                        IconButton(onClick = onClearClicked) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear $label"
                            )
                        }
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onNext = {
                    onFieldUnfocused()
                    onImeActionTriggered()
                },
                onDone = {
                    onFieldUnfocused()
                    onImeActionTriggered()
                },
                onSearch = {
                    onFieldUnfocused()
                    onImeActionTriggered()
                }
            )
        )

        // Persistent Dropdown List (stays visible even when typedText exactly matches a suggestion)
        AnimatedVisibility(visible = showDropdown) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 185.dp),
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 4.dp,
                shadowElevation = 4.dp
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(
                        items = suggestions,
                        key = { it }
                    ) { suggestion ->
                        val isExactMatch = suggestion.trim().equals(typedText.trim(), ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isExactMatch) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                )
                                .clickable {
                                    onSuggestionSelected(suggestion)
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isExactMatch) FontWeight.Bold else FontWeight.Normal,
                                color = if (isExactMatch) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (isExactMatch) {
                                Text(
                                    text = "Exact Match",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}
