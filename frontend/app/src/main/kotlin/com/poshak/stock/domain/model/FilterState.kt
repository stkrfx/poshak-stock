package com.poshak.stock.domain.model

import com.poshak.stock.data.remote.dto.StockSearchFilterDto

/**
 * Enumerates every filterable field in the Stock search bottom sheet, chips, and result tables.
 */
enum class FilterField(val queryKey: String, val displayLabel: String, val isPrimary: Boolean) {
    ARTICLE("article", "Article", isPrimary = true),
    BRAND("brand", "Brand", isPrimary = true),
    BARCODE("barcode", "Barcode", isPrimary = true),
    NAME("name", "Name", isPrimary = true),
    PARTY("party", "Party", isPrimary = true), // Admin only
    COLOR("color", "Color", isPrimary = false),
    SIZE("size", "Size", isPrimary = false)
}

/**
 * Immutable representation of all active search filters.
 *
 * Enforces the Strict Non-Solo Rule for Size & Color:
 * - Primary filters: `article`, `brand`, `barcode`, `brandcode`, `name`, and `party` (admin only).
 * - Secondary filters: `color`, `size`.
 */
data class FilterState(
    val article: String = "",
    val brand: String = "",
    val barcode: String = "",
    val brandcode: String = "",
    val name: String = "",
    val party: String = "",
    val color: String = "",
    val size: String = ""
) {
    /**
     * Returns true if at least one primary filter is non-blank.
     * For employees (`isAdmin == false`), `party` never counts as a valid primary filter.
     */
    fun hasPrimaryFilter(isAdmin: Boolean): Boolean {
        if (
            article.isNotBlank() ||
            brand.isNotBlank() ||
            barcode.isNotBlank() ||
            brandcode.isNotBlank() ||
            name.isNotBlank()
        ) {
            return true
        }
        if (isAdmin && party.isNotBlank()) {
            return true
        }
        return false
    }

    /**
     * Returns true if only `size` and/or `color` are non-blank and zero primary filters are active.
     */
    fun hasOnlySecondaryFilters(isAdmin: Boolean): Boolean {
        return !hasPrimaryFilter(isAdmin) && (color.isNotBlank() || size.isNotBlank())
    }

    fun isEmpty(isAdmin: Boolean): Boolean {
        return !hasPrimaryFilter(isAdmin) && color.isBlank() && size.isBlank()
    }

    /**
     * Sanitizes this [FilterState] for the caller's role and enforces the Non-Solo Rule:
     * - Strips `party` if `!isAdmin`.
     * - If no primary filter remains (such that only `size` and/or `color` would remain),
     *   immediately clears the entire filter state to blank (`FilterState()`).
     */
    fun enforceRoleAndNonSoloRule(isAdmin: Boolean): FilterState {
        val roleChecked = if (isAdmin) this else copy(party = "")
        return if (!roleChecked.hasPrimaryFilter(isAdmin)) {
            FilterState()
        } else {
            roleChecked
        }
    }

    /**
     * Returns the value of a specific [FilterField].
     */
    fun getValue(field: FilterField): String {
        return when (field) {
            FilterField.ARTICLE -> article
            FilterField.BRAND -> brand
            FilterField.BARCODE -> barcode.ifBlank { brandcode }
            FilterField.NAME -> name
            FilterField.PARTY -> party
            FilterField.COLOR -> color
            FilterField.SIZE -> size
        }
    }

    /**
     * Returns a new [FilterState] with [field] updated to [newValue].
     */
    fun withField(field: FilterField, newValue: String): FilterState {
        val clean = newValue.trim()
        return when (field) {
            FilterField.ARTICLE -> copy(article = clean)
            FilterField.BRAND -> copy(brand = clean)
            FilterField.BARCODE -> copy(barcode = clean, brandcode = "")
            FilterField.NAME -> copy(name = clean)
            FilterField.PARTY -> copy(party = clean)
            FilterField.COLOR -> copy(color = clean)
            FilterField.SIZE -> copy(size = clean)
        }
    }

    /**
     * Returns an ordered list of active `(FilterField, value)` pairs for rendering Applied Filter chips.
     */
    fun activeFilterEntries(isAdmin: Boolean): List<Pair<FilterField, String>> {
        val list = mutableListOf<Pair<FilterField, String>>()
        if (article.isNotBlank()) list.add(FilterField.ARTICLE to article)
        if (brand.isNotBlank()) list.add(FilterField.BRAND to brand)
        val code = barcode.ifBlank { brandcode }
        if (code.isNotBlank()) list.add(FilterField.BARCODE to code)
        if (name.isNotBlank()) list.add(FilterField.NAME to name)
        if (isAdmin && party.isNotBlank()) list.add(FilterField.PARTY to party)
        if (color.isNotBlank()) list.add(FilterField.COLOR to color)
        if (size.isNotBlank()) list.add(FilterField.SIZE to size)
        return list
    }

    /**
     * Formats a human-readable summary string for Search History & Offline Scope titles.
     */
    fun toDisplaySummary(isAdmin: Boolean): String {
        return activeFilterEntries(isAdmin).joinToString(" • ") { (field, value) ->
            "${field.displayLabel}: $value"
        }
    }

    fun toDto(isAdmin: Boolean): StockSearchFilterDto {
        return StockSearchFilterDto(
            article = article.takeIf { it.isNotBlank() },
            brand = brand.takeIf { it.isNotBlank() },
            barcode = barcode.takeIf { it.isNotBlank() },
            brandcode = brandcode.takeIf { it.isNotBlank() },
            name = name.takeIf { it.isNotBlank() },
            party = if (isAdmin) party.takeIf { it.isNotBlank() } else null,
            color = color.takeIf { it.isNotBlank() },
            size = size.takeIf { it.isNotBlank() }
        )
    }

    companion object {
        fun fromDto(dto: StockSearchFilterDto, isAdmin: Boolean): FilterState {
            return FilterState(
                article = dto.article?.trim().orEmpty(),
                brand = dto.brand?.trim().orEmpty(),
                barcode = dto.barcode?.trim().orEmpty(),
                brandcode = dto.brandcode?.trim().orEmpty(),
                name = dto.name?.trim().orEmpty(),
                party = if (isAdmin) dto.party?.trim().orEmpty() else "",
                color = dto.color?.trim().orEmpty(),
                size = dto.size?.trim().orEmpty()
            ).enforceRoleAndNonSoloRule(isAdmin)
        }
    }
}
