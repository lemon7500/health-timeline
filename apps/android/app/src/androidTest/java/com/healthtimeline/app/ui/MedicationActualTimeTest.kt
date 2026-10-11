package com.healthtimeline.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthtimeline.app.data.LEGACY_MEDICATION_HISTORY_MARKER
import com.healthtimeline.app.data.LEGACY_MEDICATION_HISTORY_NAME_PREFIX
import com.healthtimeline.app.data.MedicationEntity
import com.healthtimeline.app.data.MedicationMode
import com.healthtimeline.app.ui.theme.HealthTimelineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class MedicationActualTimeTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2026, 10, 11)

    @Test fun scheduledDoseDefaultAt2359ConfirmsSameDay2355() {
        var submitted: LocalDateTime? = null
        compose.setContent {
            HealthTimelineTheme {
                MedicationActualTimeDialog(
                    date = date, plannedAt = date.atTime(8, 0), now = date.atTime(23, 59),
                    onConfirm = { submitted = it }, onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("23:55").assertExists()
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals(date.atTime(23, 55), submitted) }
    }

    @Test fun actualTimeWheelStillAcceptsFiveMinuteSelections() {
        var submitted: LocalDateTime? = null
        compose.setContent {
            HealthTimelineTheme {
                MedicationActualTimeDialog(
                    date = date, now = date.atTime(23, 59),
                    onConfirm = { submitted = it }, onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("50 分").performClick()
        compose.onNodeWithText("23:50").assertExists()
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals(date.atTime(23, 50), submitted) }
    }

    @Test fun asNeededDefaultAt2359ConfirmsSameDay2355() {
        var submitted: LocalDateTime? = null
        var submittedMedication: MedicationEntity? = null
        val medication = medication().copy(mode = MedicationMode.AS_NEEDED.name)
        compose.setContent {
            HealthTimelineTheme {
                AsNeededRecordDialog(
                    medications = listOf(medication), date = date, now = date.atTime(23, 59),
                    onConfirm = { value, actualAt -> submittedMedication = value; submitted = actualAt },
                    onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("实际服药时间 23:55").assertExists()
        compose.onNodeWithText("确认记录").performClick()
        compose.runOnIdle {
            assertEquals(medication, submittedMedication)
            assertEquals(date.atTime(23, 55), submitted)
        }
    }

    @Test fun pastDoseConfirmationRetainsTheSelectedCalendarDate() {
        var submitted: LocalDateTime? = null
        val past = date.minusDays(1)
        compose.setContent {
            HealthTimelineTheme {
                MedicationActualTimeDialog(
                    date = past, plannedAt = past.atTime(23, 59), now = date.atTime(0, 1),
                    onConfirm = { submitted = it }, onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("23:55").assertExists()
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals(past.atTime(23, 55), submitted) }
    }

    @Test fun legacyHistoryArchiveExplainsUnknownStopTimeAndCannotRestoreCourse() {
        val legacy = medication().copy(
            name = LEGACY_MEDICATION_HISTORY_NAME_PREFIX + "测试药物",
            instructions = LEGACY_MEDICATION_HISTORY_MARKER + "仅保存旧版服药记录，不是新医嘱。",
            archived = true, endedAt = "2026-10-11T08:00:00Z"
        )
        compose.setContent {
            HealthTimelineTheme {
                EndedMedicationCard(legacy, "", onRestore = {}, onDelete = {})
            }
        }
        compose.onNodeWithText("旧版迁移归档，实际停药时间未知").assertExists()
        compose.onNodeWithText("仅保存旧版服药记录，不是新医嘱。").assertExists()
        compose.onNodeWithText("恢复疗程").assertDoesNotExist()
        compose.onNodeWithText(LEGACY_MEDICATION_HISTORY_MARKER, substring = true).assertDoesNotExist()
        compose.onNodeWithText("结束时间：", substring = true).assertDoesNotExist()
        compose.onNodeWithText("删除").assertExists()
    }

    @Test fun ordinaryEndedMedicationStillShowsStopDateAndSupportsRestore() {
        var restored = 0
        val ordinary = medication().copy(archived = true, endDate = date.toString())
        compose.setContent {
            HealthTimelineTheme {
                EndedMedicationCard(ordinary, "08:00", onRestore = { restored++ }, onDelete = {})
            }
        }
        compose.onNodeWithText("结束时间：$date").assertExists()
        compose.onNodeWithText("旧版迁移归档，实际停药时间未知").assertDoesNotExist()
        compose.onNodeWithText("恢复疗程").performClick()
        compose.runOnIdle { assertEquals(1, restored) }
    }

    @Test fun historyLikeNameAloneDoesNotHideOrdinaryCourseRestore() {
        val ordinary = medication().copy(name = LEGACY_MEDICATION_HISTORY_NAME_PREFIX + "测试药物", archived = true)
        compose.setContent {
            HealthTimelineTheme {
                EndedMedicationCard(ordinary, "08:00", onRestore = {}, onDelete = {})
            }
        }
        compose.onNodeWithText("恢复疗程").assertExists()
        compose.onNodeWithText("旧版迁移归档，实际停药时间未知").assertDoesNotExist()
    }

    private fun medication() = MedicationEntity(
        id = 1, name = "测试药物", doseAmount = "1", doseUnit = "片", startDate = date.toString(),
        createdAt = "2026-10-11T00:00:00Z", updatedAt = "2026-10-11T00:00:00Z", memberId = 1
    )
}
