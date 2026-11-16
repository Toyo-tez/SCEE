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
import de.westnordost.streetcomplete.osm.traffic_sign.isTrafficSignNodeEditable
import de.westnordost.streetcomplete.osm.traffic_sign.trafficSignNodeCountry
import de.westnordost.streetcomplete.osm.traffic_sign.trafficSignWayCountry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.resources.Res
import de.westnordost.streetcomplete.resources.quest_traffic_sign
import de.westnordost.streetcomplete.resources.traffic_sign_overlay_title
import de.westnordost.streetcomplete.util.ktx.isArea

/** Overlay to survey the traffic signs of ways per direction.
 *
 *  The editor exists for every country for which a traffic sign catalogue was imported
 *  ([hasCatalogue], see TrafficSignCatalogues); tags other than traffic_sign* are only derived for the
 *  countries with curated rules (WAY_RULES, so far only Germany). Which country a way is in is
 *  determined from the geometry of the way only, see [trafficSignWayCountry], never from the map
 *  center or the GPS position, so moving the map from one country to another needs no restart:
 *  - ways that lie completely in a country with a catalogue: shown with their survey state, editable
 *  - all other ways (no catalogue, crossing a border, uncertain): only shown if they already have
 *    traffic sign tags, in a neutral color, and only read-only. Ways without traffic sign tags are
 *    not shown there, so the overlay never says that anything is missing there.
 *  - nodes with traffic sign tags that are not only none/no, in any country: shown as a neutral
 *    point. Nodes without them are not shown, so nothing is said about completeness. Their
 *    traffic_sign=* can be edited if they lie certainly in a country with a catalogue (see
 *    [trafficSignNodeCountry]) and are not tagged per direction; otherwise they are read-only. */
class TrafficSignOverlay(
    /** returns the ISO 3166-1 or -2 code of the smallest region at the given position */
    private val getCountryCodeByLocation: (LatLon) -> String?,
    /** returns the ISO 3166-1 or -2 codes of the regions that are guaranteed to contain the whole
     *  given bounding box */
    private val getContainingCountryCodes: (BoundingBox) -> Set<String>,
    /** whether there is a traffic sign catalogue for the given ISO 3166-1 country code */
    private val hasCatalogue: (String) -> Boolean = { false },
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
                    isEditable(country, hasCatalogue) -> way to trafficSignWayStyle(way.tags)
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
                if (isEditable(country, hasCatalogue)) {
                    TrafficSignOverlayForm(on, element, geometry, (country as TrafficSignWayCountry.Within).country)
                } else {
                    TrafficSignReadOnlyWayOverlayForm(on, element, country, hasCatalogue)
                }
            }
            is Node -> {
                // decided by the position of the node and its surroundings, see trafficSignNodeCountry
                val position = (geometry as? ElementPointGeometry)?.center ?: element.position
                val country = remember(position) { trafficSignNodeCountry(position, getCountryCodeByLocation) }
                if (isEditable(country, hasCatalogue) && isTrafficSignNodeEditable(element.tags)) {
                    TrafficSignNodeOverlayForm(on, element, (country as TrafficSignWayCountry.Within).country)
                } else {
                    TrafficSignReadOnlyNodeOverlayForm(on, element, country, hasCatalogue)
                }
            }
            // only ways and nodes are shown, see getStyledElements
            else -> throw IllegalArgumentException("Unexpected element $element")
        }
    }

    private fun getWayCountry(geometry: ElementGeometry?): TrafficSignWayCountry =
        trafficSignWayCountry(geometry, getCountryCodeByLocation, getContainingCountryCodes)
}

/** Whether a way (or node) in the given [country] can be edited with the traffic sign editor: only if
 *  it lies completely in a country for which there is a catalogue */
internal fun isEditable(country: TrafficSignWayCountry, hasCatalogue: (String) -> Boolean): Boolean =
    country is TrafficSignWayCountry.Within && hasCatalogue(country.country)
