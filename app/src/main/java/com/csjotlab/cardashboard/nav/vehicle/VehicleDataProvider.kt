package com.csjotlab.cardashboard.nav.vehicle

import com.csjotlab.cardashboard.vehicle.data.VehicleSnapshot
import com.csjotlab.cardashboard.vehicle.domain.Gear
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What the vehicle itself reports that navigation can use. Every field is nullable: null means the
 * connected source does not supply it, never "zero".
 */
data class VehicleMotion(
    val speedMps: Float?,
    /** Vehicle heading from an OEM/AAOS source, degrees from north. */
    val headingDegrees: Float?,
    val gear: Gear?,
    val ignitionOn: Boolean?,
    /** When the newest of these values was measured. */
    val timestampMs: Long,
)

/**
 * The navigation-side seam to vehicle integrations. Navigation works without any of it — Android
 * location is the reliable baseline — so implementations only *add* signals: an AAOS
 * `CarPropertyManager` source, a USB/serial or CAN adapter, an OEM API. Emits null when nothing
 * usable is known.
 */
interface VehicleDataProvider {
    val motion: Flow<VehicleMotion?>
}

/** No vehicle integration: navigation runs on Android location alone. */
object NoVehicleDataProvider : VehicleDataProvider {
    override val motion: Flow<VehicleMotion?> = flowOf(null)
}

/**
 * Adapts the dashboard's [com.csjotlab.cardashboard.vehicle.data.VehicleRepository] (OBD-II today).
 *
 * Forwards only what the source genuinely reported: speed and gear. OBD-II has no standard PID for
 * heading or ignition state, so those stay null rather than being inferred (e.g. "RPM > 0 means the
 * ignition is on" is false for hybrids and auto start-stop). Snapshots from the simulated MOCK source
 * are dropped: simulated data must never steer real navigation.
 */
class VehicleRepositoryDataProvider(snapshots: Flow<VehicleSnapshot>) : VehicleDataProvider {
    override val motion: Flow<VehicleMotion?> = snapshots
        .map { snapshot -> toMotion(snapshot) }
        .distinctUntilChanged()

    internal companion object {
        fun toMotion(snapshot: VehicleSnapshot): VehicleMotion? {
            val state = snapshot.state
            if (state.source == VehicleSourceId.MOCK || state.source == VehicleSourceId.NONE) return null
            val speed = state.speedKph as? Signal.Value
            val gear = state.gear as? Signal.Value
            val newest = listOfNotNull(speed?.timestampMs, gear?.timestampMs).maxOrNull() ?: return null
            return VehicleMotion(
                speedMps = speed?.value?.let { it / 3.6f },
                headingDegrees = null,
                gear = gear?.value,
                ignitionOn = null,
                timestampMs = newest,
            )
        }
    }
}
