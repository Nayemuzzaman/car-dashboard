package com.csjotlab.cardashboard.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.ui.theme.DashboardAccent
import com.csjotlab.cardashboard.ui.theme.DashboardSpacing
import com.csjotlab.cardashboard.ui.theme.DashboardTextMuted
import com.csjotlab.cardashboard.ui.theme.DashboardWarning

/**
 * The wording is fixed by the spec, character for character, and is a constant so that the banner,
 * the JVM test that pins the em dash and any future caller cannot drift apart.
 */
const val SIMULATED_DATA_LABEL = "SIMULATED DATA — NOT A REAL VEHICLE"

/** Legible against [DashboardWarning]; the same near-black the selected mode chip uses. */
private val OnWarning = Color(0xFF03111D)

/**
 * States where the vehicle data is coming from, and offers the one action that can change it when
 * there is one.
 *
 * Deliberately one line tall. It sits above the panels in both layouts, and in landscape that
 * height comes straight out of a column budget that has already clipped once (commit `ecf85cd`).
 */
@Composable
fun ConnectionBanner(
    label: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Width comes from the caller. Portrait hands it the full width; the landscape row shares a
    // single line between this and the simulation banner, so it must be able to take a weight.
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = DashboardTextMuted,
        )
        if (actionLabel != null) {
            ActionChip(label = actionLabel, onClick = onAction)
        }
    }
}

/**
 * Shown whenever the active source is the mock. It is not decoration: it is the only thing on
 * screen telling the driver that none of these readings came from a vehicle, so it is persistent,
 * full width, and worded as bluntly as the spec requires.
 */
@Composable
fun SimulationBanner(modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = DashboardWarning,
    ) {
        Text(
            text = SIMULATED_DATA_LABEL,
            modifier = Modifier
                .fillMaxWidth()
                // Compact is the bounded-height landscape layout, where every dp this banner
                // takes comes out of the fourth warning row. The wording never shrinks; only the
                // padding around it does.
                .padding(
                    vertical = if (compact) DashboardSpacing.tight else DashboardSpacing.small,
                    horizontal = DashboardSpacing.compact,
                ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = OnWarning,
            textAlign = TextAlign.Center,
        )
    }
}

/** The `ModeChip` treatment, without the selection state a mode chip carries. */
@Composable
private fun ActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = DashboardAccent,
        shape = RoundedCornerShape(8.dp),
        onClick = onClick,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(
                vertical = DashboardSpacing.tight,
                horizontal = DashboardSpacing.compact,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = OnWarning,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun ConnectionBannerPreview() {
    CarDashboardTheme {
        ConnectionBanner(
            label = "USB permission required",
            actionLabel = "Grant USB access",
            onAction = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun SimulationBannerPreview() {
    CarDashboardTheme {
        SimulationBanner()
    }
}
