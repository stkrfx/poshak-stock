package com.poshak.stock.ui.main.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.poshak.stock.domain.model.FilterField
import com.poshak.stock.domain.model.FilterState
import kotlinx.coroutines.delay

/**
 * Material 3 `ModalBottomSheet` Search Filter Form (Section 9.1 & Section 9.2).
 *
 * Renders the filter inputs in strict priority order:
 * 1. `Article`
 * 2. `Brand`
 * 3. `Barcode / Brandcode` (with trailing camera scanner icon + clear `X` icon)
 * 4. `Name`
 * 5. `Party` (**ONLY rendered when `isAdmin == true`**)
 * 6. `Color` (enabled only when at least one of #1–#5 has a selected value)
 * 7. `Size` (enabled only when at least one of #1–#5 has a selected value)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBottomSheet(
    isAdmin: Boolean,
    draftFilter: FilterState,
    effectivePrimaryContextFilter: FilterState,
    draftTexts: Map<FilterField, String>,
    suggestionsByField: Map<FilterField, List<String>>,
    loadingSuggestionsField: FilterField?,
    focusedField: FilterField?,
    errorMessage: String?,
    onConsumeFocusedField: () -> Unit,
    onDraftFieldTextChanged: (FilterField, String) -> Unit,
    onSuggestionSelected: (FilterField, String) -> Unit,
    onClearDraftField: (FilterField) -> Unit,
    onClearAllDraftFilters: () -> Unit,
    onDraftFieldUnfocused: (FilterField) -> Unit,
    onOpenBarcodeScannerForField: () -> Unit,
    onApplyFiltersAndSearch: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val focusRequesters = remember {
        FilterField.entries.associateWith { FocusRequester() }
    }

    val areSecondaryUnlocked = effectivePrimaryContextFilter.hasPrimaryFilter(isAdmin)
    val secondaryLockedHint = if (isAdmin) {
        "Select Article, Brand, Name, Barcode, or Party first"
    } else {
        "Select Article, Brand, Name, or Barcode first"
    }

    // Automatically focus the requested field (e.g. Article from Home Widget or tapped Chip)
    LaunchedEffect(focusedField) {
        if (focusedField != null) {
            delay(180L)
            runCatching {
                focusRequesters[focusedField]?.requestFocus()
                keyboardController?.show()
            }
            onConsumeFocusedField()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            focusManager.clearFocus(force = true)
            onDismiss()
        },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Search Stock Filters",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus(force = true)
                        onClearAllDraftFilters()
                    }
                ) {
                    Text("Clear All")
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }

            // 1. Article
            AutocompleteInputField(
                label = "1. Article",
                placeholder = "Type article number (e.g. 1578)",
                typedText = draftTexts[FilterField.ARTICLE].orEmpty(),
                committedValue = draftFilter.article,
                suggestions = suggestionsByField[FilterField.ARTICLE].orEmpty(),
                isLoadingSuggestions = loadingSuggestionsField == FilterField.ARTICLE,
                focusRequester = focusRequesters.getValue(FilterField.ARTICLE),
                imeAction = ImeAction.Next,
                onTextChanged = { onDraftFieldTextChanged(FilterField.ARTICLE, it) },
                onSuggestionSelected = { onSuggestionSelected(FilterField.ARTICLE, it) },
                onClearClicked = { onClearDraftField(FilterField.ARTICLE) },
                onFieldUnfocused = { onDraftFieldUnfocused(FilterField.ARTICLE) },
                onImeActionTriggered = { focusManager.moveFocus(FocusDirection.Down) }
            )

            // 2. Brand
            AutocompleteInputField(
                label = "2. Brand",
                placeholder = "Type brand (e.g. KILLER)",
                typedText = draftTexts[FilterField.BRAND].orEmpty(),
                committedValue = draftFilter.brand,
                suggestions = suggestionsByField[FilterField.BRAND].orEmpty(),
                isLoadingSuggestions = loadingSuggestionsField == FilterField.BRAND,
                focusRequester = focusRequesters.getValue(FilterField.BRAND),
                imeAction = ImeAction.Next,
                onTextChanged = { onDraftFieldTextChanged(FilterField.BRAND, it) },
                onSuggestionSelected = { onSuggestionSelected(FilterField.BRAND, it) },
                onClearClicked = { onClearDraftField(FilterField.BRAND) },
                onFieldUnfocused = { onDraftFieldUnfocused(FilterField.BRAND) },
                onImeActionTriggered = { focusManager.moveFocus(FocusDirection.Down) }
            )

            // 3. Barcode / Brandcode (with trailing camera scanner icon + clear X icon)
            val barcodeText = draftTexts[FilterField.BARCODE].orEmpty()
            OutlinedTextField(
                value = barcodeText,
                onValueChange = { onDraftFieldTextChanged(FilterField.BARCODE, it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequesters.getValue(FilterField.BARCODE))
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused) {
                            onDraftFieldUnfocused(FilterField.BARCODE)
                        }
                    },
                singleLine = true,
                label = { Text("3. Barcode / Brandcode") },
                placeholder = { Text("Scan or type barcode / brandcode") },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (barcodeText.isNotEmpty() || draftFilter.barcode.isNotEmpty()) {
                            IconButton(onClick = { onClearDraftField(FilterField.BARCODE) }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear Barcode"
                                )
                            }
                        }
                        IconButton(onClick = onOpenBarcodeScannerForField) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Scan Barcode",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = {
                        onDraftFieldUnfocused(FilterField.BARCODE)
                        focusManager.moveFocus(FocusDirection.Down)
                    }
                )
            )

            // 4. Name
            AutocompleteInputField(
                label = "4. Name",
                placeholder = "Type item name (e.g. FORMAL SHIRT)",
                typedText = draftTexts[FilterField.NAME].orEmpty(),
                committedValue = draftFilter.name,
                suggestions = suggestionsByField[FilterField.NAME].orEmpty(),
                isLoadingSuggestions = loadingSuggestionsField == FilterField.NAME,
                focusRequester = focusRequesters.getValue(FilterField.NAME),
                imeAction = ImeAction.Next,
                onTextChanged = { onDraftFieldTextChanged(FilterField.NAME, it) },
                onSuggestionSelected = { onSuggestionSelected(FilterField.NAME, it) },
                onClearClicked = { onClearDraftField(FilterField.NAME) },
                onFieldUnfocused = { onDraftFieldUnfocused(FilterField.NAME) },
                onImeActionTriggered = { focusManager.moveFocus(FocusDirection.Down) }
            )

            // 5. Party (STRICTLY ONLY rendered when isAdmin == true)
            if (isAdmin) {
                AutocompleteInputField(
                    label = "5. Party (Admin Only)",
                    placeholder = "Type supplier / party name",
                    typedText = draftTexts[FilterField.PARTY].orEmpty(),
                    committedValue = draftFilter.party,
                    suggestions = suggestionsByField[FilterField.PARTY].orEmpty(),
                    isLoadingSuggestions = loadingSuggestionsField == FilterField.PARTY,
                    focusRequester = focusRequesters.getValue(FilterField.PARTY),
                    imeAction = ImeAction.Next,
                    onTextChanged = { onDraftFieldTextChanged(FilterField.PARTY, it) },
                    onSuggestionSelected = { onSuggestionSelected(FilterField.PARTY, it) },
                    onClearClicked = { onClearDraftField(FilterField.PARTY) },
                    onFieldUnfocused = { onDraftFieldUnfocused(FilterField.PARTY) },
                    onImeActionTriggered = { focusManager.moveFocus(FocusDirection.Down) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // 6. Color (enabled ONLY when at least one primary filter #1-#5 is active)
            AutocompleteInputField(
                label = if (isAdmin) "6. Color" else "5. Color",
                placeholder = "Type color (e.g. NAVY)",
                typedText = draftTexts[FilterField.COLOR].orEmpty(),
                committedValue = draftFilter.color,
                suggestions = suggestionsByField[FilterField.COLOR].orEmpty(),
                isLoadingSuggestions = loadingSuggestionsField == FilterField.COLOR,
                enabled = areSecondaryUnlocked,
                disabledHelperHint = secondaryLockedHint,
                focusRequester = focusRequesters.getValue(FilterField.COLOR),
                imeAction = ImeAction.Next,
                onTextChanged = { onDraftFieldTextChanged(FilterField.COLOR, it) },
                onSuggestionSelected = { onSuggestionSelected(FilterField.COLOR, it) },
                onClearClicked = { onClearDraftField(FilterField.COLOR) },
                onFieldUnfocused = { onDraftFieldUnfocused(FilterField.COLOR) },
                onImeActionTriggered = { focusManager.moveFocus(FocusDirection.Down) }
            )

            // 7. Size (enabled ONLY when at least one primary filter #1-#5 is active)
            AutocompleteInputField(
                label = if (isAdmin) "7. Size" else "6. Size",
                placeholder = "Type size (e.g. M, L, XL, 32)",
                typedText = draftTexts[FilterField.SIZE].orEmpty(),
                committedValue = draftFilter.size,
                suggestions = suggestionsByField[FilterField.SIZE].orEmpty(),
                isLoadingSuggestions = loadingSuggestionsField == FilterField.SIZE,
                enabled = areSecondaryUnlocked,
                disabledHelperHint = secondaryLockedHint,
                focusRequester = focusRequesters.getValue(FilterField.SIZE),
                imeAction = ImeAction.Search,
                onTextChanged = { onDraftFieldTextChanged(FilterField.SIZE, it) },
                onSuggestionSelected = { onSuggestionSelected(FilterField.SIZE, it) },
                onClearClicked = { onClearDraftField(FilterField.SIZE) },
                onFieldUnfocused = { onDraftFieldUnfocused(FilterField.SIZE) },
                onImeActionTriggered = {
                    focusManager.clearFocus(force = true)
                    onApplyFiltersAndSearch()
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Apply / Search Action Button
            Button(
                onClick = {
                    focusManager.clearFocus(force = true)
                    onApplyFiltersAndSearch()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = areSecondaryUnlocked
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = "Search Stock",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
