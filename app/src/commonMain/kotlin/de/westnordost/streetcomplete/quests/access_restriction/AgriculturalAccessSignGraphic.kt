package de.westnordost.streetcomplete.quests.access_restriction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.westnordost.streetcomplete.data.traffic_signs.TrafficSignCatalogues
import de.westnordost.streetcomplete.osm.traffic_sign.parseTrafficSignValue
import de.westnordost.streetcomplete.overlays.traffic_sign.TrafficSignCatalogueState
import de.westnordost.streetcomplete.overlays.traffic_sign.rememberTrafficSignCatalogueState
import de.westnordost.streetcomplete.resources.*
import de.westnordost.streetcomplete.ui.common.traffic_sign.TrafficSignValueImage
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private const val COUNTRY = "DE"

/** Visual German sign combination for one answer, plus a short caption. */
@Composable
fun AgriculturalAccessSignGraphic(
    sign: AgriculturalAccessSign,
    accessValue: String,
    modifier: Modifier = Modifier,
    signHeight: Dp = 56.dp,
    catalogues: TrafficSignCatalogues = koinInject(),
) {
    val tagValue = sign.trafficSignTagValue(accessValue)
    val catalogueState = rememberTrafficSignCatalogueState(catalogues, COUNTRY)
    Column(
        modifier = modifier.padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            tagValue == null -> Unit
            catalogueState is TrafficSignCatalogueState.Ready -> {
                val parsed = parseTrafficSignValue(tagValue, catalogueState.catalogue)
                TrafficSignValueImage(
                    value = parsed,
                    signHeight = signHeight,
                )
            }
            catalogueState is TrafficSignCatalogueState.Failed -> {
                Text(tagValue, style = MaterialTheme.typography.caption, textAlign = TextAlign.Center)
            }
            // Loading: avoid layout jump; catalogue loads quickly from bundled resources.
        }
        Text(
            text = stringResource(sign.title),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.caption,
            modifier = Modifier.padding(2.dp),
        )
    }
}