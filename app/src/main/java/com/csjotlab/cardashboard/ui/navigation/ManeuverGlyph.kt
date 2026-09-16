package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.domain.ManeuverType

/**
 * A turn arrow per maneuver, as a text glyph from the Unicode Arrows block (U+2190–U+21FF) so it
 * renders with the system font and needs no icon assets.
 */
fun ManeuverType.glyph(): String = when (this) {
    ManeuverType.Depart -> "●"
    ManeuverType.Arrive -> "◉"
    ManeuverType.Continue -> "↑"
    ManeuverType.TurnLeft -> "↰"
    ManeuverType.TurnRight -> "↱"
    ManeuverType.SlightLeft -> "↖"
    ManeuverType.SlightRight -> "↗"
    ManeuverType.SharpLeft -> "↙"
    ManeuverType.SharpRight -> "↘"
    ManeuverType.UTurn -> "↶"
    is ManeuverType.Roundabout -> "↻"
    is ManeuverType.Exit -> "⇗"
    ManeuverType.KeepLeft -> "⇖"
    ManeuverType.KeepRight -> "⇗"
    ManeuverType.Merge -> "⇑"
    ManeuverType.Unknown -> "•"
}
