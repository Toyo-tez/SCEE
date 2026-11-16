package de.westnordost.streetcomplete.overlays.traffic_sign

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import de.westnordost.streetcomplete.data.meta.CountryInfo
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataWithGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.Node
import de.westnordost.streetcomplete.data.osm.mapdata.Way
import de.westnordost.streetcomplete.data.osm.mapdata.filter
import de.westnordost.streetcomplete.data.overlays.Overlay
import de.westnordost.streetcomplete.data.overlays.OverlayAction
import de.westnordost.streetcomplete.data.overlays.OverlayStyle
import de.westnordost.streetcomplete.osm.ALL_PATHS
import de.westnordost.streetcomplete.osm.ALL_ROADS
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignWayCountry
import de.westnordost.streetcomplete.osm.traffic_sign.trafficSignWayCountry
import de.westnordost.streetcomplete.resources.Res
import de.westnordost.streetcomplete.resources.quest_traffic_sign
import de.westnordost.streetcomplete.resources.traffic_sign_overlay_title
import de.westnordost.streetcomplete.util.ktx.isArea

/** Overlay to survey the traffic signs of ways per direction.
 *
 *  The editor (with the catalogue and the rules to derive tags) only exists for Germany. Which
 *  country a way is in is determined from the geometry of the way only, see
 *  [trafficSignWayCountry]:
 *  - ways that lie completely in Germany: shown with their survey state, editable
 *  - all other ways (other countries, crossing a border, uncertain): only shown if they already
 *    have traffic sign tags, in a neutral color, and only read-only. Ways without traffic sign tags
 *    are not shown there, so the overlay never says that anything is missing there.
 *  - nodes with traffic sign tags that are not only none/no, in any country (also Germany): shown read-only as a neutral
 *    point. Nodes without them are not shown, so nothing is said about completeness. */
class TrafficSignOverlay(
    /** returns the ISO 3166-1 or -2 code of the smallest region at the given position */
    private val getCountryCodeByLocation: (LatLon) -> String?,
    /** returns the ISO 3166-1 or -2 codes of the regions that are guaranteed to contain the whole
     *  given bounding box */
    private val getContainingCountryCodes: (BoundingBox) -> Set<String>,
) : Overlay {

    override val title = Res.string.traffic_sign_overlay_title
    override val icon = Res.drawable.quest_traffic_sign
    override val changesetComment = "Survey traffic signs"
    override val wikiLink: String = "Key:traffic_sign"

    override fun getStyledElements(mapData: MapDataWithGeometry): Sequence<Pair<Element, OverlayStyle>> =
        getStyledWays(mapData) + getStyledNodes(mapData)

    private fun getStyledNodes(mapData: MapDataWithGeometry): Sequence<Pair<Element, OverlayStyle>> =
        mapData.nodes.asSequence()
            .filter { hasTrafficSignOnNode(it.tags) }
            .map { it to trafficSignNodeStyle }

    private fun getStyledWays(mapData: MapDataWithGeometry): Sequence<Pair<Element, OverlayStyle>> =
        mapData.filter("ways with highway ~ ${(ALL_ROADS + ALL_PATHS).joinToString("|")}")
            .filter { it is Way && !it.isArea() && it.tags["area"] != "yes" }
            .mapNotNull { way ->
                val hasTags = hasTrafficSignTags(way.tags)
                val country = getWayCountry(mapData.getWayGeometry(way.id))
                when {
                    isEditable(country) -> way to trafficSignWayStyle(way.tags)
                    hasTags -> way to trafficSignReadOnlyStyle
                    else -> null
                }
            }

    @Composable
    override fun Form(
        on: (OverlayAction) -> Unit,
        element: Element?,
        geometry: ElementGeometry,
        countryInfo: CountryInfo,
    ) {
        when (element) {
            is Way -> {
                // decided by the geometry of the way, not by countryInfo (which is for its center only)
                val country = remember(geometry) { getWayCountry(geometry) }
                if (isEditable(country)) {
                    TrafficSignOverlayForm(on, element, geometry)
                } else {
                    TrafficSignReadOnlyWayOverlayForm(on, element, country)
                }
            }
            is Node -> TrafficSignReadOnlyNodeOverlayForm(on, element)
            // only ways and nodes are shown, see getStyledElements
            else -> throw IllegalArgumentException("Unexpected element $element")
        }
    }

    private fun getWayCountry(geometry: ElementGeometry?): TrafficSignWayCountry =
        trafficSignWayCountry(geometry, getCountryCodeByLocation, getContainingCountryCodes)
}

/** Whether a way in the given [country] can be edited with the traffic sign editor: only if it lies
 *  completely in a country for which the editor exists */
internal fun isEditable(country: TrafficSignWayCountry): Boolean =
    country is TrafficSignWayCountry.Within && country.country == TRAFFIC_SIGN_EDITOR_COUNTRY
