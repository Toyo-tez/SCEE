package de.westnordost.streetcomplete.osm.traffic_sign

import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPolylinesGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.util.math.distanceTo
import kotlin.math.ceil

/** In which country a way lies, as far as it can be told from the country boundaries of the app.
 *  Only the geometry of the way itself is used, never the map center or the GPS position. */
sealed interface TrafficSignWayCountry {
    /** The way lies in [country] according to the country boundaries of the app: either its whole
     *  bounding box is guaranteed to be within it, or each vertex and each point sampled along the
     *  segments at most [TRAFFIC_SIGN_SAMPLE_DISTANCE] meters apart is. (A border that juts into a
     *  segment by less than that could be missed; the country boundaries of the app are simplified
     *  anyway.) [country] is an ISO 3166-1 code ("DE"), never a subdivision. */
    data class Within(val country: String) : TrafficSignWayCountry

    /** Vertices or points along the segments of the way lie in different [countries] (at least two)
     *  according to the country boundaries of the app, i.e. the way crosses (or touches) a border. */
    data class CrossesBorder(val countries: Set<String>) : TrafficSignWayCountry

    /** It can not be told whether the way lies completely in one country: the geometry is missing or
     *  not a simple line, or the country of a vertex or of a point along a segment is unknown, or the
     *  way is too long to be checked. [country] is the only country known for the way, if any. */
    data class Uncertain(val country: String?) : TrafficSignWayCountry
}

/** Maximum distance in meters between the points along a segment whose country is looked up */
const val TRAFFIC_SIGN_SAMPLE_DISTANCE = 25.0

/** Ways with more points to look up than this are not checked (and thus [TrafficSignWayCountry.Uncertain]) */
private const val MAX_LOOKUPS = 2000

/** Determine in which country the way with the given [geometry] lies.
 *
 *  1. If the bounding box of the whole way is guaranteed to be contained in exactly one country, the
 *     way is [TrafficSignWayCountry.Within] that country. This is cheap, but the guarantee of the
 *     country boundaries only holds for (rather large) raster cells that are completely within a
 *     country, so it often fails near borders, coasts and lakes.
 *  2. Otherwise, the country of each vertex is looked up. Different countries -> crossing the border,
 *     an unknown country -> uncertain.
 *  3. If all vertices are in the same country, the segments are checked: a segment whose bounding
 *     box is guaranteed to be within the country is, otherwise points along it are looked up (at
 *     most [TRAFFIC_SIGN_SAMPLE_DISTANCE] apart). A border could otherwise run between two vertices
 *     (e.g. a way that leaves and re-enters the country).
 *
 *  All this refers to the (simplified) country boundaries that the app knows, no more.
 *
 *  @param getCountryCode the ISO 3166-1 or ISO 3166-2 code ("DE-BW") of the smallest region at the
 *         given position, null if unknown
 *  @param getContainingCountryCodes the codes (ISO 3166-1 or -2) of all regions that are guaranteed
 *         to contain the whole given bounding box
 */
fun trafficSignWayCountry(
    geometry: ElementGeometry?,
    getCountryCode: (LatLon) -> String?,
    getContainingCountryCodes: (BoundingBox) -> Set<String>,
): TrafficSignWayCountry {
    if (geometry !is ElementPolylinesGeometry) return TrafficSignWayCountry.Uncertain(null)
    val line = geometry.polylines.singleOrNull() ?: return TrafficSignWayCountry.Uncertain(null)
    if (line.size < 2) return TrafficSignWayCountry.Uncertain(null)

    // 1. fast path: the bounding box of the whole way is within one country
    val containingWhole = countriesOf(getContainingCountryCodes(boundsOf(line)))
    containingWhole.singleOrNull()?.let { return TrafficSignWayCountry.Within(it) }

    // 2. the country of each vertex
    val vertexCountries = line.map { getCountryCode(it)?.let(::countryOf) }
    val known = vertexCountries.filterNotNull().toSet()
    if (known.size > 1) return TrafficSignWayCountry.CrossesBorder(known)
    val country = known.singleOrNull()
    if (country == null || null in vertexCountries) return TrafficSignWayCountry.Uncertain(country)

    // 3. all vertices are in the same country: is every segment, too?
    val segmentsToSample = line.zipWithNext().filter { (a, b) ->
        country !in countriesOf(getContainingCountryCodes(boundsOf(listOf(a, b))))
    }
    val samples = segmentsToSample.map { (a, b) -> sampleCount(a, b) }
    if (samples.sum() > MAX_LOOKUPS) return TrafficSignWayCountry.Uncertain(country)

    val others = HashSet<String>()
    var unknown = false
    segmentsToSample.forEachIndexed { i, (a, b) ->
        val n = samples[i] + 1
        for (j in 1 until n) {
            val c = getCountryCode(interpolate(a, b, j.toDouble() / n))?.let(::countryOf)
            if (c == null) unknown = true else if (c != country) others.add(c)
        }
    }
    return when {
        others.isNotEmpty() -> TrafficSignWayCountry.CrossesBorder(others + country)
        unknown -> TrafficSignWayCountry.Uncertain(country)
        else -> TrafficSignWayCountry.Within(country)
    }
}

/** number of points between [a] and [b] to look up so that they are at most
 *  [TRAFFIC_SIGN_SAMPLE_DISTANCE] apart */
private fun sampleCount(a: LatLon, b: LatLon): Int =
    (ceil(a.distanceTo(b) / TRAFFIC_SIGN_SAMPLE_DISTANCE).toInt() - 1).coerceAtLeast(0)

/** linear interpolation, sufficiently exact for the short distances here */
private fun interpolate(a: LatLon, b: LatLon, f: Double) = LatLon(
    a.latitude + (b.latitude - a.latitude) * f,
    a.longitude + (b.longitude - a.longitude) * f,
)

/** "DE-BW" -> "DE", "DE" -> "DE" */
private fun countryOf(code: String): String = code.substringBefore('-')

private fun countriesOf(codes: Set<String>): Set<String> = codes.mapTo(HashSet(), ::countryOf)

private fun boundsOf(points: List<LatLon>): BoundingBox = BoundingBox(
    minLatitude = points.minOf { it.latitude },
    minLongitude = points.minOf { it.longitude },
    maxLatitude = points.maxOf { it.latitude },
    maxLongitude = points.maxOf { it.longitude },
)
