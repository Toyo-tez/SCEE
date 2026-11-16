package de.westnordost.streetcomplete.quests.access_restriction

import de.westnordost.streetcomplete.Prefs
import de.westnordost.streetcomplete.data.Database
import de.westnordost.streetcomplete.data.StreetCompleteDatabaseTestCase
import de.westnordost.streetcomplete.data.osm.mapdata.ElementType
import de.westnordost.streetcomplete.data.osm.osmquests.OsmQuestsHiddenDao
import de.westnordost.streetcomplete.data.quest.OsmQuestKey
import de.westnordost.streetcomplete.testutils.inMemoryPrefs
import de.westnordost.streetcomplete.testutils.way
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgriculturalAccessHiddenTest : StreetCompleteDatabaseTestCase() {
    private lateinit var database: Database

    override fun onDatabaseInitialized(database: Database) {
        this.database = database
        Prefs.preferences = inMemoryPrefs()
    }

    @Test fun `local hiding survives DAO recreation without modifying unchanged OSM data`() {
        val quest = CheckAgriculturalAccess()
        val tags = mapOf("highway" to "track", "access" to "agricultural")
        val element = way(123, nodes = listOf(1, 2, 3), tags = tags)
        val key = OsmQuestKey(ElementType.WAY, element.id, quest.name)
        OsmQuestsHiddenDao(database).add(key)
        val reloaded = OsmQuestsHiddenDao(database)
        assertNotNull(reloaded.getTimestamp(key))
        assertTrue(quest.isApplicableTo(element))
        assertEquals(tags, element.tags)
        assertTrue(reloaded.delete(key))
        assertNull(reloaded.getTimestamp(key))
        assertTrue(quest.isApplicableTo(element))
    }
}
