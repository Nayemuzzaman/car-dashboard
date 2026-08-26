package com.csjotlab.cardashboard.vehicle.source

import app.cash.turbine.test
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.fakes.ControllableVehicleDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleSourceSelectorTest {

    private fun mockFactory(): VehicleDataSource = ControllableVehicleDataSource(VehicleSourceId.MOCK)

    @Test
    fun `a real source always wins`() = runTest {
        val real = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val selector = VehicleSourceSelector(
            realSourceAvailability = MutableStateFlow(real),
            mockModeEnabled = MutableStateFlow(true),
            debugBuild = true,
            mockSourceFactory = ::mockFactory,
        )

        selector.activeSource.test {
            assertEquals(VehicleSourceId.OBD_USB, awaitItem()?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no real source and mock off means no source at all`() = runTest {
        val selector = VehicleSourceSelector(
            realSourceAvailability = MutableStateFlow(null),
            mockModeEnabled = MutableStateFlow(false),
            debugBuild = true,
            mockSourceFactory = ::mockFactory,
        )

        selector.activeSource.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mock requires both the debug build and the explicit toggle`() = runTest {
        val selector = VehicleSourceSelector(
            realSourceAvailability = MutableStateFlow(null),
            mockModeEnabled = MutableStateFlow(true),
            debugBuild = true,
            mockSourceFactory = ::mockFactory,
        )

        selector.activeSource.test {
            assertEquals(VehicleSourceId.MOCK, awaitItem()?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `release builds never resolve to mock however the toggle is set`() = runTest {
        val selector = VehicleSourceSelector(
            realSourceAvailability = MutableStateFlow(null),
            mockModeEnabled = MutableStateFlow(true),
            debugBuild = false,
            mockSourceFactory = ::mockFactory,
        )

        selector.activeSource.test {
            assertNull("mock must be unreachable outside debug builds", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `losing the real source falls back to nothing not to mock`() = runTest {
        val real = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val availability = MutableStateFlow<VehicleDataSource?>(real)
        val selector = VehicleSourceSelector(
            realSourceAvailability = availability,
            mockModeEnabled = MutableStateFlow(false),
            debugBuild = true,
            mockSourceFactory = ::mockFactory,
        )

        selector.activeSource.test {
            assertEquals(VehicleSourceId.OBD_USB, awaitItem()?.id)
            availability.value = null
            assertNull("a failing real source must never be replaced by fake values", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
