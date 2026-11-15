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
import de.westnordost.streetcomplete.data.osm.mapdata.Way
import de.westnordost.streetcomplete.data.overlays.Action
import de.westnordost.streetcomplete.data.overlays.OverlayAction
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignWayCountry
import de.westnordost.streetcomplete.resources.*
import de.westnordost.streetcomplete.ui.common.overlay.OverlayForm
import de.westnordost.streetcomplete.util.ktx.isSplittable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Form for ways that can not be edited with the traffic sign editor (see TrafficSignOverlay):
 *  shows why, and the traffic sign tags of the way as they are, without interpreting them.
 *
 *  It never saves anything by itself: there is no OK button. The tag editor and splitting the way
 *  are only opened when the user explicitly taps the respective button. */
@Composable
fun TrafficSignReadOnlyOverlayForm(
    on: (OverlayAction) -> Unit,
    way: Way,
    country: TrafficSignWayCountry,
) {
    OverlayForm(on = on, isComplete = false, hasChanges = false, onClickOk = {}) {
        TrafficSignReadOnlyContent(
            tags = way.tags,
            country = country,
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

/** Content of [TrafficSignReadOnlyOverlayForm]. [onSplitWay] null: no button to split the way */
@Composable
internal fun TrafficSignReadOnlyContent(
    tags: Map<String, String>,
    country: TrafficSignWayCountry,
    onEditTags: () -> Unit,
    onSplitWay: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(readOnlyExplanation(country))

        Text(
            stringResource(Res.string.traffic_sign_overlay_readonly_tags_title),
            style = MaterialTheme.typography.subtitle1,
        )
        val present = readOnlyTrafficSignTags(tags)
        if (present.isEmpty()) {
            Text(stringResource(Res.string.traffic_sign_overlay_readonly_no_tags))
        }
        for ((key, value) in present) {
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
private fun readOnlyExplanation(country: TrafficSignWayCountry): String = when (country) {
    is TrafficSignWayCountry.CrossesBorder -> stringResource(
        Res.string.traffic_sign_overlay_readonly_border,
        country.countries.sorted().joinToString(", ")
    )
    is TrafficSignWayCountry.Within -> stringResource(Res.string.traffic_sign_overlay_readonly_country)
    is TrafficSignWayCountry.Uncertain ->
        if (country.country != null && country.country != TRAFFIC_SIGN_EDITOR_COUNTRY) {
            stringResource(Res.string.traffic_sign_overlay_readonly_country)
        } else {
            stringResource(Res.string.traffic_sign_overlay_readonly_uncertain)
        }
}

/** The traffic sign tags of [TRAFFIC_SIGN_KEYS] that have a value, with their values as they are */
internal fun readOnlyTrafficSignTags(tags: Map<String, String>): List<Pair<String, String>> =
    TRAFFIC_SIGN_KEYS.mapNotNull { key -> tags[key]?.takeIf { it.isNotBlank() }?.let { key to it } }

/** What the key itself says about the direction (not the value: it is not interpreted) */
private fun directionLabelOf(key: String): StringResource = when (key) {
    "traffic_sign:forward" -> Res.string.traffic_sign_overlay_direction_forward
    "traffic_sign:backward" -> Res.string.traffic_sign_overlay_direction_backward
    else -> Res.string.traffic_sign_overlay_readonly_no_direction
}
