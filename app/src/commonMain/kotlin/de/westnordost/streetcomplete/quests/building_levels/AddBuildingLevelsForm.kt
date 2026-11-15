package de.westnordost.streetcomplete.quests.building_levels

import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.westnordost.streetcomplete.data.meta.CountryInfo
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.osmquests.Answer
import de.westnordost.streetcomplete.data.osm.osmquests.QuestAction
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.data.preferences.addLastPicked
import de.westnordost.streetcomplete.data.preferences.getLastPicked
import de.westnordost.streetcomplete.osm.isUnrepresentableBuildingLevels
import de.westnordost.streetcomplete.osm.parseRepresentableBuildingLevels
import de.westnordost.streetcomplete.quests.questPrefix
import de.westnordost.streetcomplete.resources.*
import de.westnordost.streetcomplete.ui.common.dialogs.InfoDialog
import de.westnordost.streetcomplete.ui.common.quest.AnswerItem
import de.westnordost.streetcomplete.ui.common.quest.QuestForm
import de.westnordost.streetcomplete.util.takeFavorites
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun AddBuildingLevelsForm(
    on: (QuestAction<BuildingLevels>) -> Unit,
    element: Element,
    countryInfo: CountryInfo,
    preferences: Preferences = koinInject(),
) {
    val key = "AddBuildingLevelsForm"
    val lastPicked = remember {
        preferences.getLastPicked<BuildingLevels>(key)
            .takeFavorites(n = 5, history = 15, first = 1)
            .sortedWith(compareBy<BuildingLevels>({ it.levels ?: Int.MAX_VALUE }, { it.roofLevels }))
            .filter { it.levels != null }
    }

    val originalBuildingLevelsRaw = remember(element) { element.tags["building:levels"] }
    val originalRoofLevelsRaw = remember(element) { element.tags["roof:levels"] }
    val originalLevels = remember(element) { originalBuildingLevelsRaw.parseRepresentableBuildingLevels() }
    val originalRoofLevels = remember(element) { originalRoofLevelsRaw.parseRepresentableBuildingLevels() }
    val unparsedBuildingLevels = remember(element) { originalBuildingLevelsRaw.isUnrepresentableBuildingLevels() }
    val unparsedRoofLevels = remember(element) { originalRoofLevelsRaw.isUnrepresentableBuildingLevels() }

    var levels by rememberSaveable(originalLevels) { mutableStateOf(originalLevels) }
    var roofLevels by rememberSaveable(originalRoofLevels) { mutableStateOf(originalRoofLevels) }

    var showMultipleLevelsHint by remember { mutableStateOf(false) }

    val roofLevelsAreOptional = remember(element) {
        if (!preferences.getBoolean(questPrefix(preferences) + MANDATORY_ROOF_LEVELS, true)) return@remember true
        val roofShape = element.tags["roof:shape"]
        val hasNonFlatRoofShape = roofShape != null && roofShape != "flat"
        countryInfo.roofsAreUsuallyFlat && !hasNonFlatRoofShape
    }

    val isBuildingPart = element.tags.containsKey("building:part")

    val needsBuildingLevelsInput = originalLevels == null && !unparsedBuildingLevels
    val buildingInputSatisfied = levels != null || !needsBuildingLevelsInput
    val roofInputSatisfied = roofLevelsAreOptional || roofLevels != null

    val writesBuildingLevels = levels != null && (unparsedBuildingLevels || levels != originalLevels)
    val writesRoofLevels = roofLevels != null && (unparsedRoofLevels || roofLevels != originalRoofLevels)

    QuestForm(
        on = on,
        isComplete = buildingInputSatisfied && roofInputSatisfied,
        hasChanges = writesBuildingLevels || writesRoofLevels,
        onClickOk = {
            val answer = BuildingLevels(
                levels = if (writesBuildingLevels) levels else null,
                roofLevels = if (writesRoofLevels) roofLevels else null,
            )
            if (answer.levels != null) {
                preferences.addLastPicked(key, answer.copy(roofLevels = answer.roofLevels ?: roofLevels))
            }
            on(Answer(answer))
        },
        title = stringResource(
            if (isBuildingPart) Res.string.quest_buildingLevels_title_buildingPart2
            else Res.string.quest_buildingLevels_title2
        ),
        otherAnswers = { listOf(
            AnswerItem(stringResource(Res.string.quest_buildingLevels_answer_multipleLevels)) {
                showMultipleLevelsHint = true
            }
        ) },
    ) {
        if (unparsedBuildingLevels) {
            Text(
                stringResource(
                    Res.string.quest_buildingLevels_unparsed_levels,
                    originalBuildingLevelsRaw!!
                ),
                Modifier.padding(bottom = 8.dp),
            )
        }
        if (unparsedRoofLevels) {
            Text(
                stringResource(
                    Res.string.quest_buildingLevels_unparsed_roof_levels,
                    originalRoofLevelsRaw!!
                ),
                Modifier.padding(bottom = 8.dp),
            )
        }
        BuildingLevelsForm(
            levels = levels,
            onLevelsChange = { levels = it },
            roofLevels = roofLevels,
            onRoofLevelsChange = { roofLevels = it },
            previousBuildingLevels = lastPicked
        )
    }

    if (showMultipleLevelsHint) {
        InfoDialog(
            onDismissRequest = { showMultipleLevelsHint = false },
            text = { Text(stringResource(Res.string.quest_buildingLevels_answer_description)) }
        )
    }
}