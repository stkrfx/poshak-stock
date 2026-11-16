package com.poshak.stock.domain.logic

import com.poshak.stock.domain.model.CommonValueChip
import com.poshak.stock.domain.model.FilterField
import com.poshak.stock.domain.model.FilterState
import com.poshak.stock.domain.model.ProductWithStocks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes Common Value chips 100% in the Kotlin frontend per Section 9.4.2:
 * - Inspects all rows in the current filtered result set.
 * - If every product in the result set shares the same non-null, non-empty value for
 *   `MRP`, `Brand`, `Article`, `Name`, `Color`, or `Party` (admin only)—or if every stock row
 *   shares a single `Size`—and that field is not already an Applied Filter, it is displayed
 *   as a Common Value chip.
 * - `MRP` (when common across all rows) uses a prominent Material 3 tonal badge (`MRP: ₹1499`).
 */
@Singleton
class CommonValuesCalculator @Inject constructor() {

    fun calculateCommonValues(
        products: List<ProductWithStocks>,
        activeFilter: FilterState,
        isAdmin: Boolean
    ): List<CommonValueChip> {
        if (products.isEmpty()) return emptyList()

        val chips = mutableListOf<CommonValueChip>()

        // 1. Common MRP Badge (prominent Material 3 tonal badge: "MRP: ₹1499")
        val firstMrp = products.first().mrp
        if (firstMrp > 0 && products.all { it.mrp == firstMrp }) {
            chips.add(
                CommonValueChip(
                    key = "mrp",
                    label = "MRP",
                    displayValue = "₹$firstMrp",
                    rawValue = firstMrp.toString(),
                    filterField = null,
                    isMrpTonalBadge = true
                )
            )
        }

        // 2. Common Article (if not already an Applied Filter)
        if (activeFilter.article.isBlank()) {
            findCommonString(products.map { it.article })?.let { commonArticle ->
                chips.add(
                    CommonValueChip(
                        key = "article",
                        label = "Article",
                        displayValue = commonArticle,
                        rawValue = commonArticle,
                        filterField = FilterField.ARTICLE
                    )
                )
            }
        }

        // 3. Common Brand (if not already an Applied Filter)
        if (activeFilter.brand.isBlank()) {
            findCommonString(products.map { it.brand })?.let { commonBrand ->
                chips.add(
                    CommonValueChip(
                        key = "brand",
                        label = "Brand",
                        displayValue = commonBrand,
                        rawValue = commonBrand,
                        filterField = FilterField.BRAND
                    )
                )
            }
        }

        // 4. Common Name (if not already an Applied Filter)
        if (activeFilter.name.isBlank()) {
            findCommonString(products.map { it.name })?.let { commonName ->
                chips.add(
                    CommonValueChip(
                        key = "name",
                        label = "Name",
                        displayValue = commonName,
                        rawValue = commonName,
                        filterField = FilterField.NAME
                    )
                )
            }
        }

        // 5. Common Party (ONLY for admin and if not already an Applied Filter)
        if (isAdmin && activeFilter.party.isBlank()) {
            findCommonString(products.map { it.party })?.let { commonParty ->
                chips.add(
                    CommonValueChip(
                        key = "party",
                        label = "Party",
                        displayValue = commonParty,
                        rawValue = commonParty,
                        filterField = FilterField.PARTY
                    )
                )
            }
        }

        // 6. Common Color (if not already an Applied Filter)
        if (activeFilter.color.isBlank()) {
            findCommonString(products.map { it.color })?.let { commonColor ->
                chips.add(
                    CommonValueChip(
                        key = "color",
                        label = "Color",
                        displayValue = commonColor,
                        rawValue = commonColor,
                        filterField = FilterField.COLOR
                    )
                )
            }
        }

        // 7. Common Size across all stock variant rows (if not already an Applied Filter)
        if (activeFilter.size.isBlank()) {
            val allStockSizes = products.flatMap { product ->
                product.stocks.filter { it.quantity > 0 || products.all { p -> p.totalQuantity == 0 } }
                    .map { it.size }
            }
            if (allStockSizes.isNotEmpty()) {
                findCommonString(allStockSizes)?.let { commonSize ->
                    chips.add(
                        CommonValueChip(
                            key = "size",
                            label = "Size",
                            displayValue = commonSize,
                            rawValue = commonSize,
                            filterField = FilterField.SIZE
                        )
                    )
                }
            }
        }

        return chips
    }

    /**
     * Returns the canonical trimmed string if 100% of [values] are non-null, non-blank,
     * and equal (case-insensitively); otherwise returns null.
     */
    private fun findCommonString(values: List<String?>): String? {
        if (values.isEmpty()) return null
        val first = values.first()?.trim().orEmpty()
        if (first.isEmpty()) return null
        val allSame = values.all { candidate ->
            val clean = candidate?.trim().orEmpty()
            clean.isNotEmpty() && clean.equals(first, ignoreCase = true)
        }
        return if (allSame) first else null
    }
}
