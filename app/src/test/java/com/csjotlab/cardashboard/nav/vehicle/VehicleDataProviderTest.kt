package com.csjotlab.cardashboard.nav.vehicle

import com.csjotlab.cardashboard.vehicle.data.VehicleSnapshot
import com.csjotlab.cardashboard.vehicle.domain.Gear
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleDataProviderTest {

    private fun snapshot(source: VehicleSourceId, speedKph: Signal<Float> = Signal.Value(36f, 1_000L), gear: Signal<Gear> = Signal.Unknown) =
        VehicleSnapshot.Disconnected.copy(
            state = VehicleState.unavailable(source).copy(speedKph = speedKph, gear = gear),
        )

    @Test
    fun `a real source's speed and gear reach navigation, in metres per second`() {
        val motion = VehicleRepositoryDataProvider.toMotion(snapshot(VehicleSourceId.OBD_USB, gear = Signal.Value(Gear.Drive, 2_000L)))!!
        assertEquals(10f, motion.speedMps!!, 0.001f)
        assertEquals(Gear.Drive, motion.gear)
        assertEquals(2_000L, motion.timestampMs)
    }

    @Test
    fun `heading and ignition are never inferred from OBD data`() {
        val motion = VehicleRepositoryDataProvider.toMotion(snapshot(VehicleSourceId.OBD_USB))!!
        assertNull(motion.headingDegrees)
        assertNull(motion.ignitionOn)
    }

    @Test
    fun `simulated data never steers navigation`() {
        assertNull(VehicleRepositoryDataProvider.toMotion(snapshot(VehicleSourceId.MOCK)))
    }

    @Test
    fun `nothing reported means nothing forwarded`() {
        assertNull(VehicleRepositoryDataProvider.toMotion(snapshot(VehicleSourceId.OBD_USB, speedKph = Signal.Unknown)))
    }
}
