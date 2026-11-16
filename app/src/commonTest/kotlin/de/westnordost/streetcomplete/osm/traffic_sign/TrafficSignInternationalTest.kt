package de.westnordost.streetcomplete.osm.traffic_sign

import androidx.compose.ui.unit.Density
import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapChangesBuilder
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.traffic_signs.TrafficSignCatalogues
import de.westnordost.streetcomplete.data.traffic_signs.indexJson
import de.westnordost.streetcomplete.data.traffic_signs.parseTrafficSignCatalogueIndex
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.at1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.de1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.de2
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.fr1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.fr2
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.gb1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.gb2
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.line
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.unknown1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestData.RESOURCES_DIR
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestData.bundledCatalogues
import de.westnordost.streetcomplete.overlays.traffic_sign.TrafficSignOverlay
import de.westnordost.streetcomplete.overlays.traffic_sign.isEditable
import de.westnordost.streetcomplete.overlays.traffic_sign.newNodeUnavailableExplanation
import de.westnordost.streetcomplete.overlays.traffic_sign.trafficSignReadOnlyStyle
import de.westnordost.streetcomplete.overlays.traffic_sign.trafficSignWayStyle
import de.westnordost.streetcomplete.resources.Res
import de.westnordost.streetcomplete.resources.traffic_sign_overlay_create_node_country
import de.westnordost.streetcomplete.resources.traffic_sign_overlay_create_node_uncertain
import de.westnordost.streetcomplete.testutils.createMapData
import de.westnordost.streetcomplete.testutils.node
import de.westnordost.streetcomplete.testutils.way
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.decodeToImageVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The catalogues of all countries of the import (files/traffic_signs/catalogues.json) and the
 *  editor for them: which countries there are, which signs are offered, and that only Germany, which
 *  has curated rules, gets other tags than traffic_sign* */
class TrafficSignInternationalTest {

    private fun catalogue(country: String) = assertNotNull(bundledCatalogues.get(country), country)

    /** the tags after [apply] */
    private fun Map<String, String>.after(apply: (StringMapChangesBuilder) -> Unit): Map<String, String> =
        HashMap(StringMapChangesBuilder(this).also(apply))

    private val indexText = TrafficSignTestData.readText("$RESOURCES_DIR/files/traffic_signs/${TrafficSignCatalogues.INDEX_FILE}")

    /* ------------------------------------------- countries ------------------------------------------- */

    @Test fun `the available countries are exactly the imported catalogues`() {
        val jsonFiles = SystemFileSystem.list(Path("$RESOURCES_DIR/files/traffic_signs"))
            .map { it.name }.filter { Regex("^[A-Z]{2}\\.json$").matches(it) }.map { it.removeSuffix(".json") }.toSet()
        assertEquals(jsonFiles, bundledCatalogues.availableCountries)
        assertTrue(bundledCatalogues.availableCountries.containsAll(setOf("DE", "FR", "AT")))
        for (country in bundledCatalogues.availableCountries) assertEquals(country, catalogue(country).country)
    }

    @Test fun `GB has no catalogue`() {
        assertFalse(bundledCatalogues.isAvailable("GB"))
        assertNull(bundledCatalogues.get("GB"))
    }

    @Test fun `a country that is added to the index is available without any change of code`() {
        fun json(country: String) = """{"country": "$country", "version": "9", "signs": [
            {"osmValuePart": "1", "signId": "1", "name": "1", "kind": "traffic_sign", "catalogue": {"signCategory": "x"}, "tagRecommendationsByGeometry": "none"},
            {"osmValuePart": "2", "signId": "2", "name": "2", "kind": "traffic_sign", "catalogue": {"signCategory": "x"}, "tagRecommendationsByGeometry": "none"}
        ]}"""
        val catalogues = TrafficSignCatalogues(
            loadIndex = { indexJson(mapOf("DE" to emptySet(), "XY" to setOf("2"))) },
            loadJson = { country -> json(country) },
        )
        assertEquals(setOf("DE", "XY"), catalogues.availableCountries)
        // the signs that were not imported are left out
        assertEquals(listOf("1"), catalogues.get("XY")!!.signs.map { it.osmValuePart })
        assertEquals(listOf("1", "2"), catalogues.get("DE")!!.signs.map { it.osmValuePart })
        assertNull(catalogues.get("ZZ"))
    }

    @Test fun `without index there is no catalogue, but no error either`() {
        val catalogues = TrafficSignCatalogues(loadIndex = { null }, loadJson = { error("must not be loaded") })
        assertEquals(emptySet(), catalogues.availableCountries)
        assertNull(catalogues.get("DE"))
        assertNull(TrafficSignCatalogues(loadIndex = { "garbage" }, loadJson = { null }).get("DE"))
        // an index entry that is not a country is ignored
        assertEquals(setOf("DE"), parseTrafficSignCatalogueIndex("""{"countries": [{"country": "DE"}, {"country": "de-x"}, {"x": 1}]}""").keys)
    }

    @Test fun `catalogues are loaded per country on demand and switched without a restart`() {
        val loaded = ArrayList<String>()
        val catalogues = TrafficSignCatalogues(
            loadIndex = { TrafficSignTestData.readText("$RESOURCES_DIR/files/traffic_signs/${TrafficSignCatalogues.INDEX_FILE}") },
            loadJson = { country ->
                loaded.add(country)
                TrafficSignTestData.readText("$RESOURCES_DIR/files/traffic_signs/$country.json")
            },
        )
        // asking which countries there are loads no catalogue
        assertTrue(catalogues.isAvailable("FR"))
        assertEquals(emptyList(), loaded)
        val de = catalogues.get("DE")!!
        val fr = catalogues.get("FR")!!
        assertEquals("DE", de.country)
        assertEquals("FR", fr.country)
        assertNotSame(de, fr)
        // back to DE: the same catalogue, not loaded again
        assertSame(de, catalogues.get("DE"))
        assertEquals(listOf("DE", "FR"), loaded)
    }

    @Test fun `moving the map between countries changes which ways are editable`() {
        val overlay = TrafficSignOverlay(
            TrafficSignTestCountries.getCountryCode,
            TrafficSignTestCountries.getContainingCountryCodes,
            bundledCatalogues::isAvailable,
        )
        val road = "highway" to "residential"
        fun styleOfWayIn(a: LatLon, b: LatLon, tags: Map<String, String> = mapOf(road)) =
            overlay.getStyledElements(createMapData(mapOf(way(1, tags = tags) to line(a, b)))).singleOrNull()?.second
        // DE -> FR -> GB -> FR -> DE, with the same overlay
        assertEquals(trafficSignWayStyle(mapOf(road)), styleOfWayIn(de1, de2))
        assertEquals(trafficSignWayStyle(mapOf(road)), styleOfWayIn(fr1, fr2))
        assertNull(styleOfWayIn(gb1, gb2))
        assertEquals(trafficSignReadOnlyStyle, styleOfWayIn(gb1, gb2, mapOf(road, "traffic_sign" to "GB:616")))
        assertEquals(trafficSignWayStyle(mapOf(road)), styleOfWayIn(fr1, fr2))
        assertEquals(trafficSignWayStyle(mapOf(road)), styleOfWayIn(de1, de2))
    }

    @Test fun `ways that cross a border or of uncertain country stay read-only, also between two countries with catalogue`() {
        val all = { _: String -> true }
        assertFalse(isEditable(trafficSignWayCountry(line(de1, at1), TrafficSignTestCountries.getCountryCode, TrafficSignTestCountries.getContainingCountryCodes), all))
        assertFalse(isEditable(trafficSignWayCountry(line(de1, unknown1), TrafficSignTestCountries.getCountryCode, TrafficSignTestCountries.getContainingCountryCodes), all))
        assertTrue(isEditable(trafficSignWayCountry(line(fr1, fr2), TrafficSignTestCountries.getCountryCode, TrafficSignTestCountries.getContainingCountryCodes), bundledCatalogues::isAvailable))
    }

    /* -------------------------------------------- import -------------------------------------------- */

    @Test fun `each catalogue has exactly the imported signs, each with an image that can be decoded`() {
        val density = Density(1f)
        val index = Json.parseToJsonElement(indexText).jsonObject.getValue("countries").jsonArray.map { it.jsonObject }
        for (entry in index) {
            val country = entry.getValue("country").jsonPrimitive.content
            val c = catalogue(country)
            assertEquals(entry.getValue("importedSigns").jsonPrimitive.int, c.signs.size, country)
            val excluded = entry.getValue("excluded").jsonArray.map { it.jsonObject.getValue("osmValuePart").jsonPrimitive.content }
            for (id in excluded) assertNull(c.getByOsmValuePart(id), "$country:$id")
            for (sign in c.signs) {
                val bytes = assertNotNull(TrafficSignTestData.readBytesOrNull("$RESOURCES_DIR/${sign.imagePath(country)}"), "image of $country:${sign.osmValuePart}")
                val vector = bytes.decodeToImageVector(density)
                assertTrue(vector.defaultWidth.value > 0 && vector.defaultHeight.value > 0, "$country:${sign.osmValuePart}")
            }
        }
    }

    @Test fun `every imported sign of every country can be selected and is written and read back as itself`() {
        for (country in bundledCatalogues.availableCountries) {
            val c = catalogue(country)
            val notSelectable = c.signs.filter { !c.isSelectable(it) }.map { it.osmValuePart }
            assertEquals(emptyList(), notSelectable, country)
            for (sign in c.selectableSigns) {
                val value = TrafficSignValue.of(country, listOf(TrafficSignGroup(TrafficSignPart.of(sign))))
                val read = parseTrafficSignValue(value.toOsmValue(), c)
                assertEquals(value.toOsmValue(), read.toOsmValue(), "$country:${sign.osmValuePart}")
                assertEquals(sign, read.parts.single().sign, "$country:${sign.osmValuePart}")
            }
        }
    }

    @Test fun `signs that were not imported are not offered, existing values with them are kept as they are`() {
        // FR:A1b has no image, IT:II.250 an image that can not be converted, BE:B1+M1 and PL:B-9,T-22
        // ids that can not be written unambiguously
        for ((country, id) in listOf("FR" to "A1b", "IT" to "II.250", "BE" to "B1+M1", "PL" to "B-9,T-22")) {
            val c = catalogue(country)
            assertNull(c.getByOsmValuePart(id), "$country:$id")
            assertTrue(c.search(id, TrafficSignPickerKind.MAIN).none { it.osmValuePart == id }, "$country:$id")
        }
        val fr = catalogue("FR")
        val existing = mapOf("highway" to "residential", "traffic_sign:backward" to "FR:A1b", "traffic_sign:forward" to "FR:B9b")
        val t = TrafficSignWaySigns(parseTrafficSignValue("FR:B14[50]", fr), null).deriveWayTags(existing)
        val result = existing.after { t.applyTo(it) }
        assertEquals("FR:A1b", result["traffic_sign:backward"])
        assertEquals("FR:B14[50]", result["traffic_sign:forward"])
    }

    @Test fun `the ids are checked like the import checks them`() {
        for (ok in listOf("237", "274[30]", "1042-32[PH off;Mo-Sa 8:30-11:30,16:00-18:00]", "\"Kfz-Verkehr frei\"", "Kein Winterdienst", "J1 bis", "Rb-76/76A", "52.10a[30]", "A16[10%]")) {
            assertNull(osmValuePartProblem(ok), ok)
        }
        for (bad in listOf("", " 1", "1 ", "B1+M1", "C5 + C7", "B-9,T-22", "C1;C2", "DE:250", "none", "No", "city_limit", "(", "274[30", "274[30]x", "274[]", "\"a\"b\"", "\"\"", "a=b")) {
            assertNotNull(osmValuePartProblem(bad), bad)
        }
    }

    /* ---------------------------------------------- DE ---------------------------------------------- */

    @Test fun `DE is unchanged and keeps its derived tags`() {
        val de = catalogue("DE")
        assertEquals(TrafficSignTestData.catalogueDE.signs, de.signs)
        assertEquals(de.signs, de.selectableSigns)
        assertTrue(de.hasModifierCompatibility)
        val path = parseTrafficSignValue("DE:237", de).deriveWayTags(mapOf("highway" to "path"))
        val result = mapOf("highway" to "path").after { path.applyTo(it) }
        assertEquals("cycleway", result["highway"])
        assertEquals("DE:237", result["traffic_sign"])
        val ban = parseTrafficSignValue("DE:250", de).deriveWayTags(mapOf("highway" to "residential"))
        assertTrue(ban.changes.any { it.key == "vehicle" && it is TrafficSignTagChange.Set && it.value == "no" })
        assertTrue(ban.proposals.isNotEmpty())
    }

    /* ----------------------------------------- FR and AT ----------------------------------------- */

    @Test fun `FR B9b can be picked and only the sign is written`() {
        val fr = catalogue("FR")
        val sign = assertNotNull(fr.getByOsmValuePart("B9b"))
        assertTrue(fr.isSelectable(sign))
        assertEquals(sign, fr.search("B9b", TrafficSignPickerKind.MAIN).first())
        val value = TrafficSignValue.of("FR", listOf(TrafficSignGroup(TrafficSignPart.of(sign))))
        assertEquals("FR:B9b", value.toOsmValue())
        // the catalogue recommends bicycle=no for B9b: this is not applied
        val tags = mapOf("highway" to "residential", "maxspeed" to "50", "oneway" to "yes", "surface" to "asphalt")
        val t = value.deriveWayTags(tags)
        assertEquals(listOf<TrafficSignTagChange>(TrafficSignTagChange.Set("traffic_sign", "FR:B9b", null)), t.changes)
        assertTrue(t.proposals.isEmpty())
        assertTrue(t.notDerived.isEmpty())
        assertEquals(TrafficSignDerivation.TRAFFIC_SIGN_ONLY, t.derivation)
        val written = tags.after { t.applyConfirmed(it, emptyMap()) }
        assertEquals(tags + ("traffic_sign" to "FR:B9b"), written)
    }

    @Test fun `AT signs can be picked, also with a fixed value in brackets`() {
        val at = catalogue("AT")
        for (id in listOf("52.1", "52.10a[30]")) {
            val sign = assertNotNull(at.getByOsmValuePart(id), id)
            assertTrue(at.isSelectable(sign), id)
            val value = TrafficSignValue.of("AT", listOf(TrafficSignGroup(TrafficSignPart.of(sign))))
            assertEquals("AT:$id", value.toOsmValue())
            val read = parseTrafficSignValue("AT:$id", at).parts.single()
            assertEquals(sign, read.sign)
            assertFalse(read.hasUnexpectedValue, id)
            val t = value.deriveWayTags(mapOf("highway" to "residential"))
            assertEquals(setOf("traffic_sign"), t.changes.map { it.key }.toSet())
        }
    }

    @Test fun `signs with a variable value whose value is only given in the id are read`() {
        val au = catalogue("AU")
        val ten = parseTrafficSignValue("AU:R4-1[10]", au).parts.single()
        assertEquals(au.getByOsmValuePart("R4-1[10]"), ten.sign)
        assertEquals("10", ten.value)
        val other = parseTrafficSignValue("AU:R4-1[45]", au).parts.single()
        assertEquals("R4-1", other.sign?.signId)
        assertEquals("R4-1[45]", other.text)
        assertFalse(other.isMissingValue || other.hasUnexpectedValue)
    }

    @Test fun `existing tags like bicycle=no and unknown values are kept`() {
        val fr = catalogue("FR")
        val tags = mapOf(
            "highway" to "residential", "bicycle" to "no", "foot" to "yes", "access" to "destination",
            "vehicle" to "yes", "maxspeed" to "30", "oneway" to "-1", "fixme" to "x",
            "traffic_sign:backward" to "FR:QQ99;something odd",
        )
        val t = TrafficSignWaySigns(parseTrafficSignValue("FR:B9b", fr), null).deriveWayTags(tags)
        val result = tags.after { t.applyConfirmed(it, emptyMap()) }
        assertEquals(tags + ("traffic_sign:forward" to "FR:B9b"), result)
        assertEquals(setOf("traffic_sign:forward"), t.changes.map { it.key }.toSet())
        // the unknown value is read, but kept as it is written
        assertEquals("FR:QQ99;something odd", parseTrafficSignValue("FR:QQ99;something odd", fr).toOsmValue())
    }

    @Test fun `directions are tagged as for Germany`() {
        val fr = catalogue("FR")
        val b9b = parseTrafficSignValue("FR:B9b", fr)
        val tags = mapOf("highway" to "residential", "traffic_sign" to "FR:B1")
        // both directions the same: traffic_sign
        val both = TrafficSignWaySigns.both(b9b).deriveWayTags(tags)
        assertEquals(mapOf("highway" to "residential", "traffic_sign" to "FR:B9b"), tags.after { both.applyTo(it) })
        // different: per direction, the general value is removed
        val split = TrafficSignWaySigns(b9b, TrafficSignValue.NONE).deriveWayTags(tags)
        assertEquals(
            mapOf("highway" to "residential", "traffic_sign:forward" to "FR:B9b", "traffic_sign:backward" to "none"),
            tags.after { split.applyTo(it) }
        )
        // one direction not surveyed: it keeps what it has
        val one = TrafficSignWaySigns(b9b, null).deriveWayTags(tags)
        assertEquals(
            mapOf("highway" to "residential", "traffic_sign:forward" to "FR:B9b", "traffic_sign:backward" to "FR:B1"),
            tags.after { one.applyTo(it) }
        )
        // for the reversed way, the same signs per direction are swapped
        assertEquals(TrafficSignWaySigns(TrafficSignValue.NONE, b9b), TrafficSignWaySigns(b9b, TrafficSignValue.NONE).reversed())
    }

    @Test fun `no country without curated rules gets any other tag from any of its signs`() {
        val withoutRules = bundledCatalogues.availableCountries - SUPPORTED_WAY_TRAFFIC_SIGNS.keys
        assertTrue("FR" in withoutRules && "AT" in withoutRules)
        val tagsList = listOf(
            mapOf("highway" to "residential"),
            mapOf("highway" to "path", "bicycle" to "no", "foot" to "yes", "access" to "private", "maxspeed" to "50", "oneway" to "yes", "segregated" to "yes"),
            mapOf("highway" to "cycleway", "motor_vehicle" to "no", "vehicle:conditional" to "no @ (Mo-Fr)"),
        )
        for (country in withoutRules) {
            val c = catalogue(country)
            for (sign in c.selectableSigns) {
                val value = TrafficSignValue.of(country, listOf(TrafficSignGroup(TrafficSignPart.of(sign))))
                for (tags in tagsList) {
                    for (signs in listOf(TrafficSignWaySigns.both(value), TrafficSignWaySigns(value, null), TrafficSignWaySigns(null, value))) {
                        val t = signs.deriveWayTags(tags)
                        val label = "$country:${sign.osmValuePart} on $tags"
                        assertTrue(t.changes.all { it.key.startsWith("traffic_sign") }, "$label: ${t.changes}")
                        assertTrue(t.proposals.isEmpty(), label)
                        assertEquals(TrafficSignDerivation.TRAFFIC_SIGN_ONLY, t.derivation, label)
                        val result = tags.after { t.applyConfirmed(it, null) }
                        assertEquals(tags, result.filterKeys { !it.startsWith("traffic_sign") }, label)
                    }
                }
            }
        }
    }

    /* --------------------------------------- picker capabilities --------------------------------------- */

    @Test fun `additional signs are only combined where the catalogue says what fits`() {
        assertTrue(catalogue("DE").hasModifierCompatibility)
        for (country in bundledCatalogues.availableCountries - "DE") {
            assertFalse(catalogue(country).hasModifierCompatibility, country)
        }
    }

    @Test fun `the history of other countries is not offered`() {
        val fr = catalogue("FR")
        val de = catalogue("DE")
        val deCombination = TrafficSignCombination("DE:237", "DE:237")
        val frCombination = TrafficSignCombination("FR:B9b", "none")
        assertFalse(deCombination.isUsableWith(fr))
        assertTrue(deCombination.isUsableWith(de))
        assertTrue(frCombination.isUsableWith(fr))
        assertFalse(frCombination.isUsableWith(de))
        assertTrue(TrafficSignCombination("none", "").isUsableWith(fr))
    }

    /* --------------------------------------------- nodes --------------------------------------------- */

    @Test fun `on nodes only traffic_sign is written`() {
        val fr = catalogue("FR")
        val tags = mapOf("highway" to "stop", "direction" to "forward", "traffic_sign" to "FR:AB4", "bicycle" to "no")
        val original = DirectionSelection.of(parseTrafficSignNodeValue(tags, fr))
        val b9b = DirectionSelection.Signed(parseTrafficSignValue("FR:B9b", fr))
        assertEquals(TrafficSignTagChange.Set("traffic_sign", "FR:B9b", "FR:AB4"), trafficSignNodeChange(tags, original, b9b))
        // nothing changed, no signs, or "no signs": nothing is written
        assertNull(trafficSignNodeChange(tags, original, original))
        assertNull(trafficSignNodeChange(tags, original, DirectionSelection.NotSurveyed))
        assertNull(trafficSignNodeChange(tags, original, DirectionSelection.None))
        // a new sign on a node without any
        assertEquals(
            TrafficSignTagChange.Set("traffic_sign", "FR:B9b", null),
            trafficSignNodeChange(mapOf("highway" to "give_way"), DirectionSelection.NotSurveyed, b9b)
        )
    }

    @Test fun `nodes with signs per direction are not edited`() {
        val tags = mapOf("traffic_sign:forward" to "DE:206", "traffic_sign" to "DE:205")
        assertFalse(isTrafficSignNodeEditable(tags))
        val de = catalogue("DE")
        val stop = DirectionSelection.Signed(parseTrafficSignValue("DE:206", de))
        assertNull(trafficSignNodeChange(tags, DirectionSelection.NotSurveyed, stop))
        assertTrue(isTrafficSignNodeEditable(mapOf("traffic_sign" to "DE:205", "direction" to "45", "traffic_sign:direction" to "forward")))
    }

    @Test fun `the country of a node is only certain away from borders`() {
        val code = TrafficSignTestCountries.getCountryCode
        assertEquals(TrafficSignWayCountry.Within("DE"), trafficSignNodeCountry(de1, code))
        assertEquals(TrafficSignWayCountry.Within("FR"), trafficSignNodeCountry(fr1, code))
        // ~7 m from the border between DE and AT at lon 10
        assertEquals(TrafficSignWayCountry.CrossesBorder(setOf("DE", "AT")), trafficSignNodeCountry(LatLon(50.0, 9.9999), code))
        assertEquals(TrafficSignWayCountry.Uncertain(null), trafficSignNodeCountry(unknown1, code))
        assertEquals(TrafficSignWayCountry.Uncertain(null), trafficSignNodeCountry(null, code))
    }

    @Test fun `new nodes can be created, only where the position is certainly in a country with a catalogue`() {
        val code = TrafficSignTestCountries.getCountryCode
        val has = bundledCatalogues::isAvailable
        val overlay = TrafficSignOverlay(code, TrafficSignTestCountries.getContainingCountryCodes, has)
        assertTrue(overlay.isCreateNodeEnabled)
        for (p in listOf(de1, at1, fr1)) assertTrue(isEditable(trafficSignNodeCountry(p, code), has), "$p")
        // no catalogue, unknown country, near a border: nothing can be created, never a fallback to DE
        for (p in listOf(gb1, unknown1, LatLon(50.0, 9.9999))) assertFalse(isEditable(trafficSignNodeCountry(p, code), has), "$p")
        assertEquals(Res.string.traffic_sign_overlay_create_node_country, newNodeUnavailableExplanation(trafficSignNodeCountry(gb1, code), has))
        assertEquals(Res.string.traffic_sign_overlay_create_node_uncertain, newNodeUnavailableExplanation(trafficSignNodeCountry(unknown1, code), has))
        assertEquals(Res.string.traffic_sign_overlay_create_node_uncertain, newNodeUnavailableExplanation(trafficSignNodeCountry(LatLon(50.0, 9.9999), code), has))
    }

    @Test fun `a new node with only traffic_sign is shown in the overlay and can be edited again`() {
        val overlay = TrafficSignOverlay(TrafficSignTestCountries.getCountryCode, TrafficSignTestCountries.getContainingCountryCodes, bundledCatalogues::isAvailable)
        val created = node(-1, fr1, mapOf("traffic_sign" to "FR:B9b"))
        val styled = overlay.getStyledElements(createMapData(mapOf(created to ElementPointGeometry(fr1)))).toList()
        assertEquals(listOf(created), styled.map { it.first })
        assertTrue(isTrafficSignNodeEditable(created.tags))
        // a node without signs is not shown: nothing empty is ever offered
        assertTrue(overlay.getStyledElements(createMapData(mapOf(node(-2, fr1) to ElementPointGeometry(fr1)))).none())
    }

    @Test fun `the value of a new node is only traffic_sign, written as read`() {
        for ((country, value) in listOf("FR" to "FR:B9b", "DE" to "DE:206", "AT" to "AT:52.10a[30]")) {
            val catalogue = catalogue(country)
            val picked = DirectionSelection.Signed(parseTrafficSignValue(value, catalogue))
            val change = trafficSignNodeChange(emptyMap(), DirectionSelection.NotSurveyed, picked)
            assertEquals(TrafficSignTagChange.Set("traffic_sign", value, null), change, value)
        }
        // nothing picked: nothing to create
        assertNull(trafficSignNodeChange(emptyMap(), DirectionSelection.NotSurveyed, DirectionSelection.NotSurveyed))
        assertNull(trafficSignNodeChange(emptyMap(), DirectionSelection.NotSurveyed, DirectionSelection.None))
    }
}
