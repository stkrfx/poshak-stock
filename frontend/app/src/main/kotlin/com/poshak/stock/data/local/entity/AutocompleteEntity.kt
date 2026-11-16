package com.poshak.stock.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Stores distinct autocomplete suggestions locally in Room (`autocomplete_suggestions` table).
 * Populated both organically (from `/v1/autocomplete` and `/v1/stock/search` responses)
 * and during manual Full/Filtered Offline Downloads.
 *
 * Valid `field` values: `article`, `brand`, `name`, `party` (admin only), `color`, `size`.
 */
@Entity(
    tableName = "autocomplete_suggestions",
    primaryKeys = ["field", "normalized_value"],
    indices = [
        Index(value = ["field", "normalized_value"])
    ]
)
data class AutocompleteEntity(
    @ColumnInfo(name = "field")
    val field: String,

    @ColumnInfo(name = "normalized_value")
    val normalizedValue: String,

    @ColumnInfo(name = "display_value")
    val displayValue: String,

    @ColumnInfo(name = "size_sort_order")
    val sizeSortOrder: Int = 999999,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val FIELD_ARTICLE = "article"
        const val FIELD_BRAND = "brand"
        const val FIELD_NAME = "name"
        const val FIELD_PARTY = "party"
        const val FIELD_COLOR = "color"
        const val FIELD_SIZE = "size"

        fun create(
            field: String,
            displayValue: String,
            sizeSortOrder: Int = 999999,
            updatedAt: Long = System.currentTimeMillis()
        ): AutocompleteEntity {
            val cleanDisplay = displayValue.trim()
            return AutocompleteEntity(
                field = field.trim().lowercase(),
                normalizedValue = cleanDisplay.lowercase(),
                displayValue = cleanDisplay,
                sizeSortOrder = sizeSortOrder,
                updatedAt = updatedAt
            )
        }
    }
}
