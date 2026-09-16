package com.csjotlab.cardashboard.nav.map

/**
 * Supplies a style URL per [MapStyle]. Kept behind an interface so day/night (and eventually
 * offline/self-hosted styles) are a configuration change, not a code change.
 */
interface MapStyleProvider {
    fun style(style: MapStyle): String
}

/**
 * Day/night style URLs injected from `BuildConfig` (OpenFreeMap by default). There is deliberately
 * no fallback URL here: a missing style is a build-configuration error, not something to paper over
 * with a blank demo map.
 */
class ConfigurableMapStyleProvider(
    private val dayStyleUrl: String,
    private val nightStyleUrl: String,
) : MapStyleProvider {
    override fun style(style: MapStyle): String = when (style) {
        MapStyle.Day -> dayStyleUrl
        MapStyle.Night -> nightStyleUrl
    }
}
