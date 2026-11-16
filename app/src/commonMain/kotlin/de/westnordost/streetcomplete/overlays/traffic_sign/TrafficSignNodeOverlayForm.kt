package de.westnordost.streetcomplete.overlays.traffic_sign

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.westnordost.streetcomplete.data.osm.edits.create.CreateNodeAction
import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapChangesBuilder
import de.westnordost.streetcomplete.data.osm.edits.update_tags.updateElementTagsActionOrNull
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.osm.mapdata.Node
import de.westnordost.streetcomplete.data.overlays.Edit
import de.westnordost.streetcomplete.data.overlays.OverlayAction
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.data.traffic_signs.TrafficSignCatalogues
import de.westnordost.streetcomplete.osm.traffic_sign.DirectionSelection
import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignCatalogue
import de.westnordost.streetcomplete.osm.traffic_sign.isUsableWith
import de.westnordost.streetcomplete.osm.traffic_sign.parseTrafficSignNodeValue
import de.westnordost.streetcomplete.osm.traffic_sign.trafficSignNodeChange
import de.westnordost.streetcomplete.resources.*
import de.westnordost.streetcomplete.ui.common.dialogs.AlertDialog
import de.westnordost.streetcomplete.ui.common.last_picked.LastPickedChipsRow
import de.westnordost.streetcomplete.ui.common.overlay.OverlayForm
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** The editor of the traffic signs of a node with the catalogue of [country]: the same picker as for
 *  ways, but only traffic_sign=* of the node is written. Nothing is derived from the signs, nothing is
 *  transferred to a way, the direction of the sign (direction=*, traffic_sign:direction=*) and all
 *  other tags are kept. Only to be shown for nodes that lie certainly in [country] and are not tagged
 *  per direction, see TrafficSignOverlay. */
@Composable
fun TrafficSignNodeOverlayForm(
    on: (OverlayAction) -> Unit,
    node: Node,
    country: String,
    catalogues: TrafficSignCatalogues = koinInject(),
) {
    when (val s = rememberTrafficSignCatalogueState(catalogues, country)) {
        TrafficSignCatalogueState.Loading -> OverlayForm(on = on, isComplete = false, hasChanges = false, onClickOk = {}) {
            Text(stringResource(Res.string.traffic_sign_overlay_loading))
        }
        TrafficSignCatalogueState.Failed -> OverlayForm(on = on, isComplete = false, hasChanges = false, onClickOk = {}) {
            Text(stringResource(Res.string.traffic_sign_overlay_catalogue_missing))
        }
        is TrafficSignCatalogueState.Ready -> TrafficSignNodeOverlayFormContent(on, node, s.catalogue)
    }
}

@Composable
private fun TrafficSignNodeOverlayFormContent(
    on: (OverlayAction) -> Unit,
    node: Node,
    catalogue: TrafficSignCatalogue,
) {
    val original = remember(node, catalogue) { DirectionSelection.of(parseTrafficSignNodeValue(node.tags, catalogue)) }
    // saved as text (the traffic_sign value), "" = unchanged
    var encoded by rememberSaveable(node.id, catalogue.country) { mutableStateOf(original.encode()) }
    val selection = remember(encoded, catalogue) { DirectionSelection.decode(encoded, catalogue) }
    val change = remember(selection, node.tags) { trafficSignNodeChange(node.tags, original, selection) }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }

    fun save() {
        val c = change ?: return
        val changes = StringMapChangesBuilder(node.tags)
        changes[c.key] = c.value
        updateElementTagsActionOrNull(node, changes.create())?.let { on(Edit(it)) }
    }

    OverlayForm(
        on = on,
        isComplete = change != null,
        hasChanges = change != null,
        // an existing value is only replaced after a confirmation
        onClickOk = { if (change?.previousValue != null) confirmReplace = true else save() },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SignsEditor(
                catalogue = catalogue,
                selection = selection,
                hasExistingValue = original != DirectionSelection.NotSurveyed,
                // removing all signs means: leave the existing value alone
                onChange = { encoded = if (it == DirectionSelection.None) "" else it.encode() },
            )
            Text(stringResource(Res.string.traffic_sign_overlay_node_hint), style = MaterialTheme.typography.caption)
            change?.let {
                Text("${it.key}=${it.value}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.body2)
            }
        }
    }

    val c = change
    if (confirmReplace && c?.previousValue != null) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(Res.string.traffic_sign_confirm_title)) },
            text = { Text(stringResource(Res.string.traffic_sign_overlay_node_confirm_replace, c.previousValue, c.value)) },
            buttonRow = {
                TextButton(onClick = { confirmReplace = false }) { Text(stringResource(Res.string.cancel)) }
                TextButton(onClick = { confirmReplace = false; save() }) { Text(stringResource(Res.string.traffic_sign_confirm_yes)) }
            },
        )
    }
}

/** The editor for a new node with traffic signs at [position] (the crosshair, like in the things
 *  overlay) with the catalogue of [country]: the same picker as for existing nodes. On OK, a new node
 *  with only traffic_sign=* is created, nothing else is derived or added. On cancel, nothing is
 *  created. Only to be shown if [position] lies certainly in [country], see TrafficSignOverlay. */
@Composable
fun TrafficSignNewNodeOverlayForm(
    on: (OverlayAction) -> Unit,
    position: LatLon,
    country: String,
    catalogues: TrafficSignCatalogues = koinInject(),
    preferences: Preferences = koinInject(),
) {
    when (val s = rememberTrafficSignCatalogueState(catalogues, country)) {
        TrafficSignCatalogueState.Loading -> OverlayForm(on = on, isComplete = false, hasChanges = false, onClickOk = {}) {
            Text(stringResource(Res.string.traffic_sign_overlay_loading))
        }
        TrafficSignCatalogueState.Failed -> OverlayForm(on = on, isComplete = false, hasChanges = false, onClickOk = {}) {
            Text(stringResource(Res.string.traffic_sign_overlay_catalogue_missing))
        }
        is TrafficSignCatalogueState.Ready -> TrafficSignNewNodeOverlayFormContent(on, position, s.catalogue, preferences)
    }
}

@Composable
private fun TrafficSignNewNodeOverlayFormContent(
    on: (OverlayAction) -> Unit,
    position: LatLon,
    catalogue: TrafficSignCatalogue,
    preferences: Preferences,
) {
    // saved as text (the traffic_sign value), "" = nothing picked yet. Not reset when the map (and
    // with it the crosshair, [position]) is moved within the same country
    var encoded by rememberSaveable(catalogue.country) { mutableStateOf("") }
    val selection = remember(encoded, catalogue) { DirectionSelection.decode(encoded, catalogue) }
    // the same rules as for an existing node without any tags: only real signs, written as read
    val change = remember(selection) { trafficSignNodeChange(emptyMap(), DirectionSelection.NotSurveyed, selection) }
    // only signs of this country that are the same in both directions make sense for one node
    val lastPicked = remember(catalogue) {
        preferences.lastPickedTrafficSigns().filter {
            it.isSameInBothDirections && it.isUsableWith(catalogue) &&
                DirectionSelection.decode(it.forward, catalogue) is DirectionSelection.Signed
        }
    }

    OverlayForm(
        on = on,
        isComplete = change != null,
        hasChanges = change != null,
        onClickOk = {
            val c = change
            if (c != null) {
                preferences.rememberPickedTrafficSign(encoded)
                on(Edit(CreateNodeAction(position, mapOf(c.key to c.value))))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lastPicked.isNotEmpty()) {
                LastPickedChipsRow(items = lastPicked, onClick = { encoded = it.forward }) { CombinationChip(it, catalogue) }
            }
            SignsEditor(
                catalogue = catalogue,
                selection = selection,
                hasExistingValue = false,
                onChange = { encoded = if (it is DirectionSelection.Signed) it.encode() else "" },
            )
            Text(stringResource(Res.string.traffic_sign_overlay_create_node_hint), style = MaterialTheme.typography.caption)
            change?.let {
                Text("${it.key}=${it.value}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.body2)
            }
        }
    }
}

/** Shown instead of the editor for a new node if the country at the crosshair has no catalogue or is
 *  uncertain (e.g. near a border): nothing can be created. */
@Composable
fun TrafficSignNewNodeUnavailableForm(on: (OverlayAction) -> Unit, explanation: StringResource) {
    OverlayForm(on = on, isComplete = false, hasChanges = false, onClickOk = {}) {
        Text(stringResource(explanation))
    }
}
