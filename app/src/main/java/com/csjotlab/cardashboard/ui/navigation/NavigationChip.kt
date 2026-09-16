package com.csjotlab.cardashboard.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.csjotlab.cardashboard.ui.theme.DashboardAccent
import com.csjotlab.cardashboard.ui.theme.DashboardSpacing

/** The dashboard entry point into the navigation screen. */
@Composable
fun NavigationChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = DashboardAccent,
        onClick = onClick,
    ) {
        Text(
            text = "Navigate",
            modifier = Modifier.padding(
                vertical = DashboardSpacing.tight,
                horizontal = DashboardSpacing.small,
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF03111D),
        )
    }
}
