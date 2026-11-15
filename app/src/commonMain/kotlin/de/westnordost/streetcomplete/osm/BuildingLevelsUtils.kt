package de.westnordost.streetcomplete.osm

val BUILDINGS_WITH_LEVELS = arrayOf(
    "house", "residential", "apartments", "detached", "terrace", "dormitory", "semi",
    "semidetached_house", "bungalow", "school", "civic", "college", "university", "public",
    "hospital", "kindergarten", "transportation", "train_station", "hotel", "retail",
    "commercial", "office", "houseboat", "manufacture", "parking", "farm",
    "cabin"
)

/** Non-negative integer [building:levels] / [roof:levels] values editable in the quest form. */
fun String?.parseRepresentableBuildingLevels(): Int? =
    this?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

/** Tag is present but not an editable non-negative integer (e.g. `2.5`, `unknown`, `3;4`). */
fun String?.isUnrepresentableBuildingLevels(): Boolean =
    !isNullOrBlank() && parseRepresentableBuildingLevels() == null