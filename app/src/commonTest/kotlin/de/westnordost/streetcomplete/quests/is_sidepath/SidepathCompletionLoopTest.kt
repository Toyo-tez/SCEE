package de.westnordost.streetcomplete.quests.is_sidepath

import de.westnordost.streetcomplete.Prefs
import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapChanges
import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapEntryAdd
import de.westnordost.streetcomplete.data.osm.edits.update_tags.updateElementTagsActionOrNull
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPolylinesGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.Way
import de.westnordost.streetcomplete.osm.nowAsCheckDateString
import de.westnordost.streetcomplete.quests.answerAppliedTo
import de.westnordost.streetcomplete.testutils.createMapData
import de.westnordost.streetcomplete.testutils.mockPrefs3
import de.westnordost.streetcomplete.testutils.p
import de.westnordost.streetcomplete.testutils.way
import de.westnordost.streetcomplete.util.math.translate
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** R1/R2: completion-mode loops of AddIsSidepath (quest re-check at 79a82730cc). */
class SidepathCompletionLoopTest {

    @BeforeTest fun setUp() {
        Prefs.preferences = mockPrefs3()
    }

    private val today get() = nowAsCheckDateString()

    private val pathStart = p(0.0, 0.0)
    private val pathGeom = ElementPolylinesGeometry(
        listOf(listOf(pathStart, pathStart.translate(40.0, 0.0))), pathStart
    )

    private fun roadGeom(distance: Double, bearing: Double): ElementPolylinesGeometry {
        val start = pathStart.translate(distance, bearing)
        return ElementPolylinesGeometry(listOf(listOf(start, start.translate(40.0, 0.0))), start)
    }

    private val unnamedSecondary = way(1, listOf(10, 11), mapOf("highway" to "secondary"))
    private val namedSecondary = way(3, listOf(20, 21), mapOf("highway" to "secondary", "name" to "Foo"))

    private fun path(tags: Map<String, String>) = way(2, listOf(3, 4), tags)

    /** Fresh quest instance and fresh map data: what a quest recalculation after reload sees. */
    private fun applicableCount(pathTags: Map<String, String>, vararg roads: Pair<Way, ElementGeometry>): Int {
        val geometries = mutableMapOf<Element, ElementGeometry?>(path(pathTags) to pathGeom)
        roads.forEach { (road, geometry) -> geometries[road] = geometry }
        return AddIsSidepath().getApplicableElements(createMapData(geometries)).count()
    }

    /** Applies the answer like an edit: through a serialized StringMapChanges round trip. */
    private fun applyPersisted(answer: IsSidepathAnswer, tags: Map<String, String>): Map<String, String> {
        val changes = AddIsSidepath().answerAppliedTo(answer, tags)
        val action = updateElementTagsActionOrNull(path(tags), StringMapChanges(changes))
        assertNotNull(action, "answer must not produce an empty edit")
        val json = Json.encodeToString(StringMapChanges.serializer(), action.changes)
        val restored = Json.decodeFromString(StringMapChanges.serializer(), json)
        return tags.toMutableMap().also { restored.applyTo(it) }
    }

    private val associationKeys = setOf("is_sidepath:of", "is_sidepath:of:name", "is_sidepath:of:ref")

    // region R1: can't say which road

    @Test fun `R1 cant say closes the quest`() {
        val start = mapOf("highway" to "cycleway")
        val road = unnamedSecondary to roadGeom(5.0, 90.0)
        assertEquals(1, applicableCount(start, road))

        val changes = AddIsSidepath().answerAppliedTo(IsSidepathAnswer.CantSayWhichRoad, start)
        assertEquals(
            setOf(
                StringMapEntryAdd("is_sidepath", "yes"),
                StringMapEntryAdd("check_date:is_sidepath:of", today),
            ),
            changes,
        )
        assertTrue(changes.none { it.key in associationKeys }, "no association may be invented")

        val after = applyPersisted(IsSidepathAnswer.CantSayWhichRoad, start)
        assertEquals(0, applicableCount(after, road))
    }

    @Test fun `R1 closure survives recalculation and persistence reload`() {
        val road = unnamedSecondary to roadGeom(5.0, 90.0)
        val named = namedSecondary to roadGeom(6.0, 270.0)
        val after = applyPersisted(IsSidepathAnswer.CantSayWhichRoad, mapOf("highway" to "cycleway"))
        // stored tags only (as after app restart / re-download), new quest instance each time
        val reloaded = after.toMap()
        repeat(3) { assertEquals(0, applicableCount(reloaded, road, named)) }
        assertFalse(reloaded.keys.any { it in associationKeys })
    }

    @Test fun `R1 cant say in completion mode keeps the existing association untouched`() {
        val start = mapOf(
            "highway" to "cycleway",
            "is_sidepath" to "yes",
            "is_sidepath:of" to "secondary",
        )
        val unnamed = unnamedSecondary to roadGeom(5.0, 90.0)
        val named = namedSecondary to roadGeom(6.0, 270.0)
        assertEquals(1, applicableCount(start, unnamed, named))

        val changes = AddIsSidepath().answerAppliedTo(IsSidepathAnswer.CantSayWhichRoad, start)
        assertEquals(setOf(StringMapEntryAdd("check_date:is_sidepath:of", today)), changes)

        val after = applyPersisted(IsSidepathAnswer.CantSayWhichRoad, start)
        assertEquals("secondary", after["is_sidepath:of"])
        assertFalse(after.containsKey("is_sidepath:of:name"))
        assertEquals(0, applicableCount(after, unnamed, named))
    }

    // endregion

    // region R2: unnamed road next to a named road of the same class

    @Test fun `R2 choosing the unnamed road closes the quest at once`() {
        val start = mapOf("highway" to "cycleway")
        val unnamed = unnamedSecondary to roadGeom(5.0, 90.0)
        val named = namedSecondary to roadGeom(6.0, 270.0)
        assertEquals(1, applicableCount(start, unnamed, named))

        val after = applyPersisted(IsSidepathAnswer.Yes(null, "secondary", null), start)
        assertEquals("yes", after["is_sidepath"])
        assertEquals("secondary", after["is_sidepath:of"])
        assertFalse(after.containsKey("is_sidepath:of:name"), "named road must not be associated")
        assertFalse(after.containsKey("is_sidepath:of:ref"))
        assertEquals(today, after["check_date:is_sidepath:of"])
        assertEquals(0, applicableCount(after, unnamed, named))
    }

    @Test fun `R2 re-confirming an existing unnamed association is submittable and closes`() {
        val start = mapOf(
            "highway" to "cycleway",
            "is_sidepath" to "yes",
            "is_sidepath:of" to "secondary",
        )
        val unnamed = unnamedSecondary to roadGeom(5.0, 90.0)
        val named = namedSecondary to roadGeom(6.0, 270.0)
        // not surveyed yet: the named same-class road still makes it eligible
        assertEquals(1, applicableCount(start, unnamed, named))
        // the truthful road is selectable in the form (same writer as the answer)
        assertTrue(sidepathRoadSelectionProducesChanges(start, null, "secondary", null))

        val changes = AddIsSidepath().answerAppliedTo(IsSidepathAnswer.Yes(null, "secondary", null), start)
        assertEquals(setOf(StringMapEntryAdd("check_date:is_sidepath:of", today)), changes)

        val after = applyPersisted(IsSidepathAnswer.Yes(null, "secondary", null), start)
        assertEquals(start + ("check_date:is_sidepath:of" to today), after)
        assertEquals(0, applicableCount(after, unnamed, named))
    }

    @Test fun `R2 selection already surveyed today is not offered as a change`() {
        val tags = mapOf(
            "highway" to "cycleway",
            "is_sidepath" to "yes",
            "is_sidepath:of" to "secondary",
            "check_date:is_sidepath:of" to today,
        )
        assertFalse(sidepathRoadSelectionProducesChanges(tags, null, "secondary", null))
    }

    @Test fun `completion default keeps the existing road class when a closer different road exists`() {
        val tags = mapOf(
            "highway" to "cycleway",
            "is_sidepath" to "yes",
            "is_sidepath:of" to "secondary",
        )
        val candidates = listOf(
            CandidateRoad(
                element = way(5, listOf(30, 31), mapOf("highway" to "primary")),
                geometry = roadGeom(3.0, 90.0),
                distance = 3.0,
                alignmentDiff = 0.0,
            ),
            CandidateRoad(
                element = unnamedSecondary,
                geometry = roadGeom(8.0, 90.0),
                distance = 8.0,
                alignmentDiff = 0.0,
            ),
        )
        val canSubmit = { name: String?, highway: String?, ref: String? ->
            sidepathRoadSelectionProducesChanges(tags, name, highway, ref)
        }

        assertEquals(
            "primary",
            selectDefaultSidepathRoadCandidate(candidates, roadSelectionProducesChanges = canSubmit).highway,
        )
        assertEquals(
            "secondary",
            selectDefaultSidepathRoadCandidate(
                candidates,
                preferredHighway = "secondary",
                roadSelectionProducesChanges = canSubmit,
            ).highway,
        )
    }

    // endregion

    // region guards

    @Test fun `named road answer still fills the name without a check date`() {
        val start = mapOf("highway" to "cycleway", "is_sidepath" to "yes", "is_sidepath:of" to "secondary")
        val changes = AddIsSidepath().answerAppliedTo(IsSidepathAnswer.Yes("Foo", "secondary", null), start)
        assertEquals(setOf(StringMapEntryAdd("is_sidepath:of:name", "Foo")), changes)
    }

    @Test fun `sidepath check date does not suppress association completion`() {
        val start = mapOf(
            "highway" to "cycleway",
            "is_sidepath" to "yes",
            "check_date:is_sidepath" to today,
        )
        assertEquals(1, applicableCount(start, unnamedSecondary to roadGeom(5.0, 90.0)))
    }

    @Test fun `association survey suppresses until a candidate road is edited later`() {
        val candidate = unnamedSecondary to roadGeom(5.0, 90.0)
        val start = mapOf(
            "highway" to "cycleway",
            "is_sidepath" to "yes",
            "check_date:is_sidepath:of" to today,
        )
        assertEquals(0, applicableCount(start, candidate))

        val olderSurvey = start + ("check_date:is_sidepath:of" to "2020-01-01")
        assertEquals(1, applicableCount(olderSurvey, candidate))
    }

    // endregion
}
