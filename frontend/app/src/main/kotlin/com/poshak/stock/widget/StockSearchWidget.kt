package com.poshak.stock.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.poshak.stock.MainActivity
import com.poshak.stock.ui.main.StockMainViewModel

/**
 * Unified Home-Screen Widget built with Jetpack Glance (Section 1.8 & Section 9.6).
 *
 * Provides two distinct click targets with zero validation exceptions:
 * 1. Search Bar Target ("Search article number..."):
 *    - Launches `MainActivity` with `ACTION_OPEN_ARTICLE_SEARCH`.
 *    - Opens the exact same `StockMainScreen`, automatically opens `FilterBottomSheet`,
 *      and focuses the `Article` input field with the keyboard open.
 * 2. Scanner Icon Target ("Scan"):
 *    - Launches `MainActivity` with `ACTION_OPEN_BARCODE_SCANNER`.
 *    - Opens the ML Kit camera scanner directly and renders results on the exact same `StockMainScreen`.
 */
class StockSearchWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val articleSearchIntent = Intent(context, MainActivity::class.java).apply {
            action = StockMainViewModel.ACTION_OPEN_ARTICLE_SEARCH
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val barcodeScannerIntent = Intent(context, MainActivity::class.java).apply {
            action = StockMainViewModel.ACTION_OPEN_BARCODE_SCANNER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        provideContent {
            GlanceTheme {
                WidgetContent(
                    articleSearchIntent = articleSearchIntent,
                    barcodeScannerIntent = barcodeScannerIntent
                )
            }
        }
    }

    @Composable
    private fun WidgetContent(
        articleSearchIntent: Intent,
        barcodeScannerIntent: Intent
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(24.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Target 1: Search Bar Target ("Search article number...")
            Row(
                modifier = GlanceModifier
                    .defaultWeight()
                    .background(GlanceTheme.colors.surfaceVariant)
                    .cornerRadius(18.dp)
                    .clickable(actionStartActivity(articleSearchIntent))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Stock",
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(
                    text = "Search article number...",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 13.sp
                    ),
                    maxLines = 1
                )
            }

            Spacer(modifier = GlanceModifier.width(8.dp))

            // Target 2: Barcode Scanner Target
            Box(
                modifier = GlanceModifier
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(18.dp)
                    .clickable(actionStartActivity(barcodeScannerIntent))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Scan",
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                )
            }
        }
    }
}
