package de.westnordost.streetcomplete.osm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildingLevelsUtilsTest {

    @Test fun `parseRepresentableBuildingLevels accepts non-negative integers`() {
        assertEquals(0, "0".parseRepresentableBuildingLevels())
        assertEquals(3, "3".parseRepresentableBuildingLevels())
    }

    @Test fun `parseRepresentableBuildingLevels rejects unsupported values`() {
        assertEquals(null, "2.5".parseRepresentableBuildingLevels())
        assertEquals(null, "-1".parseRepresentableBuildingLevels())
        assertEquals(null, "unknown".parseRepresentableBuildingLevels())
        assertEquals(null, "3;4".parseRepresentableBuildingLevels())
    }

    @Test fun `isUnrepresentableBuildingLevels`() {
        assertFalse("3".isUnrepresentableBuildingLevels())
        assertFalse(null.isUnrepresentableBuildingLevels())
        assertFalse("".isUnrepresentableBuildingLevels())
        assertTrue("2.5".isUnrepresentableBuildingLevels())
    }
}