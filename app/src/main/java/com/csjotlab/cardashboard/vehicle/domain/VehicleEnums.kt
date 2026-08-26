package com.csjotlab.cardashboard.vehicle.domain

enum class VehicleSourceId { NONE, MOCK, OBD_USB }

sealed interface Gear {
    data object Park : Gear
    data object Reverse : Gear
    data object Neutral : Gear
    data object Drive : Gear
    data object Low : Gear
    data class Manual(val position: Int) : Gear
    data object Unknown : Gear
}

enum class DoorPosition { FrontLeft, FrontRight, RearLeft, RearRight, Hood, Trunk }

enum class DoorState { Open, Closed }

enum class SeatPosition { Driver, FrontPassenger, RearLeft, RearCenter, RearRight }

enum class SeatbeltState { Buckled, Unbuckled }

enum class TirePosition { FrontLeft, FrontRight, RearLeft, RearRight }
