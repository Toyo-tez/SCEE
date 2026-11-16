package de.westnordost.streetcomplete.osm.traffic_sign

import kotlinx.serialization.Serializable

/** A combination of signs the user entered, shared by quest and overlay. Both directions are kept
 *  as the traffic_sign values they are tagged as ("" = not surveyed, "none" = no signs), so that
 *  different signs per direction are stored without loss. */
@Serializable
data class TrafficSignCombination(val forward: String, val backward: String) {
    val isSameInBothDirections: Boolean get() = forward == backward

    fun toSelection(catalogue: TrafficSignCatalogue) = WaySignSelection(
        DirectionSelection.decode(forward, catalogue),
        DirectionSelection.decode(backward, catalogue),
    )

    companion object {
        /** null if there are no signs in either direction: only real signs are worth remembering */
        fun of(selection: WaySignSelection): TrafficSignCombination? =
            if (selection.forward is DirectionSelection.Signed || selection.backward is DirectionSelection.Signed) {
                TrafficSignCombination(selection.forward.encode(), selection.backward.encode())
            } else {
                null
            }
    }
}

/** Whether this combination can be offered for a way of the country of [catalogue]: the history is
 *  shared by all countries, but signs of one country must never be written to a way in another one.
 *  So every direction must be "not surveyed", "none" or signs that this catalogue reads (values of
 *  other countries are unreadable for it). */
fun TrafficSignCombination.isUsableWith(catalogue: TrafficSignCatalogue): Boolean {
    val selection = toSelection(catalogue)
    return TrafficSignDirection.entries.all { d ->
        when (val s = selection[d]) {
            is DirectionSelection.Signed -> !s.value.isUnparseable && (s.value.country == null || s.value.country == catalogue.country)
            else -> true
        }
    }
}

const val TRAFFIC_SIGN_HISTORY_SIZE = 6

/** [combination] first (most recent), without duplicates, at most [TRAFFIC_SIGN_HISTORY_SIZE] */
fun List<TrafficSignCombination>.withPicked(combination: TrafficSignCombination): List<TrafficSignCombination> =
    (listOf(combination) + filter { it != combination }).take(TRAFFIC_SIGN_HISTORY_SIZE)
