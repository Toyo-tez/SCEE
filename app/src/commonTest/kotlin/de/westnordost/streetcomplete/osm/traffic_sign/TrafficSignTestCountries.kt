package de.westnordost.streetcomplete.osm.traffic_sign

import de.westnordost.streetcomplete.data.osm.geometry.ElementPolylinesGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import kotlin.math.floor

/** A made-up world for the tests of the country of ways, by longitude:
 *  - lon < 10: Germany (Baden-Württemberg, so the lookup of a position returns "DE-BW"), except an
 *    Austrian pocket at lat 50.5..50.51, lon 9.5..9.9 (a border between two German vertices)
 *  - 10 <= lon < 20: Austria
 *  - 20 <= lon < 30: France
 *  - 30 <= lon < 40: Great Britain
 *  - lat > 60 or lon >= 40 or lon < 0: unknown
 *
 *  Like the real country boundaries, containment of a bounding box is only guaranteed with some
 *  distance ([MARGIN]) to a border, so ways that run very close to a border are not guaranteed to be
 *  within a country. */
object TrafficSignTestCountries {
    const val MARGIN = 0.001

    private val pocket = BoundingBox(50.5, 9.5, 50.51, 9.9)

    var countryLookups = 0
    var containingLookups = 0

    val getCountryCode: (LatLon) -> String? = { p ->
        countryLookups++
        when {
            p.latitude > 60.0 || p.longitude < 0.0 || p.longitude >= 40.0 -> null
            p.isIn(pocket) -> "AT"
            else -> idsOfBand(band(p.longitude)).firstOrNull()
        }
    }

    val getContainingCountryCodes: (BoundingBox) -> Set<String> = { b ->
        containingLookups++
        val west = band(b.min.longitude - MARGIN)
        val east = band(b.max.longitude + MARGIN)
        when {
            b.max.latitude > 60.0 - MARGIN -> emptySet()
            west != east -> emptySet()
            b.intersects(pocket, MARGIN) -> emptySet()
            else -> idsOfBand(west).toSet()
        }
    }

    fun resetCounters() {
        countryLookups = 0
        containingLookups = 0
    }

    // positions in the middle of each country
    val de1 = LatLon(50.0, 5.0)
    val de2 = LatLon(50.0, 5.01)
    val at1 = LatLon(50.0, 15.0)
    val at2 = LatLon(50.0, 15.01)
    val fr1 = LatLon(48.8, 25.0)
    val fr2 = LatLon(48.8, 25.01)
    val gb1 = LatLon(51.5, 35.0)
    val gb2 = LatLon(51.5, 35.01)
    val unknown1 = LatLon(70.0, 5.0)
    val unknown2 = LatLon(70.0, 5.01)

    fun line(vararg points: LatLon) =
        ElementPolylinesGeometry(listOf(points.toList()), points[points.size / 2])

    private fun band(lon: Double): Int = floor(lon / 10.0).toInt()

    private fun idsOfBand(band: Int): List<String> = when (band) {
        0 -> listOf("DE-BW", "DE")
        1 -> listOf("AT")
        2 -> listOf("FR")
        3 -> listOf("GB")
        else -> emptyList()
    }

    private fun LatLon.isIn(b: BoundingBox) =
        latitude in b.min.latitude..b.max.latitude && longitude in b.min.longitude..b.max.longitude

    private fun BoundingBox.intersects(other: BoundingBox, margin: Double) =
        min.latitude <= other.max.latitude + margin && max.latitude >= other.min.latitude - margin &&
        min.longitude <= other.max.longitude + margin && max.longitude >= other.min.longitude - margin
}
