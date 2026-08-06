package com.example.cardashboard.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cardashboard.ui.theme.DashboardSpacing
import com.example.cardashboard.ui.theme.LocalDashboardColors

/** The card every cluster group sits in. One place to change the panel look. */
@Composable
fun DashboardPanel(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = DashboardSpacing.medium,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalDashboardColors.current
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = colors.panel
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, colors.panelBorder, MaterialTheme.shapes.medium)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
            content = content
        )
    }
}

/** Panel title on the left, an optional short status on the right. */
@Composable
fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    trailingColor: Color? = null
) {
    val colors = LocalDashboardColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = trailingColor ?: colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A labelled value, the building block of every readout in the cluster. */
@Composable
fun Readout(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null
) {
    val colors = LocalDashboardColors.current
    Column(
        // Merged so a screen reader announces "Odometer, 38,421 km" instead of two stray nodes.
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tiny)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}
