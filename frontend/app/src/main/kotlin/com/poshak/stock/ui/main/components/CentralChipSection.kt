package com.poshak.stock.ui.main.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.poshak.stock.domain.model.CommonValueChip
import com.poshak.stock.domain.model.FilterField
import com.poshak.stock.domain.model.FilterState

/**
 * Centralized Chip Area located directly above the result table inside a wrapping `FlowRow`
 * (bounded in height and vertically scrollable if chips wrap to many lines).
 *
 * Implements Section 9.4:
 * 1. Applied Filters Group:
 *    - Displays every active filter (e.g., `Article: 1578`, `Brand: KILLER`).
 *    - Trailing `X` icon removes that filter (triggering the full-clear rule if only `Size`/`Color` remain).
 *    - Includes a `Clear All` action chip.
 *    - Single-Tap: Opens the Filter Bottom Sheet and immediately focuses that specific filter field's input.
 * 2. Common Values Group (Computed 100% in Kotlin Frontend):
 *    - `MRP` (when common across all rows) uses a prominent Material 3 tonal badge (`MRP: ₹1499`).
 *    - Single-Tap on a filterable Common Value chip: Opens the Filter Bottom Sheet and focuses
 *      that specific filter field's input.
 *    - Double-Tap on an unapplied filterable Common Value chip: Promotes that chip's value into
 *      an active Applied Filter.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CentralChipSection(
    activeFilter: FilterState,
    commonValueChips: List<CommonValueChip>,
    isAdmin: Boolean,
    onSingleTapFocusField: (FilterField) -> Unit,
    onRemoveAppliedFilter: (FilterField) -> Unit,
    onClearAllFilters: () -> Unit,
    onDoubleTapPromoteCommonChip: (FilterField, String) -> Unit
) {
    val appliedEntries = activeFilter.activeFilterEntries(isAdmin)
    if (appliedEntries.isEmpty() && commonValueChips.isEmpty()) {
        return
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 136.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 1. Applied Filters Group
                appliedEntries.forEach { (field, value) ->
                    AppliedFilterChipItem(
                        label = "${field.displayLabel}: $value",
                        onSingleTap = { onSingleTapFocusField(field) },
                        onRemoveClick = { onRemoveAppliedFilter(field) }
                    )
                }

                if (appliedEntries.isNotEmpty()) {
                    ClearAllChipItem(onClick = onClearAllFilters)
                }

                // 2. Common Values Group (Frontend Computed)
                commonValueChips.forEach { chip ->
                    if (chip.isMrpTonalBadge) {
                        ProminentMrpTonalBadge(text = chip.formattedChipText)
                    } else {
                        FilterableCommonValueChipItem(
                            text = chip.formattedChipText,
                            onSingleTap = {
                                chip.filterField?.let { onSingleTapFocusField(it) }
                            },
                            onDoubleTap = {
                                chip.filterField?.let { field ->
                                    onDoubleTapPromoteCommonChip(field, chip.rawValue)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppliedFilterChipItem(
    label: String,
    onSingleTap: () -> Unit,
    onRemoveClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onSingleTap,
                onDoubleClick = onSingleTap
            ),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            IconButton(
                onClick = onRemoveClick,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove $label",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClearAllChipItem(
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DeleteSweep,
                contentDescription = "Clear All Filters",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "Clear All",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun ProminentMrpTonalBadge(
    text: String
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Sell,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterableCommonValueChipItem(
    text: String,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onSingleTap,
                onDoubleClick = onDoubleTap
            ),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
        shape = RoundedCornerShape(8.dp),
        border = AssistChipDefaults.assistChipBorder(enabled = true)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
