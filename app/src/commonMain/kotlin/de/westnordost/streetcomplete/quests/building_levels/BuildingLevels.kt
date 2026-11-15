package de.westnordost.streetcomplete.quests.building_levels

import kotlinx.serialization.Serializable

@Serializable
data class BuildingLevels(
    /** When null, [building:levels] is not written (existing value is kept). */
    val levels: Int?,
    /** When null, [roof:levels] is not written (existing value is kept). */
    val roofLevels: Int?,
)
