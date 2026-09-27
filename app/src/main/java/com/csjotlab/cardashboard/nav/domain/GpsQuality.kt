package com.csjotlab.cardashboard.nav.domain

/**
 * How much the current position can be trusted, for the driver as much as for the engine.
 *
 * - [None]: no fix has arrived yet this session.
 * - [Good]: a recent fix with accuracy of about a lane or two.
 * - [Degraded]: fixes are arriving but coarse (urban canyon, indoor) or were just rejected.
 * - [Lost]: no usable fix for longer than the staleness timeout (tunnel, garage, GPS off).
 */
enum class GpsQuality { None, Good, Degraded, Lost }
