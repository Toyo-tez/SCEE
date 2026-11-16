package de.westnordost.streetcomplete.overlays.traffic_sign

import android.app.Application
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.westnordost.osmfeatures.FeatureDictionary
import de.westnordost.streetcomplete.Prefs
import de.westnordost.streetcomplete.data.meta.CountryInfo
import de.westnordost.streetcomplete.data.osm.edits.MapDataWithEditsSource
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPolylinesGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.overlays.Action
import de.westnordost.streetcomplete.data.overlays.OverlayAction
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.at1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.de1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.de2
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.gb1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.gb2
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.line
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.unknown1
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

/** The form of the traffic sign overlay for ways that can not be edited with the traffic sign
 *  editor: read-only, nothing is saved, the tag editor only on an explicit tap */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS")
class TrafficSignReadOnlyOverlayFormTest {
    @get:Rule val compose = createComposeRule()

    private val preferences = inMemoryPrefs()
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
            // no TrafficSignCatalogues: the read-only form must not need a catalogue
        })
    }

    // like the bundled catalogues: DE, FR and AT have one, GB has none
    private val overlay = TrafficSignOverlay(
        TrafficSignTestCountries.getCountryCode,
        TrafficSignTestCountries.getContainingCountryCodes,
        hasCatalogue = { it in setOf("DE", "FR", "AT") },
    )

    private val actions = ArrayList<OverlayAction>()

    @After fun closeKoin() {
        testKoin.close()
    }

    private fun show(tags: Map<String, String>, geometry: ElementPolylinesGeometry) {
        show(way(1, nodes = listOf(1, 2, 3), tags = tags), geometry)
    }

    private fun show(w: Element, geometry: ElementGeometry) {
        Prefs.preferences = preferences
        compose.setContent {
            KoinIsolatedContext(testKoin) {
                MaterialTheme {
                    overlay.Form(
                        on = { actions.add(it) },
                        element = w,
                        geometry = geometry,
                        // deliberately says Germany: the decision must be made by the geometry
                        countryInfo = CountryInfo("DE", emptyList()),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `way in a country without catalogue shows its tags as they are and saves nothing`() {
        show(
            mapOf("highway" to "residential", "traffic_sign" to "GB:616", "traffic_sign:forward" to "GB:619"),
            line(gb1, gb2)
        )
        compose.onNodeWithText("A complete traffic sign editor is not available for this country yet.", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("traffic_sign=GB:616").assertIsDisplayed()
        compose.onNodeWithText("traffic_sign:forward=GB:619").assertIsDisplayed()
        compose.onNodeWithText("General (key without direction)").assertIsDisplayed()
        compose.onNodeWithText("Direction of the way").assertIsDisplayed()
        compose.onNodeWithText("Split way").assertDoesNotExist()
        // showing the form does nothing by itself
        assertEquals(emptyList(), actions)
    }

    @Test fun `tag editor is only opened by an explicit tap`() {
        show(mapOf("highway" to "residential", "traffic_sign:backward" to "GB:616"), line(gb1, gb2))
        compose.onNodeWithText("traffic_sign:backward=GB:616").assertIsDisplayed()
        compose.onNodeWithText("Opposite direction").assertIsDisplayed()
        assertEquals(emptyList(), actions)

        compose.onNodeWithText("Show/edit tags").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf<OverlayAction>(Action.EditTags), actions)
    }

    @Test fun `way that crosses the border explains it and offers to split it`() {
        show(mapOf("highway" to "residential", "traffic_sign" to "DE:250"), line(de1, de2, at1))
        compose.onNodeWithText("this way lies in several countries (AT, DE)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("traffic_sign=DE:250").assertIsDisplayed()
        assertEquals(emptyList(), actions)

        compose.onNodeWithText("Split way").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf<OverlayAction>(Action.SplitWay), actions)
    }

    @Test fun `node shows its traffic sign and direction tags as they are and saves nothing`() {
        val n = node(1, gb1, mapOf("traffic_sign" to "GB:616", "direction" to "forward", "highway" to "give_way"))
        show(n, ElementPointGeometry(gb1))
        compose.onNodeWithText("There is no traffic sign catalogue for the country of this point", substring = true).assertIsDisplayed()
        compose.onNodeWithText("traffic_sign=GB:616").assertIsDisplayed()
        compose.onNodeWithText("direction=forward").assertIsDisplayed()
        compose.onNodeWithText("Direction, as tagged").assertIsDisplayed()
        compose.onNodeWithText("highway=give_way").assertDoesNotExist()
        compose.onNodeWithText("Split way").assertDoesNotExist()
        assertEquals(emptyList(), actions)

        compose.onNodeWithText("Show/edit tags").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf<OverlayAction>(Action.EditTags), actions)
    }

    @Test fun `node in Germany with signs per direction is read-only`() {
        // which direction they refer to would have to be interpreted: no catalogue is needed
        show(node(1, de1, mapOf("traffic_sign:forward" to "DE:206", "traffic_sign" to "stop")), ElementPointGeometry(de1))
        compose.onNodeWithText("traffic_sign:forward=DE:206").assertIsDisplayed()
        compose.onNodeWithText("tagged per direction", substring = true).assertIsDisplayed()
        assertEquals(emptyList(), actions)
    }

    @Test fun `node close to a border is read-only`() {
        // about 7 m from the border between DE and AT (lon 10)
        val p = LatLon(50.0, 9.9999)
        show(node(1, p, mapOf("traffic_sign" to "DE:206")), ElementPointGeometry(p))
        compose.onNodeWithText("It cannot be determined reliably in which country this point lies", substring = true).assertIsDisplayed()
        assertEquals(emptyList(), actions)
    }

    @Test fun `way in Germany with a part of unknown country is uncertain and not editable`() {
        show(mapOf("highway" to "residential", "traffic_sign" to "DE:250"), line(de1, unknown1))
        compose.onNodeWithText("It cannot be determined reliably", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Split way").assertDoesNotExist()
        assertEquals(emptyList(), actions)
    }
}
