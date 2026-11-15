package de.westnordost.streetcomplete.quests.opening_hours

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.westnordost.osm_opening_hours.model.Month
import de.westnordost.osm_opening_hours.model.MonthRange
import de.westnordost.streetcomplete.data.meta.CountryInfo
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.osmquests.Answer
import de.westnordost.streetcomplete.data.osm.osmquests.QuestAction
import de.westnordost.streetcomplete.osm.opening_hours.HierarchicOpeningHours
import de.westnordost.streetcomplete.osm.opening_hours.isValidButUnrepresentableOpeningHours
import de.westnordost.streetcomplete.osm.opening_hours.toRepresentableHierarchicOpeningHours
import de.westnordost.streetcomplete.resources.*
import de.westnordost.streetcomplete.ui.common.dialogs.AreYouSureDialog
import de.westnordost.streetcomplete.ui.common.opening_hours.OpeningHoursCommentDialog
import de.westnordost.streetcomplete.ui.common.opening_hours.OpeningHoursTable
import de.westnordost.streetcomplete.ui.common.opening_hours.TimeMode
import de.westnordost.streetcomplete.ui.common.quest.AnswerItem
import de.westnordost.streetcomplete.ui.common.quest.QuestForm
import de.westnordost.streetcomplete.ui.util.ReplaceBottomSheetTransitionSpec
import de.westnordost.streetcomplete.ui.util.rememberSerializable
import org.jetbrains.compose.resources.stringResource

private enum class OpeningHoursFormScreen {
  ConfirmRepresentable,
  ConfirmUnrepresentable,
  Edit,
}

@Composable
fun AddOpeningHoursForm(
    on: (QuestAction<OpeningHoursAnswer>) -> Unit,
    element: Element,
    countryInfo: CountryInfo,
) {
    val originalOpeningHoursString = remember(element) { element.tags["opening_hours"] }
    val originalOpeningHours = remember(element) {
        originalOpeningHoursString?.toRepresentableHierarchicOpeningHours()
    }
  var openingHours by rememberSerializable(originalOpeningHours) {
        mutableStateOf(originalOpeningHours ?: HierarchicOpeningHours())
    }
    val startScreen = remember(element) {
        when {
            originalOpeningHours != null -> OpeningHoursFormScreen.ConfirmRepresentable
            originalOpeningHoursString != null &&
                originalOpeningHoursString.isValidButUnrepresentableOpeningHours() ->
                OpeningHoursFormScreen.ConfirmUnrepresentable
            else -> OpeningHoursFormScreen.Edit
        }
    }
    var screen by rememberSaveable(startScreen) { mutableStateOf(startScreen) }

    var showCommentDialog by remember { mutableStateOf(false) }
    var confirmNoSign by remember { mutableStateOf(false) }
    var confirm24_7 by remember { mutableStateOf(false) }

    val previousReadOnly = screen != OpeningHoursFormScreen.Edit

    val openingHoursTable: @Composable () -> Unit = {
        OpeningHoursTable(
            openingHours = openingHours,
            onChange = { openingHours = it },
            timeMode = TimeMode.Spans,
            countryInfo = countryInfo,
            addButtonContent = { Text(stringResource(Res.string.quest_openingHours_add_times)) },
            countryLocale = countryInfo.userPreferredLocale,
            enabled = !previousReadOnly,
        )
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = ReplaceBottomSheetTransitionSpec
    ) { currentScreen ->
        when (currentScreen) {
            OpeningHoursFormScreen.ConfirmRepresentable -> {
                QuestForm(
                    on = on,
                    answers = listOf(
                        AnswerItem(stringResource(Res.string.quest_generic_hasFeature_no)) {
                            screen = OpeningHoursFormScreen.Edit
                        },
                        AnswerItem(stringResource(Res.string.quest_generic_hasFeature_yes)) {
                            on(Answer(RegularOpeningHours(originalOpeningHours!!)))
                        }
                    ),
                    isResurvey = true,
                    otherAnswers = { listOf(
                        AnswerItem(stringResource(Res.string.quest_openingHours_no_sign)) {
                            confirmNoSign = true
                        },
                    ) },
                    content = { openingHoursTable() }
                )
            }
            OpeningHoursFormScreen.ConfirmUnrepresentable -> {
                QuestForm(
                    on = on,
                    answers = listOf(
                        AnswerItem(stringResource(Res.string.quest_generic_hasFeature_no)) {
                            screen = OpeningHoursFormScreen.Edit
                        },
                        AnswerItem(stringResource(Res.string.quest_generic_hasFeature_yes)) {
                            on(Answer(UnchangedOpeningHours(originalOpeningHoursString!!)))
                        }
                    ),
                    isResurvey = true,
                    otherAnswers = { listOf(
                        AnswerItem(stringResource(Res.string.quest_openingHours_no_sign)) {
                            confirmNoSign = true
                        },
                    ) },
                    content = {
                        Column {
                            Text(stringResource(Res.string.quest_openingHours_unrepresentable_explanation))
                            Text(
                                stringResource(
                                    Res.string.quest_openingHours_stored_value,
                                    originalOpeningHoursString!!
                                ),
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.body2,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                )
            }
            OpeningHoursFormScreen.Edit -> {
                QuestForm(
                    on = on,
                    isComplete = openingHours.isComplete() && !openingHours.isTooLong(),
                    onClickOk = { on(Answer(RegularOpeningHours(openingHours))) },
                    hasChanges =
                        (originalOpeningHours ?: HierarchicOpeningHours()) != openingHours
                        && !openingHours.isEmpty(),
                    otherAnswers = { listOf(
                        AnswerItem(stringResource(Res.string.quest_openingHours_no_sign)) {
                            confirmNoSign = true
                        },
                        AnswerItem(stringResource(Res.string.quest_openingHours_answer_no_regular_opening_hours)) {
                            showCommentDialog = true
                        },
                        AnswerItem(stringResource(Res.string.quest_openingHours_answer_247)) {
                            confirm24_7 = true
                        },
                        AnswerItem(stringResource(Res.string.quest_openingHours_answer_seasonal_opening_hours)) {
                            screen = OpeningHoursFormScreen.Edit
                            openingHours = openingHours.monthsSelectorsAdded()
                        }
                    ) },
                    content = {
                        if (originalOpeningHoursString != null && originalOpeningHours == null) {
                            Text(
                                stringResource(
                                    Res.string.quest_openingHours_malformed_or_unrepresentable_note,
                                    originalOpeningHoursString
                                ),
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        openingHoursTable()
                    }
                )
            }
        }
    }

    if (showCommentDialog) {
        OpeningHoursCommentDialog(
            onDismissRequest = { showCommentDialog = false },
            onConfirm = { on(Answer(DescribeOpeningHours(it))) }
        )
    }
    if (confirmNoSign) {
        AreYouSureDialog(
            onDismissRequest = { confirmNoSign = false },
            onConfirmed = { on(Answer(NoOpeningHoursSign)) },
            titleText = stringResource(Res.string.quest_generic_confirmation_title)
        )
    }
    if (confirm24_7) {
        AreYouSureDialog(
            onDismissRequest = { confirm24_7 = false },
            onConfirmed = { on(Answer(AlwaysOpen)) },
            titleText = stringResource(Res.string.quest_openingHours_24_7_confirmation)
        )
    }
}

private fun HierarchicOpeningHours.monthsSelectorsAdded() = HierarchicOpeningHours(
    monthsList.map { months ->
        if (months.selectors.isEmpty()) {
            months.copy(selectors = listOf(MonthRange(Month.January, Month.December)))
        } else {
            months
        }
    }
)