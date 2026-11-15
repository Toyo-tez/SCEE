package de.westnordost.streetcomplete.quests.is_sidepath

sealed interface IsSidepathAnswer {
    /** Sidepath of a road. Fields come from the selected road when available. */
    data class Yes(
        val ofName: String? = null,
        val ofHighway: String? = null,
        val ofRef: String? = null,
    ) : IsSidepathAnswer
    /** Sidepath of a road, but the mapper cannot tell which road. Never writes association tags. */
    data object CantSayWhichRoad : IsSidepathAnswer
    data object No : IsSidepathAnswer
    data object IsSidewalk : IsSidepathAnswer
    data object IsCrossing : IsSidepathAnswer
}
