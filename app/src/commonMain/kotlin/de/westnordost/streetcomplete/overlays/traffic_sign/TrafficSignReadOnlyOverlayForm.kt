package de.westnordost.streetcomplete.overlays.traffic_sign

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.westnordost.streetcomplete.data.osm.mapdata.Node
import de.westnordost.streetcomplete.data.osm.mapdata.Way
import de.westnordost.streetcomplete.data.overlays.Action
import de.westnordost.streetcomplete.data.overlays.OverlayAction
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignWayCountry
import de.westnordost.streetcomplete.osm.traffic_sign.isTrafficSignNodeEditable
import de.westnordost.streetcomplete.resources.*
import de.westnordost.streetcomplete.ui.common.overlay.OverlayForm
import de.westnordost.streetcomplete.util.ktx.isSplittable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/* Forms for elements that can not be edited with the traffic sign editor (see TrafficSignOverlay):
 * they show why, and the traffic sign tags of the element as they are, without interpreting them.
 *
 * They never save anything by themselves: there is no OK button. The tag editor and splitting the
 * way are only opened when the user explicitly taps the respective button. */

/** Read-only form for a way that is not completely in a country with a traffic sign catalogue */
@Composable
fun TrafficSignReadOnlyWayOverlayForm(
    on: (OverlayAction) -> Unit,
    way: Way,
    country: TrafficSignWayCountry,
    hasCatalogue: (String) -> Boolean = { false },
) {
    OverlayForm(on = on, isComplete = false, hasChanges = false, onClickOk = {}) {
        TrafficSignReadOnlyContent(
            explanation = readOnlyExplanation(country, hasCatalogue),
            tags = readOnlyTrafficSignTags(way.tags),
            onEditTags = { on(Action.EditTags) },
            // splitting only makes sense if the way is known to lie in several countries
            onSplitWay = if (country is TrafficSignWayCountry.CrossesBorder && way.isSplittable()) {
                { on(Action.SplitWay) }
            } else {
                null
            },
        )
    }
}

/** Read-only form for a node with traffic sign tags that can not be edited: it is tagged per
 *  direction, or it does not lie certainly in a country with a catalogue ([country]) */
@Composable
fun TrafficSignReadOnlyNodeOverlayForm(
    on: (OverlayAction) -> Unit,
    node: Node,
    country: TrafficSignWayCountry = TrafficSignWayCountry.Uncertain(null),
    hasCatalogue: (String) -> Boolean = { false },
) {
    OverlayForm(on = on, isComplete = false, hasChanges = false, onClickOk = {}) {
        TrafficSignReadOnlyContent(
            explanation = stringResource(readOnlyNodeExplanation(node.tags, country, hasCatalogue)),
            tags = readOnlyTrafficSignTags(node.tags, TRAFFIC_SIGN_NODE_KEYS),
            onEditTags = { on(Action.EditTags) },
            onSplitWay = null,
        )
    }
}

/** Content of the read-only forms. [tags]: the tags to show as they are. [onSplitWay] null: no
 *  button to split the way */
@Composable
internal fun TrafficSignReadOnlyContent(
    explanation: String,
    tags: List<Pair<String, String>>,
    onEditTags: () -> Unit,
    onSplitWay: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(explanation)

        Text(
            stringResource(Res.string.traffic_sign_overlay_readonly_tags_title),
            style = MaterialTheme.typography.subtitle1,
        )
        if (tags.isEmpty()) {
            Text(stringResource(Res.string.traffic_sign_overlay_readonly_no_tags))
        }
        for ((key, value) in tags) {
            Column {
                // the raw tag as it is, no interpretation of the value
                Text("$key=$value", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.body2)
                Text(
                    stringResource(directionLabelOf(key)),
                    style = MaterialTheme.typography.caption,
                )
            }
        }

        OutlinedButton(onClick = onEditTags, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.quest_generic_answer_show_edit_tags))
        }
        if (onSplitWay != null) {
            OutlinedButton(onClick = onSplitWay, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.split_way))
            }
        }
    }
}

@Composable
private fun readOnlyExplanation(country: TrafficSignWayCountry, hasCatalogue: (String) -> Boolean): String = when (country) {
    is TrafficSignWayCountry.CrossesBorder -> stringResource(
        Res.string.traffic_sign_overlay_readonly_border,
        country.countries.sorted().joinToString(", ")
    )
    is TrafficSignWayCountry.Within -> stringResource(Res.string.traffic_sign_overlay_readonly_country)
    is TrafficSignWayCountry.Uncertain ->
        if (country.country != null && !hasCatalogue(country.country)) {
            stringResource(Res.string.traffic_sign_overlay_readonly_country)
        } else {
            stringResource(Res.string.traffic_sign_overlay_readonly_uncertain)
        }
}

/** Why the signs of a node can not be edited */
internal fun readOnlyNodeExplanation(
    tags: Map<String, String>,
    country: TrafficSignWayCountry,
    hasCatalogue: (String) -> Boolean,
): StringResource = when {
    !isTrafficSignNodeEditable(tags) -> Res.string.traffic_sign_overlay_readonly_node
    country is TrafficSignWayCountry.Within && !hasCatalogue(country.country) -> Res.string.traffic_sign_overlay_readonly_node_country
    country is TrafficSignWayCountry.Uncertain && country.country != null && !hasCatalogue(country.country) -> Res.string.traffic_sign_overlay_readonly_node_country
    else -> Res.string.traffic_sign_overlay_readonly_node_uncertain
}

/** Why no new node with traffic signs can be created at a position in [country] */
internal fun newNodeUnavailableExplanation(
    country: TrafficSignWayCountry,
    hasCatalogue: (String) -> Boolean,
): StringResource = when {
    country is TrafficSignWayCountry.Within && !hasCatalogue(country.country) -> Res.string.traffic_sign_overlay_create_node_country
    country is TrafficSignWayCountry.Uncertain && country.country != null && !hasCatalogue(country.country) -> Res.string.traffic_sign_overlay_create_node_country
    else -> Res.string.traffic_sign_overlay_create_node_uncertain
}

/** The tags of [keys] that have a value, in the order of [keys], with their values as they are */
internal fun readOnlyTrafficSignTags(
    tags: Map<String, String>,
    keys: List<String> = TRAFFIC_SIGN_KEYS,
): List<Pair<String, String>> =
    keys.mapNotNull { key -> tags[key]?.takeIf { it.isNotBlank() }?.let { key to it } }

/** What the key itself says about the direction (not the value: it is not interpreted) */
private fun directionLabelOf(key: String): StringResource = when (key) {
    "traffic_sign:forward" -> Res.string.traffic_sign_overlay_direction_forward
    "traffic_sign:backward" -> Res.string.traffic_sign_overlay_direction_backward
    "traffic_sign:direction", "direction" -> Res.string.traffic_sign_overlay_readonly_direction_tag
    else -> Res.string.traffic_sign_overlay_readonly_no_direction
}
