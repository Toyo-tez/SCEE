package de.westnordost.streetcomplete.screens.settings

import de.westnordost.streetcomplete.Prefs
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.testutils.SnapshotMapSettings
import com.russhwolf.settings.ObservableSettings
import de.westnordost.streetcomplete.data.preferences.Preferences.Companion.LAST_VERSION_DATA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsImportExportTest {
    private fun prefs(initial: Map<String, Any> = emptyMap()) =
        Preferences(SnapshotMapSettings(initial.toMutableMap()))

    @Test fun `export round trip preserves portable settings`() {
        val store = SnapshotMapSettings(
            mutableMapOf(
                Prefs.EXPERT_MODE to true,
                Prefs.LOCATION_INTERVAL to 25,
                "theme.select" to "DARK",
                Preferences.OAUTH2_ACCESS_TOKEN to "stolen",
                "osm.userid" to 42L,
                LAST_VERSION_DATA to "old-marker",
            ),
        )
        val original = Preferences(store)
        val exported = settingsToString(original.all.filterKeys { isExportableSettingsKey(it) })
        val target = prefs(mapOf(Prefs.EXPERT_MODE to false, Prefs.LOCATION_INTERVAL to 1))
        assertTrue(readToSettings(exported.lines(), target))
        assertTrue(target.expertMode)
        assertEquals(25, target.getInt(Prefs.LOCATION_INTERVAL, 0))
        assertEquals("DARK", target.getString("theme.select", ""))
        assertNull(target.oAuth2AccessToken)
        assertNull(target.lastDataVersion)
    }

    @Test fun `injected internal keys are not applied`() {
        val target = prefs(
            mapOf(
                Prefs.EXPERT_MODE to true,
                LAST_VERSION_DATA to "keep",
                "osm.userid" to -1L,
            ),
        )
        val lines = listOf(
            "string settings", """{"${Preferences.OAUTH2_ACCESS_TOKEN}":"x","$LAST_VERSION_DATA":"evil","osm.username":"hacker"}""",
            "long settings", """{"osm.userid":999}""",
        )
        assertTrue(readToSettings(lines, target))
        assertTrue(target.expertMode)
        assertEquals("keep", target.lastDataVersion)
        assertEquals(-1L, target.userId)
        assertNull(target.oAuth2AccessToken)
    }

    @Test fun `invalid value types reject import without changing store`() {
        val target = prefs(mapOf(Prefs.EXPERT_MODE to true, Prefs.LOCATION_INTERVAL to 15))
        val lines = listOf(
            "boolean settings", """{"expert_mode":false}""",
            "int settings", """{"gps_interval":"not an int"}""",
        )
        assertFalse(readToSettings(lines, target))
        assertTrue(target.expertMode)
        assertEquals(15, target.getInt(Prefs.LOCATION_INTERVAL, 0))
    }

    @Test fun `unknown section header rejects import`() {
        val target = prefs(mapOf(Prefs.EXPERT_MODE to true))
        assertFalse(readToSettings(listOf("boolean settings", "{}", "evil section", "{}"), target))
        assertTrue(target.expertMode)
    }

    @Test fun `malformed json rejects import`() {
        val target = prefs(mapOf(Prefs.EXPERT_MODE to true))
        assertFalse(readToSettings(listOf("boolean settings", "{not json"), target))
        assertTrue(target.expertMode)
    }

    @Test fun `partial compatible export with only booleans imports`() {
        val target = prefs(mapOf(Prefs.EXPERT_MODE to false, Prefs.LOCATION_INTERVAL to 9))
        assertTrue(readToSettings(listOf("boolean settings", """{"expert_mode":true}"""), target))
        assertTrue(target.expertMode)
        assertEquals(9, target.getInt(Prefs.LOCATION_INTERVAL, 0))
    }

    @Test fun `repeated import is idempotent`() {
        val payload = listOf("boolean settings", """{"expert_mode":true}""")
        val target = prefs()
        repeat(2) { assertTrue(readToSettings(payload, target)) }
        assertTrue(target.expertMode)
    }

    @Test fun `preset quest policy accepts only per preset keys`() {
        val target = prefs()
        val lines = listOf(
            "boolean settings", """{"expert_mode":true,"7_qs_AddTowerAccess_expert":true}""",
            "string settings", """{"7_qs_AddTowerAccess_filter":"nodes","oauth2.accessToken":"no"}""",
        )
        assertTrue(readToSettings(lines, target, SettingsImportPolicy.PresetQuestSettings))
        assertFalse(target.expertMode)
        assertTrue(target.getBoolean("7_qs_AddTowerAccess_expert", false))
        assertEquals("nodes", target.getString("7_qs_AddTowerAccess_filter", ""))
    }

    @Test fun `write failure rolls back earlier keys in same import`() {
        val backing = SnapshotMapSettings(mutableMapOf(Prefs.EXPERT_MODE to true, Prefs.LOCATION_INTERVAL to 15))
        val failing = object : ObservableSettings by backing {
            override fun putInt(key: String, value: Int) = error("injected")
        }
        val target = Preferences(object : de.westnordost.streetcomplete.data.preferences.SettingsWithSnapshot,
            ObservableSettings by failing {
            override val all: Map<String, *> get() = backing.all
        })
        val lines = listOf(
            "boolean settings", """{"expert_mode":false}""",
            "int settings", """{"gps_interval":99}""",
        )
        assertFalse(readToSettings(lines, target))
        assertTrue(target.expertMode)
        assertEquals(15, target.getInt(Prefs.LOCATION_INTERVAL, 0))
    }
}