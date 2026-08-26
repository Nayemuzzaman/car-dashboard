package com.csjotlab.cardashboard.vehicle.protocol

import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.DtcClassification
import com.csjotlab.cardashboard.vehicle.domain.DtcSystem
import com.csjotlab.cardashboard.vehicle.domain.Severity

/**
 * Decodes diagnostic trouble codes using only what SAE J2012 encodes in the code format itself.
 *
 * This deliberately never names a failed component. "P0301" yields "Powertrain / Ignition system or
 * misfire" because those facts are carried by the characters, not because we concluded anything
 * about the vehicle. See spec section 2.1 rule 2.
 */
object DtcDecoder {

    /**
     * Decodes one two-byte DTC pair from an OBD-II mode 03/07/0A response.
     * Returns null for the 0x0000 padding pair, which means "no code in this slot".
     */
    fun decodePair(byteA: Int, byteB: Int): String? {
        val a = byteA and 0xFF
        val b = byteB and 0xFF
        if (a == 0 && b == 0) return null

        val letter = when ((a shr 6) and 0x03) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val second = (a shr 4) and 0x03
        val third = a and 0x0F
        val fourth = (b shr 4) and 0x0F
        val fifth = b and 0x0F

        return buildString {
            append(letter)
            append(second)
            append(third.toString(16).uppercase())
            append(fourth.toString(16).uppercase())
            append(fifth.toString(16).uppercase())
        }
    }

    /** Returns null for anything that is not a well-formed code, rather than guessing. */
    fun classify(code: String): DtcClassification? {
        if (code.length != 5) return null

        val system = when (code[0].uppercaseChar()) {
            'P' -> DtcSystem.Powertrain
            'C' -> DtcSystem.Chassis
            'B' -> DtcSystem.Body
            'U' -> DtcSystem.Network
            else -> return null
        }

        val second = code[1]
        if (second !in '0'..'3') return null
        if (code.drop(2).any { it.digitToIntOrNull(16) == null }) return null

        return DtcClassification(
            system = system,
            // 0 and 2 are SAE-generic; 1 and 3 are manufacturer-specific.
            manufacturerSpecific = second == '1' || second == '3',
            subsystem = if (system == DtcSystem.Powertrain) powertrainSubsystem(code[2]) else null,
        )
    }

    /** The third character of a P code names a subsystem group in J2012. */
    private fun powertrainSubsystem(third: Char): String? = when (third.uppercaseChar()) {
        '1', '2' -> "Fuel and air metering"
        '3' -> "Ignition system or misfire"
        '4' -> "Auxiliary emission controls"
        '5' -> "Vehicle speed control and idle control system"
        '6' -> "Computer output circuits"
        '7', '8' -> "Transmission"
        else -> null
    }

    /** Spec section 4.3. Rules are evaluated in order; the first match wins. */
    fun severityFor(status: DiagnosticStatus, milOn: Boolean): Severity = when {
        status == DiagnosticStatus.Permanent || milOn -> Severity.Critical
        status == DiagnosticStatus.Stored -> Severity.Warning
        else -> Severity.Info
    }
}
