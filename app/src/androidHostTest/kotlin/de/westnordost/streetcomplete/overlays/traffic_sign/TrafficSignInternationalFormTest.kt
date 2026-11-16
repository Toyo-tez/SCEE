package de.westnordost.streetcomplete.overlays.traffic_sign

import android.app.Application
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import de.westnordost.osmfeatures.FeatureDictionary
import de.westnordost.streetcomplete.Prefs
import de.westnordost.streetcomplete.data.meta.CountryInfo
import de.westnordost.streetcomplete.data.osm.edits.MapDataWithEditsSource
import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapEntryAdd
import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapEntryModify
import de.westnordost.streetcomplete.data.osm.edits.update_tags.UpdateElementTagsAction
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.overlays.Edit
import de.westnordost.streetcomplete.data.overlays.OverlayAction
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.data.traffic_signs.TrafficSignCatalogues
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.de1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.de2
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.fr1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.fr2
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.line
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestData
import de.westnordost.streetcomplete.testutils.inMemoryPrefs
import de.westnordost.streetcomplete.testutils.node
import de.westnordost.streetcomplete.testutils.way
import dev.mokkery.mock
import kotlinx.io.files.SystemFileSystem
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The editor of the traffic sign overlay with the bundled catalogues of other countries than
 *  Germany: the same picker, only traffic_sign* is written, and nodes */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS")
class TrafficSignInternationalFormTest {
    @get:Rule val compose = createComposeRule()

    private val preferences = inMemoryPrefs()
    private val catalogues: TrafficSignCatalogues = TrafficSignTestData.bundledCatalogues
    private val testKoin = koinApplication {
        modules(module {
            single<Preferences> { preferences }
            single<MapDataWithEditsSource> { mock() }
            single {
                FeatureDictionary.create(
                    SystemFileSystem,
                    "src/commonMain/composeResources/files/osmfeatures/default",
                )
            }
            single { catalogues }
        })
    }

    private val overlay = TrafficSignOverlay(
        TrafficSignTestCountries.getCountryCode,
        TrafficSignTestCountries.getContainingCountryCodes,
        catalogues::isAvailable,
    )

    private val actions = ArrayList<OverlayAction>()

    @After fun closeKoin() {
        testKoin.close()
    }

    private fun show(element: Element, geometry: ElementGeometry) {
        Prefs.preferences = preferences
        compose.setContent {
            KoinIsolatedContext(testKoin) {
                MaterialTheme {
                    overlay.Form(
                        on = { actions.add(it) },
                        element = element,
                        geometry = geometry,
                        // deliberately says Germany: the decision must be made by the geometry
                        countryInfo = CountryInfo("DE", emptyList()),
                    )
                }
            }
        }
        waitForText("Add traffic sign")
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun pick(query: String, result: String) {
        compose.onAllNodesWithText("Add traffic sign").onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(query)
        compose.waitForIdle()
        waitForText(result)
        compose.onAllNodesWithText(result).onFirst().performClick()
        compose.waitForIdle()
    }

    private val changes get() = ((actions.single() as Edit).value as UpdateElementTagsAction).changes.changes

    @Test fun `way in France - FR B9b is picked and only traffic_sign is written`() {
        val tags = mapOf("highway" to "residential", "bicycle" to "no", "maxspeed" to "30")
        show(way(1, nodes = listOf(1, 2, 3), tags = tags), line(fr1, fr2))
        compose.onNodeWithText("only the traffic signs are recorded", substring = true).assertIsDisplayed()

        pick("B9b", "Accès interdit aux cycles")
        // no additional signs for a catalogue that does not say which fit
        compose.onNodeWithText("Add additional sign").assertDoesNotExist()
        compose.onNodeWithText("traffic_sign=FR:B9b").assertExists()

        compose.onNodeWithContentDescription("OK").performClick()
        compose.waitForIdle()
        assertEquals(setOf(StringMapEntryAdd("traffic_sign", "FR:B9b")), changes)
    }

    @Test fun `way in Germany still has additional signs and derived tags`() {
        show(way(1, nodes = listOf(1, 2, 3), tags = mapOf("highway" to "residential")), line(de1, de2))
        compose.onNodeWithText("only the traffic signs are recorded", substring = true).assertDoesNotExist()
        pick("250", "Verbot für Fahrzeuge aller Art")
        compose.onNodeWithText("Add additional sign").assertIsDisplayed()
        compose.onAllNodesWithText("vehicle=no").onFirst().assertExists()
    }

    @Test fun `node in France - only traffic_sign is changed, after a confirmation`() {
        val n = node(1, fr1, mapOf("traffic_sign" to "FR:QQ1", "direction" to "90", "highway" to "stop"))
        show(n, ElementPointGeometry(fr1))
        compose.onNodeWithText("Only traffic_sign of this point is changed", substring = true).assertExists()

        // the existing (unknown) sign is shown and can be removed, a picked sign is added to it
        compose.onAllNodesWithText("Remove").onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        pick("B9b", "Accès interdit aux cycles")
        compose.onNodeWithContentDescription("OK").performClick()
        compose.waitForIdle()
        // an existing value is only replaced after a confirmation
        assertEquals(emptyList(), actions)
        compose.onNodeWithText("Replace traffic_sign=FR:QQ1 with traffic_sign=FR:B9b?").assertExists()
        compose.onAllNodesWithText("Save").onFirst().performClick()
        compose.waitForIdle()
        assertEquals(setOf(StringMapEntryModify("traffic_sign", "FR:QQ1", "FR:B9b")), changes)
    }
}
