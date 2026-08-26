package com.csjotlab.cardashboard.vehicle.fakes

import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.source.VehicleDataSource
import com.csjotlab.cardashboard.vehicle.source.VehicleSourceActivation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** A source that fails the way a malformed adapter frame would: by throwing. */
class ThrowingVehicleDataSource(
    override val id: VehicleSourceId,
    private val failure: Throwable = IllegalStateException("adapter frame was garbage"),
    private val failOnStart: Boolean = false,
) : VehicleDataSource {

    override val vehicleState: Flow<VehicleState> =
        if (failOnStart) flowOf(VehicleState.unavailable(id)) else flow { throw failure }

    override val diagnostics: Flow<VehicleDiagnosticsState> = flowOf(VehicleDiagnosticsState.empty())

    override val connectionState: Flow<VehicleConnectionState> =
        flowOf(VehicleConnectionState.Reading)

    override val liveness: Flow<Long> = emptyFlow()

    override suspend fun start(): VehicleSourceActivation {
        if (failOnStart) throw failure
        return VehicleSourceActivation { }
    }

    override suspend fun stop() = Unit
}
