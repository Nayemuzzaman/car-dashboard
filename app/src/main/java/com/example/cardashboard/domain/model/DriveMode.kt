package com.example.cardashboard.domain.model

/**
 * Driving profile selected by the driver.
 *
 * The mode changes how the dashboard presents information (accent colour, gauge redline) and how
 * the demo simulation behaves. It does not command a real vehicle.
 */
enum class DriveMode {
    ECO,
    NORMAL,
    SPORT
}
