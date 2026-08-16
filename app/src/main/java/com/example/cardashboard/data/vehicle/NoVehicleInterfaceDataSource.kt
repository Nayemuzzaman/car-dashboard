package com.example.cardashboard.data.vehicle

import com.example.cardashboard.domain.model.VehicleDataState
import com.example.cardashboard.domain.model.VehicleDataUnavailableReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The real-vehicle slot of the app.
 *
 * This build targets ordinary Android phones and tablets, which have no supported way to read a
 * vehicle's speed, revs or fuel level. Rather than inventing numbers, the source says so and the
 * dashboard shows an honest "no vehicle interface" state.
 *
 * A genuine integration — Android Automotive OS `CarPropertyManager`, or a manufacturer SDK the app
 * is actually authorised to use — would replace this class and nothing else: the repository, the
 * ViewModel and the UI already handle [VehicleDataState.Available] from any source.
 */
class NoVehicleInterfaceDataSource : VehicleDataSource {

    override fun observe(): Flow<VehicleDataState> = flowOf(
        VehicleDataState.Unavailable(VehicleDataUnavailableReason.NO_SUPPORTED_INTERFACE)
    )
}
