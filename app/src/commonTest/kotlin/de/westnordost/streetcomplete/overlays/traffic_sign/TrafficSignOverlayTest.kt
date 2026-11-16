package de.westnordost.streetcomplete.overlays.traffic_sign

import de.westnordost.streetcomplete.Prefs
import de.westnordost.streetcomplete.data.meta.CountryInfo
import de.westnordost.streetcomplete.data.meta.IncompleteCountryInfo
import de.westnordost.streetcomplete.data.osm.mapdata.Way
import de.westnordost.streetcomplete.data.overlays.SelectedOverlayController
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.at1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.at2
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.de1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.de2
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.fr1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.fr2
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.gb1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.gb2
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.line
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.unknown1
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestCountries.unknown2
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignWayCountry
import de.westnordost.streetcomplete.overlays.overlaysRegistry
import de.westnordost.streetcomplete.testutils.inMemoryPrefs
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.Node
import de.westnordost.streetcomplete.data.osm.geometry.ElementPolylinesGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.overlays.OverlayColor
import de.westnordost.streetcomplete.data.overlays.OverlayStyle
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignDerivation
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignDirection
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignNotDerived
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTagState
import de.westnordost.streetcomplete.osm.traffic_sign.trafficSignTagState
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTagConflict
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignTestData
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignWaySigns
import de.westnordost.streetcomplete.osm.traffic_sign.deriveWayTags
import de.westnordost.streetcomplete.osm.traffic_sign.parseDE
import de.westnordost.streetcomplete.testutils.createMapData
import de.westnordost.streetcomplete.testutils.node
import de.westnordost.streetcomplete.testutils.way
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrafficSignOverlayTest {

    /* ------------------------------------- style ------------------------------------- */

    private fun style(vararg tags: Pair<String, String>) = trafficSignWayStyle(mapOf(*tags)).stroke!!

    @Test fun `nothing surveyed is red`() {
        assertEquals(OverlayStyle.Stroke(OverlayColor.Red), style("highway" to "path"))
    }

    @Test fun `one direction surveyed is orange and dashed`() {
        val expected = OverlayStyle.Stroke(OverlayColor.Orange, dashed = true)
        assertEquals(expected, style("traffic_sign:forward" to "DE:237"))
        assertEquals(expected, style("traffic_sign:backward" to "none"))
    }

    @Test fun `both directions with signs are green`() {
        assertEquals(OverlayStyle.Stroke(OverlayColor.Lime), style("traffic_sign" to "DE:237"))
        assertEquals(
            OverlayStyle.Stroke(OverlayColor.Lime),
            style("traffic_sign:forward" to "DE:237", "traffic_sign:backward" to "DE:239")
        )
    }

    @Test fun `both directions none are gray`() {
        assertEquals(OverlayStyle.Stroke(NoSignsColor), style("traffic_sign" to "none"))
        assertEquals(
            OverlayStyle.Stroke(NoSignsColor),
            style("traffic_sign:forward" to "none", "traffic_sign:backward" to "none")
        )
    }

    @Test fun `signs and none mixed count as surveyed and have their own color`() {
        val mixed = style("traffic_sign:forward" to "DE:237", "traffic_sign:backward" to "none")
        assertEquals(OverlayStyle.Stroke(OverlayColor.Teal), mixed)
        assertNotEquals(OverlayStyle.Stroke(OverlayColor.Lime), mixed)
        assertNotEquals(OverlayStyle.Stroke(NoSignsColor), mixed)
        assertEquals(mixed, style("traffic_sign:forward" to "none", "traffic_sign:backward" to "DE:237"))
    }

    @Test fun `not surveyed and none are different states`() {
        assertEquals(
            TrafficSignTagState.NOT_SURVEYED,
            trafficSignTagState(emptyMap(), TrafficSignDirection.FORWARD)
        )
        assertEquals(
            TrafficSignTagState.NONE,
            trafficSignTagState(mapOf("traffic_sign" to "none"), TrafficSignDirection.FORWARD)
        )
    }

    @Test fun `directional value wins over general value`() {
        val tags = mapOf("traffic_sign" to "none", "traffic_sign:forward" to "DE:237")
        assertEquals(TrafficSignTagState.SIGNED, trafficSignTagState(tags, TrafficSignDirection.FORWARD))
        assertEquals(TrafficSignTagState.NONE, trafficSignTagState(tags, TrafficSignDirection.BACKWARD))
    }

    @Test fun `unreadable values count as signed, blank as not surveyed`() {
        assertEquals(
            TrafficSignTagState.SIGNED,
            trafficSignTagState(mapOf("traffic_sign" to "AT:52"), TrafficSignDirection.BACKWARD)
        )
        assertEquals(
            TrafficSignTagState.NOT_SURVEYED,
            trafficSignTagState(mapOf("traffic_sign" to " "), TrafficSignDirection.BACKWARD)
        )
    }

    /* ---------------------------------- element selection ---------------------------------- */

    // only DE has a catalogue here: the other countries stand for countries without one
    private val overlay = TrafficSignOverlay(
        TrafficSignTestCountries.getCountryCode,
        TrafficSignTestCountries.getContainingCountryCodes,
        hasCatalogue = { it == "DE" },
    )

    private fun styledWithStyle(vararg ways: Pair<Way, ElementPolylinesGeometry?>): Map<Long, OverlayStyle> =
        overlay.getStyledElements(createMapData(ways.toMap())).associate { it.first.id to it.second }

    private fun styled(vararg ways: Pair<Way, ElementPolylinesGeometry?>) =
        styledWithStyle(*ways).keys.sorted()

    private val road = "highway" to "residential"

    @Test fun `ways in Germany are shown`() {
        val result = styled(
            way(1, tags = mapOf("highway" to "residential")) to line(de1, de2),
            way(2, tags = mapOf("highway" to "footway")) to line(de1, de2),
        )
        assertEquals(listOf(1L, 2L), result)
    }

    @Test fun `ways in Germany keep the style of their survey state`() {
        val styles = styledWithStyle(
            way(1, tags = mapOf(road)) to line(de1, de2),
            way(2, tags = mapOf(road, "traffic_sign" to "DE:250")) to line(de1, de2),
            way(3, tags = mapOf(road, "traffic_sign:forward" to "DE:250")) to line(de1, de2),
            way(4, tags = mapOf(road, "traffic_sign" to "none")) to line(de1, de2),
            way(5, tags = mapOf(road, "traffic_sign" to "AT:52")) to line(de1, de2),
        )
        assertEquals(OverlayStyle.Polyline(OverlayStyle.Stroke(OverlayColor.Red)), styles[1])
        assertEquals(OverlayStyle.Polyline(OverlayStyle.Stroke(OverlayColor.Lime)), styles[2])
        assertEquals(OverlayStyle.Polyline(OverlayStyle.Stroke(OverlayColor.Orange, dashed = true)), styles[3])
        assertEquals(OverlayStyle.Polyline(OverlayStyle.Stroke(NoSignsColor)), styles[4])
        assertEquals(OverlayStyle.Polyline(OverlayStyle.Stroke(OverlayColor.Lime)), styles[5])
    }

    @Test fun `ways without traffic sign tags outside of Germany are not shown`() {
        for (geometry in listOf(line(at1, at2), line(fr1, fr2), line(gb1, gb2), line(unknown1, unknown2))) {
            assertEquals(emptyList(), styled(way(1, tags = mapOf(road)) to geometry), "$geometry")
            // blank values count as not set
            assertEquals(emptyList(), styled(way(1, tags = mapOf(road, "traffic_sign" to " ")) to geometry))
        }
    }

    @Test fun `ways with traffic sign tags outside of Germany are shown read-only`() {
        for (geometry in listOf(line(at1, at2), line(fr1, fr2), line(gb1, gb2), line(unknown1, unknown2))) {
            for (tag in listOf(
                "traffic_sign" to "FR:B1",
                "traffic_sign" to "GB:616",
                "traffic_sign:forward" to "FR:B1",
                "traffic_sign:backward" to "FR:B1",
                // not interpreted: unknown values, values of other countries, none, yes
                "traffic_sign" to "DE:250",
                "traffic_sign" to "something unreadable",
                "traffic_sign" to "none",
                "traffic_sign" to "yes",
            )) {
                val styles = styledWithStyle(way(1, tags = mapOf(road, tag)) to geometry)
                assertEquals(mapOf(1L to trafficSignReadOnlyStyle), styles, "$tag $geometry")
            }
        }
    }

    @Test fun `read-only style can not be mistaken for any survey state`() {
        val surveyColors = listOf(
            OverlayColor.Red, OverlayColor.Orange, OverlayColor.Lime, OverlayColor.Teal, NoSignsColor
        )
        val stroke = trafficSignReadOnlyStyle.stroke!!
        assertTrue(stroke.color !in surveyColors)
        assertFalse(stroke.dashed)
    }

    @Test fun `ways that cross the border are only shown read-only and only with traffic sign tags`() {
        for (geometry in listOf(line(de1, de2, at1), line(at1, de1, de2), line(de1, fr1))) {
            assertEquals(emptyList(), styled(way(1, tags = mapOf(road)) to geometry))
            assertEquals(
                mapOf(1L to trafficSignReadOnlyStyle),
                styledWithStyle(way(1, tags = mapOf(road, "traffic_sign" to "DE:250")) to geometry)
            )
        }
    }

    @Test fun `way that leaves Germany and re-enters it is not editable`() {
        // first, middle and last vertex in Germany
        val geometry = ElementPolylinesGeometry(listOf(listOf(de1, at1, de2, at2, de1)), de2)
        assertEquals(emptyList(), styled(way(1, tags = mapOf(road)) to geometry))
        assertEquals(
            mapOf(1L to trafficSignReadOnlyStyle),
            styledWithStyle(way(1, tags = mapOf(road, "traffic_sign:forward" to "DE:250")) to geometry)
        )
    }

    @Test fun `way along the border on its German side is editable`() {
        val geometry = line(LatLon(50.0, 9.9995), LatLon(50.01, 9.9996), LatLon(50.02, 9.9995))
        assertEquals(listOf(1L), styled(way(1, tags = mapOf(road)) to geometry))
        assertEquals(
            mapOf(1L to trafficSignWayStyle(mapOf(road, "traffic_sign" to "DE:250"))),
            styledWithStyle(way(1, tags = mapOf(road, "traffic_sign" to "DE:250")) to geometry)
        )
    }

    @Test fun `way along the border on both sides of it is not editable`() {
        val geometry = line(LatLon(50.0, 9.9995), LatLon(50.01, 10.0005), LatLon(50.02, 9.9995))
        assertEquals(emptyList(), styled(way(1, tags = mapOf(road)) to geometry))
        assertEquals(
            mapOf(1L to trafficSignReadOnlyStyle),
            styledWithStyle(way(1, tags = mapOf(road, "traffic_sign" to "DE:250")) to geometry)
        )
    }

    @Test fun `mixed map data is decided per way`() {
        val styles = styledWithStyle(
            way(1, tags = mapOf(road)) to line(de1, de2),
            way(2, tags = mapOf(road, "traffic_sign" to "DE:250")) to line(de1, de2),
            way(3, tags = mapOf(road)) to line(at1, at2),
            way(4, tags = mapOf(road, "traffic_sign" to "AT:52")) to line(at1, at2),
            way(5, tags = mapOf(road, "traffic_sign" to "DE:250")) to line(de1, at1),
            way(6, tags = mapOf(road, "traffic_sign:backward" to "FR:B1")) to line(fr1, fr2),
        )
        assertEquals(setOf(1L, 2L, 4L, 5L, 6L), styles.keys)
        assertEquals(OverlayColor.Red, (styles[1] as OverlayStyle.Polyline).stroke!!.color)
        assertEquals(OverlayColor.Lime, (styles[2] as OverlayStyle.Polyline).stroke!!.color)
        assertEquals(trafficSignReadOnlyStyle, styles[4])
        assertEquals(trafficSignReadOnlyStyle, styles[5])
        assertEquals(trafficSignReadOnlyStyle, styles[6])
    }

    @Test fun `ways without geometry or highway or areas are not shown`() {
        assertEquals(
            emptyList(),
            styled(
                way(1, tags = mapOf("highway" to "residential")) to null,
                way(2, tags = mapOf("building" to "yes")) to line(de1, de2),
                way(3, tags = mapOf("highway" to "pedestrian", "area" to "yes")) to line(de1, de2),
                way(4, tags = mapOf("highway" to "bus_stop")) to line(de1, de2),
                way(5, tags = mapOf("building" to "yes", "traffic_sign" to "FR:B1")) to line(fr1, fr2),
            )
        )
    }

    @Test fun `styled way has the style of its tags`() {
        val w = way(1, tags = mapOf("highway" to "path", "traffic_sign" to "none"))
        val (_, s) = overlay.getStyledElements(createMapData(mapOf(w to line(de1, de2)))).single()
        assertEquals(OverlayStyle.Polyline(OverlayStyle.Stroke(NoSignsColor)), s)
    }

    @Test fun `only ways completely in Germany are editable`() {
        val onlyDE = { c: String -> c == "DE" }
        assertTrue(isEditable(TrafficSignWayCountry.Within("DE"), onlyDE))
        assertFalse(isEditable(TrafficSignWayCountry.Within("FR"), onlyDE))
        assertFalse(isEditable(TrafficSignWayCountry.Within("GB"), onlyDE))
        assertFalse(isEditable(TrafficSignWayCountry.Within("AT"), onlyDE))
        assertFalse(isEditable(TrafficSignWayCountry.CrossesBorder(setOf("DE", "AT")), onlyDE))
        assertFalse(isEditable(TrafficSignWayCountry.Uncertain("DE"), onlyDE))
        assertFalse(isEditable(TrafficSignWayCountry.Uncertain(null), onlyDE))
        // any country with a catalogue
        val withFR = { c: String -> c == "DE" || c == "FR" }
        assertTrue(isEditable(TrafficSignWayCountry.Within("FR"), withFR))
        assertFalse(isEditable(TrafficSignWayCountry.CrossesBorder(setOf("DE", "FR")), withFR))
        assertFalse(isEditable(TrafficSignWayCountry.Uncertain("FR"), withFR))
    }

    @Test fun `styling does not change the tags of the ways`() {
        val tags = mapOf(road, "traffic_sign:forward" to "FR:B1", "traffic_sign" to "yes")
        val w = way(1, tags = tags)
        val (element, _) = overlay.getStyledElements(createMapData(mapOf(w to line(fr1, fr2)))).single()
        assertEquals(tags, element.tags)
    }

    @Test fun `only the traffic sign tags with values are shown read-only, unchanged and in order`() {
        assertEquals(
            listOf("traffic_sign" to " DE:250 ", "traffic_sign:forward" to "FR:B1", "traffic_sign:backward" to "x"),
            readOnlyTrafficSignTags(mapOf(
                "traffic_sign:backward" to "x",
                "traffic_sign:forward" to "FR:B1",
                "traffic_sign" to " DE:250 ",
                "traffic_sign:conditional" to "y",
                "highway" to "residential",
            ))
        )
        assertEquals(emptyList(), readOnlyTrafficSignTags(mapOf("traffic_sign" to "", "traffic_sign:forward" to " ")))
        assertFalse(hasTrafficSignTags(mapOf("traffic_sign" to "", "highway" to "residential")))
        assertTrue(hasTrafficSignTags(mapOf("traffic_sign:backward" to "FR:B1")))
    }

    /* ---------------------------------------- nodes ---------------------------------------- */

    private fun styledNodes(vararg nodes: Node): Map<Long, OverlayStyle> =
        overlay.getStyledElements(createMapData(nodes.associate<Node, Element, ElementGeometry?> { it to ElementPointGeometry(it.position) }))
            .associate { it.first.id to it.second }

    @Test fun `nodes with traffic sign tags are shown read-only in every country`() {
        for (pos in listOf(de1, at1, fr1, gb1, unknown1)) {
            for (tag in listOf(
                "traffic_sign" to "stop",
                "traffic_sign" to "give_way",
                "traffic_sign" to "GB:616",
                "traffic_sign" to "FR:AB4",
                "traffic_sign" to "DE:206",
                "traffic_sign" to "city_limit",
                "traffic_sign" to "yes",
                "traffic_sign" to "something unreadable",
                "traffic_sign:forward" to "GB:616",
                "traffic_sign:backward" to "GB:616",
            )) {
                assertEquals(mapOf(1L to trafficSignNodeStyle), styledNodes(node(1, pos, mapOf(tag))), "$tag $pos")
            }
        }
    }

    @Test fun `nodes without traffic sign tags are not shown`() {
        assertEquals(
            emptyMap(),
            styledNodes(
                node(1, gb1),
                node(2, gb1, mapOf("highway" to "stop")),
                node(3, gb1, mapOf("traffic_sign" to " ")),
                node(4, gb1, mapOf("direction" to "forward")),
                node(5, gb1, mapOf("traffic_sign:direction" to "forward")),
                node(6, gb1, mapOf("highway" to "give_way", "direction" to "backward")),
            )
        )
    }

    @Test fun `nodes whose traffic sign tags are only none or no are not shown`() {
        assertEquals(
            emptyMap(),
            styledNodes(
                node(1, gb1, mapOf("traffic_sign" to "none")),
                node(2, gb1, mapOf("traffic_sign" to "no")),
                node(3, de1, mapOf("traffic_sign" to "none", "traffic_sign:forward" to "no")),
                node(4, gb1, mapOf("traffic_sign:backward" to " NONE ")),
            )
        )
    }

    @Test fun `nodes with none or no and another sign value stay shown`() {
        val shown = mapOf(1L to trafficSignNodeStyle, 2L to trafficSignNodeStyle, 3L to trafficSignNodeStyle)
        assertEquals(
            shown,
            styledNodes(
                node(1, gb1, mapOf("traffic_sign" to "none", "traffic_sign:forward" to "GB:616")),
                node(2, de1, mapOf("traffic_sign" to "no", "traffic_sign:backward" to "DE:206")),
                node(3, gb1, mapOf("traffic_sign:forward" to "none", "traffic_sign:backward" to "yes")),
            )
        )
    }

    @Test fun `nodes with yes, unknown or uninterpretable values stay shown`() {
        assertEquals(
            (1L..4L).associateWith { trafficSignNodeStyle },
            styledNodes(
                node(1, gb1, mapOf("traffic_sign" to "yes")),
                node(2, gb1, mapOf("traffic_sign" to "something odd")),
                node(3, de1, mapOf("traffic_sign" to "none;DE:206")),
                node(4, gb1, mapOf("traffic_sign" to "maxspeed")),
            )
        )
    }

    @Test fun `node style is a point that can not be mistaken for a way survey state`() {
        assertTrue(trafficSignNodeStyle.icon != null)
        assertEquals(null, trafficSignNodeStyle.label)
        assertEquals(null, trafficSignNodeStyle.color)
        assertFalse(trafficSignNodeStyle.disabled)
    }

    @Test fun `nodes do not change how ways in Germany are shown`() {
        val signNode = node(10, de1, mapOf("traffic_sign" to "DE:206", "highway" to "stop"))
        val data = createMapData(mapOf(
            way(1, tags = mapOf(road)) to line(de1, de2),
            way(2, tags = mapOf(road, "traffic_sign:forward" to "DE:250", "traffic_sign:backward" to "none")) to line(de1, de2),
            signNode to ElementPointGeometry(de1),
            node(11, de2) to ElementPointGeometry(de2),
        ))
        val styles = overlay.getStyledElements(data).associate { it.first.id to it.second }
        assertEquals(
            mapOf(
                1L to trafficSignWayStyle(mapOf(road)),
                2L to trafficSignWayStyle(mapOf(road, "traffic_sign:forward" to "DE:250", "traffic_sign:backward" to "none")),
                10L to trafficSignNodeStyle,
            ),
            styles
        )
    }

    @Test fun `nodes and ways are both shown in Great Britain`() {
        val data = createMapData(mapOf(
            way(1, tags = mapOf(road)) to line(gb1, gb2),
            way(2, tags = mapOf(road, "traffic_sign" to "GB:616")) to line(gb1, gb2),
            node(10, gb1, mapOf("traffic_sign" to "GB:616", "direction" to "forward")) to ElementPointGeometry(gb1),
        ))
        val styles = overlay.getStyledElements(data).associate { it.first.id to it.second }
        assertEquals(mapOf(2L to trafficSignReadOnlyStyle, 10L to trafficSignNodeStyle), styles)
    }

    @Test fun `styling does not change the tags of nodes`() {
        val tags = mapOf("traffic_sign" to "stop", "direction" to "forward")
        val (element, _) = overlay.getStyledElements(
            createMapData(mapOf(node(1, gb1, tags) to ElementPointGeometry(gb1)))
        ).single()
        assertEquals(tags, element.tags)
    }

    @Test fun `node tags are shown read-only with the tags of the direction`() {
        assertEquals(
            listOf(
                "traffic_sign" to "GB:616",
                "traffic_sign:forward" to "GB:610",
                "traffic_sign:direction" to "backward",
                "direction" to "90",
            ),
            readOnlyTrafficSignTags(
                mapOf(
                    "direction" to "90",
                    "traffic_sign:direction" to "backward",
                    "traffic_sign:forward" to "GB:610",
                    "traffic_sign" to "GB:616",
                    "highway" to "stop",
                ),
                TRAFFIC_SIGN_NODE_KEYS
            )
        )
    }

    /* -------------------------------- map movement and selection ----------------------------- */

    @Test fun `overlay stays selected when the map is moved between countries`() {
        Prefs.preferences = inMemoryPrefs()
        val registry = overlaysRegistry(
            getCountryInfoByLocation = { CountryInfo(null, listOf(IncompleteCountryInfo())) },
            getCountryCodeByLocation = TrafficSignTestCountries.getCountryCode,
            getFeature = { null },
            prefs = Prefs.preferences,
            getContainingCountryCodes = TrafficSignTestCountries.getContainingCountryCodes,
            hasTrafficSignCatalogue = { it == "DE" },
        )
        val controller = SelectedOverlayController(Prefs.preferences, registry)
        val trafficSignOverlay = registry.first { it is TrafficSignOverlay }
        controller.selectedOverlay = trafficSignOverlay
        assertEquals("TrafficSignOverlay", Prefs.preferences.selectedOverlayName)

        val tagged = mapOf(road, "traffic_sign" to "DE:250")
        fun styleOfWayIn(a: LatLon, b: LatLon): OverlayStyle? {
            // what the map does after it has been moved: get the styled elements of the new area
            val selected = controller.selectedOverlay!!
            return selected.getStyledElements(createMapData(mapOf(way(1, tags = tagged) to line(a, b))))
                .singleOrNull()?.second
        }

        // DE -> FR -> GB -> DE
        assertEquals(trafficSignWayStyle(tagged), styleOfWayIn(de1, de2))
        assertEquals(trafficSignReadOnlyStyle, styleOfWayIn(fr1, fr2))
        assertEquals(trafficSignReadOnlyStyle, styleOfWayIn(gb1, gb2))
        assertEquals(trafficSignWayStyle(tagged), styleOfWayIn(de1, de2))

        assertSame(trafficSignOverlay, controller.selectedOverlay)
        assertEquals("TrafficSignOverlay", Prefs.preferences.selectedOverlayName)
    }

    @Test fun `without information about containing bounding boxes the points decide`() {
        // e.g. if the registry is created without it
        val registry = overlaysRegistry(
            getCountryInfoByLocation = { CountryInfo(null, listOf(IncompleteCountryInfo())) },
            getCountryCodeByLocation = TrafficSignTestCountries.getCountryCode,
            getFeature = { null },
            prefs = inMemoryPrefs(),
            hasTrafficSignCatalogue = { it == "DE" },
        )
        val o = registry.first { it is TrafficSignOverlay }
        val data = createMapData(mapOf(
            way(1, tags = mapOf(road)) to line(de1, de2),
            way(2, tags = mapOf(road, "traffic_sign" to "FR:B1")) to line(fr1, fr2),
            way(3, tags = mapOf(road)) to line(fr1, fr2),
        ))
        assertEquals(
            listOf(1L to trafficSignWayStyle(mapOf(road)), 2L to trafficSignReadOnlyStyle),
            o.getStyledElements(data).map { it.first.id to it.second }.toList()
        )
    }

    /* ----------------------------------- preview texts ----------------------------------- */

    @Test fun `tag keys are described in words`() {
        assertEquals(PreviewSubject.BICYCLE, previewSubjectOf("bicycle"))
        assertEquals(PreviewSubject.BICYCLE, previewSubjectOf("bicycle:backward"))
        assertEquals(PreviewSubject.MOTOR, previewSubjectOf("hgv:forward:conditional"))
        assertEquals(PreviewSubject.FOOT, previewSubjectOf("foot"))
        assertEquals(PreviewSubject.SEGREGATION, previewSubjectOf("segregated"))
        assertEquals(PreviewSubject.PART_DETAILS, previewSubjectOf("footway:surface"))
        assertEquals(PreviewSubject.SIGN_ENTRY, previewSubjectOf("traffic_sign:forward"))
        assertEquals(PreviewSubject.OTHER, previewSubjectOf("some_unknown_key"))
    }

    @Test fun `every reason has a text`() {
        // the texts are chosen with exhaustive when expressions; this makes sure the enums are not
        // empty and that a new reason would be noticed in review of this test
        assertEquals(9, TrafficSignTagConflict.Reason.entries.size)
        assertEquals(14, TrafficSignNotDerived.Reason.entries.size)
    }

    @Test fun `preview model of the example does not contain tags`() {
        val tags = mapOf("highway" to "path", "bicycle" to "designated", "foot" to "designated")
        val tagging = TrafficSignWaySigns(parseDE("DE:240"), parseDE("DE:239,1022-10")).deriveWayTags(tags)
        val model = tagging.toPreviewModel()
        assertEquals(TrafficSignDerivation.FULL, model.derivation)
        assertEquals("path" to "footway", model.highwayChange)
        assertTrue(model.adjusted.any { it.subject == PreviewSubject.BICYCLE && it.direction == TrafficSignDirection.BACKWARD })
        // the model holds subjects and reasons only, never tag keys or values
        assertTrue(model.adjusted.all { it.subject.name == it.subject.name.uppercase() })
    }

    @Test fun `unresolvable conflicts are reported separately`() {
        val tags = mapOf("highway" to "residential", "hgv" to "delivery")
        val tagging = TrafficSignWaySigns.both(parseDE("DE:250,1020-30")).deriveWayTags(tags)
        val model = tagging.toPreviewModel()
        assertTrue(model.needsCheck.any { it.reason == TrafficSignTagConflict.Reason.UNCLEAR_ACCESS && it.subject == PreviewSubject.MOTOR })
    }

    @Test fun `unusual combination is saved and explained`() {
        val tags = mapOf("highway" to "residential")
        val tagging = TrafficSignWaySigns.both(parseDE("DE:237;250;274[30]")).deriveWayTags(tags)
        assertEquals(TrafficSignDerivation.TRAFFIC_SIGN_ONLY, tagging.derivation)
        assertTrue(tagging.changes.isNotEmpty())
        assertTrue(tagging.toPreviewModel().notDerived.isNotEmpty())
    }

    @Test fun `catalogue is available for the tests`() {
        assertEquals("DE", TrafficSignTestData.catalogueDE.country)
    }
}
