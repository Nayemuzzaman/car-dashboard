package com.example.cardashboard.data.demo

import com.example.cardashboard.domain.model.ChargingState
import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.EnergyState
import com.example.cardashboard.domain.model.Gear
import com.example.cardashboard.domain.model.Indicators
import com.example.cardashboard.domain.model.Temperatures
import com.example.cardashboard.domain.model.TripData
import com.example.cardashboard.domain.model.VehicleState
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.domain.model.sanitized
import kotlin.math.abs
import kotlin.math.sin

/**
 * Generates the demo drive cycle. **This is simulated data, not a vehicle.**
 *
 * The simulator is a plain class with no coroutines, no clock and no randomness: calling [step] the
 * same number of times from the same starting point always produces the same values, which is what
 * makes the demo testable. [DemoVehicleDataSource] is the piece that turns it into a timed flow.
 *
 * One cycle lasts [CYCLE_SECONDS] and walks through park, reverse, neutral, a full drive with
 * accelerations and braking, and back to park, lighting each telltale in a fixed window so every
 * dashboard state can be reviewed without a vehicle.
 */
class DemoVehicleSimulator(
    private val vehicleType: VehicleType,
    private val tickMillis: Long = DEFAULT_TICK_MILLIS
) {

    private var tick: Long = 0
    private var driveMode: DriveMode = DriveMode.NORMAL
    private var odometerKm: Double = INITIAL_ODOMETER_KM
    private var tripDistanceKm: Double = 0.0
    private var tripDrivingMillis: Long = 0
    private var tripEnergyUsed: Double = 0.0
    private var energyPercent: Float = initialEnergyPercent()
    private var previousSpeedKmh: Float = 0f
    private var completedCycles: Long = 0

    /** Records the requested mode. The simulation reacts to it; no real vehicle is commanded. */
    fun setDriveMode(mode: DriveMode) {
        driveMode = mode
    }

    /** Clears the trip computer only. The odometer keeps counting, as it does in a real car. */
    fun resetTrip() {
        tripDistanceKm = 0.0
        tripDrivingMillis = 0
        tripEnergyUsed = 0.0
    }

    /** Returns the simulation to its initial state, including the odometer and the energy level. */
    fun reset() {
        tick = 0
        driveMode = DriveMode.NORMAL
        odometerKm = INITIAL_ODOMETER_KM
        energyPercent = initialEnergyPercent()
        previousSpeedKmh = 0f
        completedCycles = 0
        resetTrip()
    }

    /** Advances the simulation by one tick and returns the resulting snapshot. */
    fun step(): VehicleState {
        val cycleSeconds = cycleSeconds(tick)
        val phase = phaseAt(cycleSeconds)
        val speedKmh = speedAt(cycleSeconds, phase)
        val deltaHours = tickMillis / 3_600_000.0
        val distanceThisTick = speedKmh * deltaHours

        odometerKm += distanceThisTick
        if (phase.gear.allowsMotion) {
            tripDistanceKm += distanceThisTick
            tripDrivingMillis += tickMillis
        }
        tripEnergyUsed += distanceThisTick * consumptionPerKm()

        val charging = isCharging(phase)
        energyPercent = if (charging) {
            (energyPercent + CHARGE_PERCENT_PER_SECOND * tickMillis / 1000f).coerceAtMost(100f)
        } else {
            (energyPercent - drainPercentPerTick()).coerceAtLeast(0f)
        }

        val decelerating = speedKmh < previousSpeedKmh - DECELERATION_EPSILON_KMH
        previousSpeedKmh = speedKmh

        tick++
        if (cycleSeconds(tick) < cycleSeconds) completedCycles++

        return VehicleState(
            speedKmh = speedKmh,
            rpm = rpmFor(speedKmh),
            gear = gearFor(phase),
            driveMode = driveMode,
            odometerKm = odometerKm,
            trip = TripData(
                distanceKm = tripDistanceKm,
                drivingTimeMillis = tripDrivingMillis,
                energyUsed = tripEnergyUsed
            ),
            energy = energyFor(charging, decelerating, speedKmh),
            temperatures = temperaturesAt(cycleSeconds),
            indicators = indicatorsAt(cycleSeconds, phase, decelerating)
        ).sanitized()
    }

    // --- drive cycle -------------------------------------------------------------------------

    private fun cycleSeconds(atTick: Long): Double =
        (atTick * tickMillis % CYCLE_MILLIS) / 1000.0

    private fun phaseAt(cycleSeconds: Double): Phase = when {
        cycleSeconds < PARK_START_END_SECONDS -> Phase.PARKED_START
        cycleSeconds < REVERSE_END_SECONDS -> Phase.REVERSING
        cycleSeconds < NEUTRAL_END_SECONDS -> Phase.NEUTRAL
        cycleSeconds < DRIVE_END_SECONDS -> Phase.DRIVING
        else -> Phase.PARKED_END
    }

    private fun speedAt(cycleSeconds: Double, phase: Phase): Float = when (phase) {
        Phase.PARKED_START, Phase.NEUTRAL, Phase.PARKED_END -> 0f
        // A short manoeuvre out of a parking space: up and back down again.
        Phase.REVERSING -> {
            val progress = (cycleSeconds - PARK_START_END_SECONDS) /
                (REVERSE_END_SECONDS - PARK_START_END_SECONDS)
            (REVERSE_PEAK_KMH * (1.0 - abs(progress * 2.0 - 1.0))).toFloat()
        }

        Phase.DRIVING -> interpolate(DRIVE_PROFILE, cycleSeconds) * driveMode.speedFactor()
    }

    /** Linear interpolation between the drive-cycle waypoints. */
    private fun interpolate(profile: List<Pair<Double, Float>>, seconds: Double): Float {
        val firstAfter = profile.indexOfFirst { it.first >= seconds }
        if (firstAfter <= 0) return profile.first().second
        val (previousSeconds, previousSpeed) = profile[firstAfter - 1]
        val (nextSeconds, nextSpeed) = profile[firstAfter]
        val span = nextSeconds - previousSeconds
        if (span <= 0.0) return nextSpeed
        val progress = ((seconds - previousSeconds) / span).toFloat()
        return previousSpeed + (nextSpeed - previousSpeed) * progress
    }

    private fun gearFor(phase: Phase): Gear = when (phase) {
        Phase.PARKED_START, Phase.PARKED_END -> Gear.PARK
        Phase.REVERSING -> Gear.REVERSE
        Phase.NEUTRAL -> Gear.NEUTRAL
        Phase.DRIVING -> if (driveMode == DriveMode.SPORT) Gear.SPORT else Gear.DRIVE
    }

    // --- powertrain --------------------------------------------------------------------------

    private fun rpmFor(speedKmh: Float): Int = when (vehicleType) {
        // An electric motor is geared straight to the wheels, so revs track road speed.
        VehicleType.ELECTRIC -> (speedKmh * ELECTRIC_RPM_PER_KMH).toInt()

        VehicleType.COMBUSTION -> if (speedKmh < IDLE_SPEED_THRESHOLD_KMH) {
            (IDLE_RPM * driveMode.rpmFactor()).toInt()
        } else {
            // Revs climb through a gear, then drop back as the transmission shifts up.
            val bandIndex = GEAR_BANDS_KMH.indexOfLast { speedKmh >= it }
                .coerceIn(0, GEAR_BANDS_KMH.lastIndex - 1)
            val low = GEAR_BANDS_KMH[bandIndex]
            val high = GEAR_BANDS_KMH[bandIndex + 1]
            val withinGear = ((speedKmh - low) / (high - low)).coerceIn(0f, 1f)
            val revs = LOWEST_DRIVING_RPM + withinGear * RPM_SPAN_WITHIN_GEAR
            (revs * driveMode.rpmFactor()).toInt()
        }
    }

    private fun energyFor(charging: Boolean, decelerating: Boolean, speedKmh: Float): EnergyState =
        when (vehicleType) {
            VehicleType.COMBUSTION -> EnergyState.Fuel(
                levelPercent = energyPercent,
                estimatedRangeKm = energyPercent / 100f * COMBUSTION_FULL_RANGE_KM
            )

            VehicleType.ELECTRIC -> EnergyState.Battery(
                levelPercent = energyPercent,
                estimatedRangeKm = energyPercent / 100f * ELECTRIC_FULL_RANGE_KM,
                chargingState = when {
                    charging && energyPercent >= 100f -> ChargingState.CHARGE_COMPLETE
                    charging -> ChargingState.CHARGING
                    else -> ChargingState.NOT_CHARGING
                },
                regenerativeBrakingActive = decelerating && speedKmh > REGEN_MIN_SPEED_KMH
            )
        }

    /** The demo EV plugs in whenever it is parked at the end of a cycle. */
    private fun isCharging(phase: Phase): Boolean =
        vehicleType == VehicleType.ELECTRIC && phase == Phase.PARKED_END && energyPercent < 100f

    private fun consumptionPerKm(): Double = when (vehicleType) {
        // Litres per km and kWh per km, adjusted by how hard the selected mode drives.
        VehicleType.COMBUSTION -> COMBUSTION_LITRES_PER_100KM / 100.0 * driveMode.consumptionFactor()
        VehicleType.ELECTRIC -> ELECTRIC_KWH_PER_100KM / 100.0 * driveMode.consumptionFactor()
    }

    /**
     * Demo drain is deliberately faster than real life so a reviewer can watch the level fall and
     * reach the low-energy telltale in a few minutes instead of a few hours.
     */
    private fun drainPercentPerTick(): Float =
        drainPercentPerCycle() * tickMillis / CYCLE_MILLIS.toFloat()

    private fun drainPercentPerCycle(): Float {
        val base = when (vehicleType) {
            VehicleType.COMBUSTION -> COMBUSTION_DRAIN_PERCENT_PER_CYCLE
            VehicleType.ELECTRIC -> ELECTRIC_DRAIN_PERCENT_PER_CYCLE
        }
        return base * driveMode.consumptionFactor().toFloat()
    }

    private fun initialEnergyPercent(): Float = when (vehicleType) {
        VehicleType.COMBUSTION -> INITIAL_FUEL_PERCENT
        VehicleType.ELECTRIC -> INITIAL_CHARGE_PERCENT
    }

    // --- sensors -----------------------------------------------------------------------------

    private fun temperaturesAt(cycleSeconds: Double): Temperatures {
        val elapsedSeconds = tick * tickMillis / 1000.0
        val outside = if (cycleSeconds in OUTSIDE_SENSOR_DROPOUT) {
            // Deliberate dropout so the "sensor unavailable" rendering is exercised.
            null
        } else {
            (OUTSIDE_BASE_CELSIUS + OUTSIDE_SWING_CELSIUS *
                sin(cycleSeconds / CYCLE_SECONDS * 2.0 * Math.PI)).toFloat()
        }

        val warmUp = (elapsedSeconds * WARM_UP_CELSIUS_PER_SECOND).toFloat()
        val heatSpike = if (cycleSeconds in HEAT_SPIKE_WINDOW) HEAT_SPIKE_CELSIUS else 0f
        val powertrain = when (vehicleType) {
            VehicleType.COMBUSTION ->
                (COLD_START_CELSIUS + warmUp).coerceAtMost(COOLANT_NORMAL_CELSIUS) + heatSpike

            VehicleType.ELECTRIC ->
                (COLD_START_CELSIUS + warmUp * 0.2f).coerceAtMost(PACK_NORMAL_CELSIUS)
        }
        return Temperatures(outsideCelsius = outside, powertrainCelsius = powertrain)
    }

    /**
     * Telltales follow fixed windows in the cycle. Turn signals stay on for whole seconds; the
     * blinking is a presentation detail handled by the UI, not by rapidly toggling this flag.
     */
    private fun indicatorsAt(
        cycleSeconds: Double,
        phase: Phase,
        decelerating: Boolean
    ): Indicators = Indicators(
        leftTurnSignal = cycleSeconds in LEFT_SIGNAL_WINDOW,
        rightTurnSignal = cycleSeconds in RIGHT_SIGNAL_WINDOW,
        hazardLights = cycleSeconds in HAZARD_WINDOW,
        highBeam = cycleSeconds in HIGH_BEAM_WINDOW,
        lowBeam = cycleSeconds !in HIGH_BEAM_WINDOW,
        parkingBrake = phase == Phase.PARKED_START || phase == Phase.PARKED_END,
        seatBeltUnfastened = cycleSeconds in SEAT_BELT_WINDOW,
        doorOpen = cycleSeconds in DOOR_OPEN_WINDOW,
        engineWarning = cycleSeconds in ENGINE_WARNING_WINDOW,
        tirePressureWarning = cycleSeconds in TIRE_PRESSURE_WINDOW,
        absWarning = decelerating && cycleSeconds in ABS_WINDOW,
        tractionControlWarning = cycleSeconds in TRACTION_WINDOW
        // lowEnergy and temperatureWarning are derived from the readings in VehicleState.sanitized().
    )

    private enum class Phase(val gear: Gear) {
        PARKED_START(Gear.PARK),
        REVERSING(Gear.REVERSE),
        NEUTRAL(Gear.NEUTRAL),
        DRIVING(Gear.DRIVE),
        PARKED_END(Gear.PARK)
    }

    private fun DriveMode.speedFactor(): Float = when (this) {
        DriveMode.ECO -> 0.88f
        DriveMode.NORMAL -> 1f
        DriveMode.SPORT -> 1.08f
    }

    private fun DriveMode.rpmFactor(): Float = when (this) {
        DriveMode.ECO -> 0.82f
        DriveMode.NORMAL -> 1f
        DriveMode.SPORT -> 1.24f
    }

    private fun DriveMode.consumptionFactor(): Double = when (this) {
        DriveMode.ECO -> 0.82
        DriveMode.NORMAL -> 1.0
        DriveMode.SPORT -> 1.35
    }

    companion object {
        const val DEFAULT_TICK_MILLIS = 200L
        const val CYCLE_SECONDS = 120.0
        const val INITIAL_ODOMETER_KM = 38_421.0

        private const val CYCLE_MILLIS = 120_000L

        private const val PARK_START_END_SECONDS = 4.0
        private const val REVERSE_END_SECONDS = 8.0
        private const val NEUTRAL_END_SECONDS = 10.0
        private const val DRIVE_END_SECONDS = 112.0
        private const val REVERSE_PEAK_KMH = 10.0

        /** Waypoints of the driving phase: cycle second to target speed in km/h. */
        private val DRIVE_PROFILE = listOf(
            10.0 to 0f,
            18.0 to 38f,
            26.0 to 52f,
            32.0 to 34f,
            40.0 to 68f,
            52.0 to 92f,
            64.0 to 108f,
            76.0 to 126f,
            88.0 to 88f,
            96.0 to 64f,
            104.0 to 30f,
            112.0 to 0f
        )

        private const val IDLE_RPM = 780f
        private const val IDLE_SPEED_THRESHOLD_KMH = 1f
        private const val LOWEST_DRIVING_RPM = 1_150f
        private const val RPM_SPAN_WITHIN_GEAR = 3_900f
        private val GEAR_BANDS_KMH = listOf(0f, 25f, 45f, 70f, 100f, 140f, 200f)
        private const val ELECTRIC_RPM_PER_KMH = 78f

        private const val INITIAL_FUEL_PERCENT = 68f
        private const val INITIAL_CHARGE_PERCENT = 34f
        private const val COMBUSTION_FULL_RANGE_KM = 620f
        private const val ELECTRIC_FULL_RANGE_KM = 380f
        private const val COMBUSTION_LITRES_PER_100KM = 7.2
        private const val ELECTRIC_KWH_PER_100KM = 17.4
        private const val COMBUSTION_DRAIN_PERCENT_PER_CYCLE = 4f
        private const val ELECTRIC_DRAIN_PERCENT_PER_CYCLE = 6f
        private const val CHARGE_PERCENT_PER_SECOND = 1.2f
        private const val REGEN_MIN_SPEED_KMH = 5f
        private const val DECELERATION_EPSILON_KMH = 0.05f

        private const val COLD_START_CELSIUS = 21f
        private const val COOLANT_NORMAL_CELSIUS = 91f
        private const val PACK_NORMAL_CELSIUS = 36f
        private const val WARM_UP_CELSIUS_PER_SECOND = 1.4
        private const val HEAT_SPIKE_CELSIUS = 26f
        private const val OUTSIDE_BASE_CELSIUS = 18.0
        private const val OUTSIDE_SWING_CELSIUS = 2.5

        private val OUTSIDE_SENSOR_DROPOUT = 20.0..26.0
        private val HEAT_SPIKE_WINDOW = 76.0..84.0
        private val LEFT_SIGNAL_WINDOW = 26.0..31.0
        private val RIGHT_SIGNAL_WINDOW = 46.0..51.0
        private val HAZARD_WINDOW = 104.0..110.0
        private val HIGH_BEAM_WINDOW = 58.0..70.0
        private val SEAT_BELT_WINDOW = 0.0..7.0
        private val DOOR_OPEN_WINDOW = 0.0..3.0
        private val ENGINE_WARNING_WINDOW = 96.0..103.0
        private val TIRE_PRESSURE_WINDOW = 70.0..112.0
        private val ABS_WINDOW = 88.0..92.0
        private val TRACTION_WINDOW = 36.0..38.0
    }
}
