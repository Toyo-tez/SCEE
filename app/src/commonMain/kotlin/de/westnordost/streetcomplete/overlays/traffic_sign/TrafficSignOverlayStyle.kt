package de.westnordost.streetcomplete.overlays.traffic_sign

import androidx.compose.ui.graphics.Color
import de.westnordost.streetcomplete.data.overlays.OverlayColor
import de.westnordost.streetcomplete.data.overlays.OverlayStyle
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignDirection
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTagState
import de.westnordost.streetcomplete.osm.traffic_sign.trafficSignTagState

/** Gray for "explicitly no signs in both directions" (there is no gray in [OverlayColor]) */
val NoSignsColor = Color(0xff9e9e9e)

/** Colors:
 *  - red: nothing surveyed in either direction
 *  - orange, dashed: only one direction is complete (the other is missing or only "yes")
 *  - orange: both directions only have traffic_sign=yes (or yes and nothing): unspecific
 *  - green (lime): both directions surveyed, both with signs
 *  - gray: both directions explicitly without signs
 *  - teal (also green family = complete): both surveyed, one with signs and one without */
fun trafficSignWayStyle(tags: Map<String, String>): OverlayStyle.Polyline {
    val forward = trafficSignTagState(tags, TrafficSignDirection.FORWARD)
    val backward = trafficSignTagState(tags, TrafficSignDirection.BACKWARD)
    val incomplete = listOf(forward, backward).count { it.isIncomplete }
    val stroke = when {
        forward == TrafficSignTagState.NOT_SURVEYED && backward == TrafficSignTagState.NOT_SURVEYED ->
            OverlayStyle.Stroke(OverlayColor.Red)
        incomplete == 2 -> OverlayStyle.Stroke(OverlayColor.Orange)
        incomplete == 1 -> OverlayStyle.Stroke(OverlayColor.Orange, dashed = true)
        forward == TrafficSignTagState.NONE && backward == TrafficSignTagState.NONE ->
            OverlayStyle.Stroke(NoSignsColor)
        forward == TrafficSignTagState.SIGNED && backward == TrafficSignTagState.SIGNED ->
            OverlayStyle.Stroke(OverlayColor.Lime)
        else -> OverlayStyle.Stroke(OverlayColor.Teal)
    }
    return OverlayStyle.Polyline(stroke)
}

/** Color of ways that are only shown read-only (outside the area of the traffic sign editor). Blue
 *  is not used by [trafficSignWayStyle], so it can not be mistaken for any survey state. */
val ReadOnlySignsColor = OverlayColor.Blue

/** Style of ways that are only shown read-only: the same for every value, because the values are
 *  not interpreted (there is no catalogue for them) and nothing is said about completeness */
val trafficSignReadOnlyStyle = OverlayStyle.Polyline(OverlayStyle.Stroke(ReadOnlySignsColor))

/** The keys of traffic signs that are shown read-only, in the order in which they are shown */
val TRAFFIC_SIGN_KEYS = listOf("traffic_sign", "traffic_sign:forward", "traffic_sign:backward")

/** Whether any of [TRAFFIC_SIGN_KEYS] has a (non-blank) value */
fun hasTrafficSignTags(tags: Map<String, String>): Boolean =
    TRAFFIC_SIGN_KEYS.any { !tags[it].isNullOrBlank() }
