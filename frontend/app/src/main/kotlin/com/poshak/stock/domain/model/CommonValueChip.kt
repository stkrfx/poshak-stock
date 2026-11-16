package com.poshak.stock.domain.model

/**
 * Represents a Common Value chip computed 100% in the Kotlin frontend by `CommonValuesCalculator`.
 *
 * Per Section 9.4.2:
 * - If every product in the result set shares the same non-null, non-empty value for
 *   `MRP`, `Brand`, `Article`, `Name`, `Color`, or `Party` (admin only)—or if every stock row
 *   shares a single `Size`—and that field is not already an Applied Filter, it is displayed
 *   as a Common Value chip.
 * - `MRP` (when common across all rows) uses a prominent Material 3 tonal badge (`MRP: ₹1499`).
 * - Single-Tap on a filterable Common Value chip: Opens the Filter Bottom Sheet and focuses
 *   that specific filter field's input.
 * - Double-Tap on an unapplied filterable Common Value chip: Promotes that chip's value into
 *   an active Applied Filter.
 */
data class CommonValueChip(
    val key: String,
    val label: String,
    val displayValue: String,
    val rawValue: String,
    val filterField: FilterField? = null,
    val isMrpTonalBadge: Boolean = false
) {
    val isFilterable: Boolean
        get() = filterField != null

    val formattedChipText: String
        get() = "$label: $displayValue"
}
