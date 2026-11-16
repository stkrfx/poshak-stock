package com.poshak.stock.domain.logic

import com.poshak.stock.domain.model.FilterField
import com.poshak.stock.domain.model.FilterState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enforces all search filter preconditions, role visibility rules,
 * Exact-Match-On-Unfocus resolution, and the Strict Non-Solo Rule for Size & Color.
 */
@Singleton
class FilterPreconditionValidator @Inject constructor() {

    /**
     * Returns true if [filter] has at least one valid primary filter
     * (`Article`, `Brand`, `Barcode/Brandcode`, `Name`, or `Party` [admin only]).
     */
    fun canExecuteSearch(filter: FilterState, isAdmin: Boolean): Boolean {
        return filter.hasPrimaryFilter(isAdmin)
    }

    /**
     * Returns true if `Size` and `Color` inputs are unlocked for querying and editing.
     * Per Section 9.2: If none of the primary filters currently has a valid selected value,
     * `Size` and `Color` inputs are disabled and display:
     * `"Select Article, Brand, Name, Barcode, or Party first"`.
     */
    fun areSecondaryFiltersUnlocked(filter: FilterState, isAdmin: Boolean): Boolean {
        return filter.hasPrimaryFilter(isAdmin)
    }

    /**
     * Returns the helper hint for locked `Size` / `Color` inputs based on user role.
     */
    fun getSecondaryLockedHint(isAdmin: Boolean): String {
        return if (isAdmin) {
            "Select Article, Brand, Name, Barcode, or Party first"
        } else {
            "Select Article, Brand, Name, or Barcode first"
        }
    }

    /**
     * Implements the Unfocus / Blur Auto-Resolution Rule (Section 9.2) for all autocomplete fields:
     * When the user unfocuses/leaves an input field without explicitly tapping a suggestion item:
     * 1. Check if the trimmed typed text matches any suggestion in that field's current
     *    suggestion list (case-insensitive exact match, e.g., `1578`).
     * 2. If an exact match exists: Automatically select that canonical suggestion value (`1578`).
     * 3. If no exact match exists: Automatically clear the input field to blank (`""`).
     */
    fun resolveValueOnUnfocus(
        typedText: String,
        currentCommittedValue: String,
        currentSuggestions: List<String>
    ): String {
        val trimmed = typedText.trim()
        if (trimmed.isEmpty()) {
            return ""
        }

        // If the typed text already equals the committed value (case-insensitive), keep canonical committed value
        if (currentCommittedValue.isNotBlank() && trimmed.equals(currentCommittedValue.trim(), ignoreCase = true)) {
            return currentCommittedValue.trim()
        }

        // Check for a case-insensitive exact match in the field's current suggestion list
        val exactMatch = currentSuggestions.firstOrNull { suggestion ->
            suggestion.trim().equals(trimmed, ignoreCase = true)
        }
        return exactMatch?.trim() ?: ""
    }

    /**
     * Removes a single [fieldToRemove] from [currentFilter] and enforces the Strict Non-Solo Rule
     * for Size & Color (Section 9.3):
     * - If removing the last active primary filter leaves only `Size` and/or `Color`,
     *   immediately clears the entire filter state back to `FilterState()`.
     */
    fun removeFilterField(
        currentFilter: FilterState,
        fieldToRemove: FilterField,
        isAdmin: Boolean
    ): FilterState {
        val updated = currentFilter.withField(fieldToRemove, "")
        return updated.enforceRoleAndNonSoloRule(isAdmin)
    }

    /**
     * Updates a single [field] to [newValue] and enforces the Strict Non-Solo Rule
     * and role security (`Party` stripped for `employee`).
     */
    fun updateFilterField(
        currentFilter: FilterState,
        field: FilterField,
        newValue: String,
        isAdmin: Boolean
    ): FilterState {
        if (field == FilterField.PARTY && !isAdmin) {
            return currentFilter.copy(party = "")
        }
        val updated = currentFilter.withField(field, newValue)
        return if (!updated.hasPrimaryFilter(isAdmin)) {
            // If clearing a primary filter left only Size/Color, wipe secondary filters too
            FilterState()
        } else {
            updated
        }
    }
}
