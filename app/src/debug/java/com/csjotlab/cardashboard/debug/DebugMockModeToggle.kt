package com.csjotlab.cardashboard.debug

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csjotlab.cardashboard.CarDashboardApplication
import com.csjotlab.cardashboard.ui.theme.DashboardSpacing
import com.csjotlab.cardashboard.ui.theme.DashboardSurfaceHigh
import com.csjotlab.cardashboard.ui.theme.DashboardTextMuted
import com.csjotlab.cardashboard.ui.theme.DashboardWarning

internal const val MOCK_TOGGLE_ON = "MOCK ON"
internal const val MOCK_TOGGLE_OFF = "MOCK OFF"

/**
 * The one production caller of `VehicleContainer.setMockModeEnabled`.
 *
 * ## Why this exists
 *
 * Without it the toggle half of the mock gate has no way of being set on a real launch, so the
 * simulated-data path — and the banner that warns about it — is unreachable outside a test harness.
 * The device checkpoint has to exercise the mock *through the architecture*, not by hand-feeding
 * Compose state, and that needs a real switch.
 *
 * ## Why this is safe
 *
 * This file lives in the **`debug` source set**. The `release` source set carries a same-signature
 * no-op in its place, so this composable is not merely hidden in a release build — it is not
 * compiled into one, and neither are its strings. It also supplies only the *toggle* half of the
 * gate: `VehicleSourceSelector` still requires `BuildConfig.DEBUG` as well and still never
 * auto-selects the mock. Nothing here can weaken either of those.
 *
 * ## Why nothing is cached
 *
 * `VehicleContainer` is rebuilt after `shutdown()`, so a container held in a `remember`, a field or
 * a captured lambda would outlive its graph and drive a dead one. The container is therefore read
 * through the `Application` on every composition *and* again inside the click handler; only the
 * `Application` itself — whose identity does not change — is ever held across a frame.
 */
@Composable
fun DebugMockModeToggle(modifier: Modifier = Modifier) {
    // Not `error(...)` as `CarDashboardApp` does: a debug affordance must never be the thing that
    // crashes a preview or a test host that installs the default Application.
    val application = LocalContext.current.applicationContext as? CarDashboardApplication ?: return

    // Read fresh, never remembered. `collectAsStateWithLifecycle` keys its subscription on the flow
    // instance, so a rebuilt container re-subscribes rather than reporting the dead one's state.
    val enabled by application.container.mockModeEnabled.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) DashboardWarning else DashboardSurfaceHigh,
        onClick = {
            // Deliberately re-read rather than closing over the container resolved above.
            val live = application.container
            live.setMockModeEnabled(!live.mockModeEnabled.value)
        },
    ) {
        Text(
            text = if (enabled) MOCK_TOGGLE_ON else MOCK_TOGGLE_OFF,
            modifier = Modifier.padding(
                vertical = DashboardSpacing.tight,
                horizontal = DashboardSpacing.small,
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color(0xFF03111D) else DashboardTextMuted,
        )
    }
}
