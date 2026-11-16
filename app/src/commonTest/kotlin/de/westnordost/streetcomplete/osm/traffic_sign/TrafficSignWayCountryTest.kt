package de.westnordost.streetcomplete.osm.traffic_sign

import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPolylinesGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
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
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignWayCountry.CrossesBorder
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignWayCountry.Uncertain
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignWayCountry.Within
import de.westnordost.streetcomplete.util.countryboundaries.CountryBoundariesImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrafficSignWayCountryTest {

    @BeforeTest fun setUp() {
        TrafficSignTestCountries.resetCounters()
    }

    private fun countryOf(geometry: ElementGeometry?) = trafficSignWayCountry(
        geometry,
        TrafficSignTestCountries.getCountryCode,
        TrafficSignTestCountries.getContainingCountryCodes,
    )

    @Test fun `way completely in one country`() {
        assertEquals(Within("DE"), countryOf(line(de1, de2)))
        assertEquals(Within("AT"), countryOf(line(at1, at2)))
        assertEquals(Within("FR"), countryOf(line(fr1, fr2)))
        assertEquals(Within("GB"), countryOf(line(gb1, gb2)))
    }

    @Test fun `subdivisions count as their country`() {
        // the lookups of the test world return "DE-BW" (positions) and "DE-BW", "DE" (bounding boxes)
        assertEquals("DE-BW", TrafficSignTestCountries.getCountryCode(de1))
        assertEquals(Within("DE"), countryOf(line(de1, de2)))
        // also if only the subdivision is known to contain the bounding box
        val onlySubdivision = trafficSignWayCountry(line(de1, de2), { "DE-BW" }, { setOf("DE-BW") })
        assertEquals(Within("DE"), onlySubdivision)
        // also via the vertices
        val viaSegments = trafficSignWayCountry(
            line(LatLon(50.0, 5.0), LatLon(50.0, 5.004), LatLon(50.0, 5.008)),
            { "DE-BY" },
            // only the segments are small enough to be known to be in Bavaria
            { b -> if (b.max.longitude - b.min.longitude < 0.005) setOf("DE-BY") else emptySet() }
        )
        assertEquals(Within("DE"), viaSegments)
    }

    @Test fun `a way in the middle of a country needs no lookup of its vertices`() {
        countryOf(line(de1, de2, LatLon(50.0, 5.02), LatLon(50.0, 5.03)))
        assertEquals(0, TrafficSignTestCountries.countryLookups)
        assertEquals(1, TrafficSignTestCountries.containingLookups)
    }

    @Test fun `way that crosses the border`() {
        assertEquals(CrossesBorder(setOf("DE", "AT")), countryOf(line(de1, at1)))
        assertEquals(CrossesBorder(setOf("DE", "AT")), countryOf(line(at1, de1)))
        assertEquals(CrossesBorder(setOf("DE", "AT")), countryOf(line(de1, de2, at1)))
        assertEquals(CrossesBorder(setOf("DE", "FR")), countryOf(line(de1, fr1)))
        assertEquals(CrossesBorder(setOf("DE", "AT", "FR")), countryOf(line(de1, at1, fr1)))
    }

    @Test fun `way that leaves and re-enters a country with a vertex outside`() {
        // first, middle and last point in Germany, the 2nd and 4th not: the former check accepted it
        val geometry = ElementPolylinesGeometry(listOf(listOf(de1, at1, de2, at2, de1)), de2)
        assertEquals(CrossesBorder(setOf("DE", "AT")), countryOf(geometry))
    }

    @Test fun `way that leaves and re-enters a country between two vertices`() {
        // both vertices are in Germany, but the line between them goes through the Austrian pocket
        val north = LatLon(50.52, 9.7)
        val south = LatLon(50.49, 9.7)
        assertEquals("DE-BW", TrafficSignTestCountries.getCountryCode(north))
        assertEquals("DE-BW", TrafficSignTestCountries.getCountryCode(south))
        assertEquals(CrossesBorder(setOf("DE", "AT")), countryOf(line(north, south)))
        // a way next to the pocket, with every segment far enough from it, is in Germany
        assertEquals(Within("DE"), countryOf(line(LatLon(50.3, 9.7), LatLon(50.3, 9.9), LatLon(50.3, 9.95))))
    }

    @Test fun `way that runs along the border on one side of it`() {
        // too close to the border for the guarantee of the bounding boxes, but every vertex and
        // every point in between is in Germany
        val along = line(LatLon(50.0, 9.9995), LatLon(50.01, 9.9996), LatLon(50.02, 9.9995))
        assertEquals(Within("DE"), countryOf(along))
        assertTrue(TrafficSignTestCountries.countryLookups > 3, "points along the segments are looked up")
    }

    @Test fun `way that runs along the border on both sides of it`() {
        val along = line(LatLon(50.0, 9.9995), LatLon(50.01, 10.0005), LatLon(50.02, 9.9995))
        assertEquals(CrossesBorder(setOf("DE", "AT")), countryOf(along))
    }

    @Test fun `way that is too long to check point by point`() {
        // just south of the pocket, so not guaranteed by bounding boxes, and ~320 km long
        val long = line(LatLon(50.4995, 5.0), LatLon(50.4995, 9.6))
        assertEquals(Uncertain("DE"), countryOf(long))
    }

    @Test fun `way that touches the border`() {
        // ends exactly on the border, which belongs to Austria in the test world
        assertEquals(CrossesBorder(setOf("DE", "AT")), countryOf(line(LatLon(50.0, 9.99), LatLon(50.0, 10.0))))
    }

    @Test fun `incomplete or unsuitable geometry`() {
        assertEquals(Uncertain(null), countryOf(null))
        assertEquals(Uncertain(null), countryOf(ElementPointGeometry(de1)))
        assertEquals(Uncertain(null), countryOf(ElementPolylinesGeometry(listOf(listOf(de1)), de1)))
        assertEquals(Uncertain(null), countryOf(ElementPolylinesGeometry(listOf(listOf(de1, de2), listOf(de1, de2)), de1)))
    }

    @Test fun `unknown country`() {
        assertEquals(Uncertain(null), countryOf(line(unknown1, unknown2)))
        // known for one vertex only
        assertEquals(Uncertain("DE"), countryOf(line(de1, unknown1)))
        assertEquals(Uncertain(null), trafficSignWayCountry(line(de1, de2), { null }, { emptySet() }))
    }

    @Test fun `without bounding box information the points decide`() {
        val result = trafficSignWayCountry(line(de1, de2), TrafficSignTestCountries.getCountryCode) { emptySet() }
        assertEquals(Within("DE"), result)
    }

    @Test fun `unknown country of a point between the vertices`() {
        val result = trafficSignWayCountry(line(de1, de2), { if (it == de1 || it == de2) "DE" else null }) { emptySet() }
        assertEquals(Uncertain("DE"), result)
    }

    @Test fun `overlapping regions are no proof of one country`() {
        val result = trafficSignWayCountry(line(de1, de2), { null }, { setOf("DE", "AT") })
        assertEquals(Uncertain(null), result)
    }

    /* ---------------------------------- real boundaries ---------------------------------- */

    @Test fun `with the real country boundaries`() {
        val bytes = TrafficSignTestData.readBytesOrNull("${TrafficSignTestData.RESOURCES_DIR}/files/boundaries.ser")
            ?: return // resource not available in this environment
        val boundaries = CountryBoundariesImpl(de.westnordost.countryboundaries.CountryBoundaries.deserializeFrom(
            kotlinx.io.Buffer().also { it.write(bytes) }
        ))
        fun countryOf(vararg points: LatLon) = trafficSignWayCountry(
            line(*points),
            { boundaries.getIds(it).firstOrNull() },
            { boundaries.getContainingIds(it) },
        )
        val munich1 = LatLon(48.137, 11.575)
        val munich2 = LatLon(48.139, 11.578)
        val paris1 = LatLon(48.8566, 2.3522)
        val paris2 = LatLon(48.8580, 2.3550)
        val london1 = LatLon(51.5074, -0.1278)
        val london2 = LatLon(51.5090, -0.1250)
        val salzburg = LatLon(47.800, 13.044)
        val strasbourg = LatLon(48.573, 7.752)

        assertEquals(Within("DE"), countryOf(munich1, munich2))
        assertEquals(Within("FR"), countryOf(paris1, paris2))
        // the bounding box of this way in London is not guaranteed to be in Great Britain by the
        // country boundaries (raster cell at the border of a region), so this is decided by its points
        assertEquals(Within("GB"), countryOf(london1, london2))
        assertEquals(CrossesBorder(setOf("DE", "AT")), countryOf(munich1, salzburg))
        assertEquals(CrossesBorder(setOf("DE", "FR")), countryOf(munich1, strasbourg))
        // Munich -> Salzburg -> Munich: first and last vertex in Germany
        assertEquals(CrossesBorder(setOf("DE", "AT")), countryOf(munich1, salzburg, munich2))
    }
}
