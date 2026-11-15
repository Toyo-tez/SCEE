package de.westnordost.streetcomplete.quests.building_levels

import de.westnordost.streetcomplete.Prefs
import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapEntryAdd
import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapEntryModify
import de.westnordost.streetcomplete.quests.answerApplied
import de.westnordost.streetcomplete.quests.answerAppliedTo
import de.westnordost.streetcomplete.testutils.TestMapDataWithGeometry
import de.westnordost.streetcomplete.testutils.inMemoryPrefs
import de.westnordost.streetcomplete.testutils.way
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class AddBuildingLevelsTest {

    private lateinit var questType: AddBuildingLevels

    @BeforeTest fun setUp() {
        Prefs.preferences = inMemoryPrefs()
        questType = AddBuildingLevels()
    }

  private val baseTags = mapOf(
        "building" to "house",
        "roof:shape" to "gabled",
    )

    @Test fun `apply building levels answer`() {
        assertEquals(
            setOf(StringMapEntryAdd("building:levels", "5")),
            questType.answerApplied(BuildingLevels(5, null))
        )
    }

    @Test fun `apply building levels and roof levels answer`() {
        assertEquals(
            setOf(
                StringMapEntryAdd("building:levels", "5"),
                StringMapEntryAdd("roof:levels", "0")
            ),
            questType.answerApplied(BuildingLevels(5, 0))
        )
    }

    @Test fun `roof answer does not overwrite unrepresentable building levels`() {
        assertEquals(
            setOf(StringMapEntryAdd("roof:levels", "1")),
            questType.answerAppliedTo(
                BuildingLevels(levels = null, roofLevels = 1),
                baseTags + mapOf("building:levels" to "2.5")
            )
        )
    }

    @Test fun `unchanged building levels are not rewritten when roof is unrepresentable`() {
        assertFails {
            questType.answerAppliedTo(
                BuildingLevels(levels = 3, roofLevels = null),
                baseTags + mapOf("roof:levels" to "2.5", "building:levels" to "3")
            )
        }
    }

    @Test fun `explicit correction replaces unrepresentable building levels`() {
        assertEquals(
            setOf(
                StringMapEntryModify("building:levels", "2.5", "3"),
                StringMapEntryAdd("roof:levels", "1"),
            ),
            questType.answerAppliedTo(
                BuildingLevels(levels = 3, roofLevels = 1),
                baseTags + mapOf("building:levels" to "2.5")
            )
        )
    }

    @Test fun `unchanged representable building levels are not rewritten when only roof is set`() {
        assertEquals(
            setOf(StringMapEntryAdd("roof:levels", "2")),
            questType.answerAppliedTo(
                BuildingLevels(levels = null, roofLevels = 2),
                baseTags + mapOf("building:levels" to "3")
            )
        )
    }

    @Test fun `preserves unrelated building tags`() {
        val tags = baseTags + mapOf(
            "building:levels" to "2.5",
            "height" to "12",
            "roof:colour" to "red",
        )
        val changes = questType.answerAppliedTo(BuildingLevels(null, 1), tags)
        assertEquals(setOf(StringMapEntryAdd("roof:levels", "1")), changes)
    }

    @Test fun `writer rejects empty answer`() {
        assertFails {
            questType.answerApplied(BuildingLevels(null, null))
        }
    }

    @Test fun `writer rejects negative levels`() {
        assertFails {
            questType.answerApplied(BuildingLevels(-1, null))
        }
    }

    @Test fun `not applicable to industrial buildings`() {
        val mapData = TestMapDataWithGeometry(listOf(
            way(1L, listOf(1, 2, 3, 4), mapOf(
                "building" to "industrial"
            ))
        ))
        assertEquals(0, questType.getApplicableElements(mapData).toList().size)
    }

    @Test fun `applicable to residential buildings`() {
        val mapData = TestMapDataWithGeometry(listOf(
            way(1L, listOf(1, 2, 3, 4), mapOf(
                "building" to "residential"
            ))
        ))
        assertEquals(1, questType.getApplicableElements(mapData).toList().size)
    }
}