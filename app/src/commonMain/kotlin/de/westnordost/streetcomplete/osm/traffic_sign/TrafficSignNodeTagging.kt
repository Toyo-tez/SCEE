package de.westnordost.streetcomplete.osm.traffic_sign

/* Traffic signs on a node (the position of the sign itself). Unlike on ways, nothing is derived from
 * them: only traffic_sign=* is written. The node is never related to a way, its direction is not
 * interpreted, and no other tag of the node is touched. */

/** Keys with which the signs of a node are tagged per direction. A node with any of them is only
 *  shown, not edited: which direction they refer to would have to be interpreted. */
val TRAFFIC_SIGN_NODE_DIRECTIONAL_KEYS = listOf("traffic_sign:forward", "traffic_sign:backward")

/** Whether the traffic signs of a node with these [tags] can be edited: they must not be tagged per
 *  direction (see [TRAFFIC_SIGN_NODE_DIRECTIONAL_KEYS]). direction=* and traffic_sign:direction=*
 *  are fine: they are kept as they are. */
fun isTrafficSignNodeEditable(tags: Map<String, String>): Boolean =
    TRAFFIC_SIGN_NODE_DIRECTIONAL_KEYS.none { !tags[it].isNullOrBlank() }

/** The signs currently tagged on a node (traffic_sign=*), null if none */
fun parseTrafficSignNodeValue(tags: Map<String, String>, catalogue: TrafficSignCatalogue): TrafficSignValue? =
    tags["traffic_sign"]?.takeIf { it.isNotBlank() }?.let { parseTrafficSignValue(it, catalogue) }

/** The change of traffic_sign=* of a node when the signs [original] are changed into [selection].
 *  null if nothing is to be changed: the selection is the same as before (however it was spelled in
 *  the tags), or there are no signs (removing all signs means: leave the existing value alone, there
 *  is no "no signs" on a node). */
fun trafficSignNodeChange(
    tags: Map<String, String>,
    original: DirectionSelection,
    selection: DirectionSelection,
): TrafficSignTagChange.Set? {
    if (!isTrafficSignNodeEditable(tags)) return null
    val signed = selection as? DirectionSelection.Signed ?: return null
    if (signed.encode() == original.encode()) return null
    val value = signed.value.toOsmValue()
    val previous = tags["traffic_sign"]
    if (value == previous) return null
    return TrafficSignTagChange.Set("traffic_sign", value, previous)
}
