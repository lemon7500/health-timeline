package com.healthtimeline.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthtimeline.app.data.*
import com.healthtimeline.app.ui.theme.HealthTimelineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class TodayScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mismatchedMemberDataIsHiddenDuringLoading() {
        val another = member.copy(id = 2, name = "测试成员乙", relationship = "父亲")
        show({ TodayOverviewState(another, date, overview(listOf(dose(1)))) })
        compose.onNodeWithTag("today-loading").assertExists()
        compose.onNodeWithText("测试药物1").assertDoesNotExist()
    }

    @Test fun mismatchedDateDataIsHiddenDuringLoading() {
        show({ TodayOverviewState(member, date.plusDays(1), overview(listOf(dose(1)))) })
        compose.onNodeWithTag("today-loading").assertExists()
        compose.onNodeWithText("测试药物1").assertDoesNotExist()
    }

    @Test fun loadFailureOffersRetryWithoutShowingOldData() {
        var retries = 0
        show({ TodayOverviewState(member, date, null, errorMessage = "读取失败，请重试") }, onRetry = { retries++ })
        compose.onNodeWithTag("today-loading").assertDoesNotExist()
        compose.onNodeWithTag("today-retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun pendingListShowsOnlyThreeUntilExpanded() {
        show({ state((1L..4L).map(::dose)) })
        compose.onNodeWithTag("today-dose-3-3").assertExists()
        compose.onNodeWithTag("today-dose-4-4").assertDoesNotExist()
        compose.onNodeWithTag("today-pending-expand").performScrollTo().performClick()
        compose.onNodeWithTag("today-dose-4-4").assertExists()
    }

    @Test fun completedEntriesStartCollapsedAndOpenDayDetail() {
        var opened = 0
        show({ state(listOf(dose(1, MedicationDayStatus.TAKEN))) }, onOpenMedication = { opened++ })
        compose.onNodeWithTag("today-dose-1-1").assertDoesNotExist()
        compose.onNodeWithTag("today-recorded-expand").performScrollTo().performClick()
        compose.onNodeWithTag("today-dose-1-1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun cancellingDoseConfirmationDoesNotSubmit() {
        var writes = 0
        show({ state(listOf(dose(1))) }, onRecordDose = { _, _, _ -> writes++ })
        compose.onNodeWithTag("today-taken-1-1").performScrollTo().performClick()
        compose.onNodeWithText("确认已服？").assertExists()
        compose.onNodeWithTag("today-dose-cancel").performClick()
        compose.onNodeWithTag("today-dose-confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun takenConfirmationSubmitsOnceWithExplicitActualTime() {
        var writes = 0
        var status: MedicationLogStatus? = null
        var actual: LocalDateTime? = null
        val before = LocalTime.now().withSecond(0).withNano(0)
        show({ state(listOf(dose(1))) }, onRecordDose = { _, submittedStatus, submittedTime ->
            writes++; status = submittedStatus; actual = submittedTime
        })
        compose.onNodeWithTag("today-taken-1-1").performScrollTo().performClick()
        compose.onNodeWithTag("today-dose-confirm").performClick()
        compose.onNodeWithTag("today-dose-confirm").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, writes)
            assertEquals(MedicationLogStatus.TAKEN, status)
            assertEquals(date, actual?.toLocalDate())
            val after = LocalTime.now().withSecond(0).withNano(0)
            assertTrue(actual?.toLocalTime() in setOf(before, after))
        }
    }

    @Test fun skippedConfirmationDoesNotInventActualTime() {
        var status: MedicationLogStatus? = null
        var actual: LocalDateTime? = date.atTime(12, 0)
        show({ state(listOf(dose(1))) }, onRecordDose = { _, submittedStatus, submittedTime ->
            status = submittedStatus; actual = submittedTime
        })
        compose.onNodeWithTag("today-skipped-1-1").performScrollTo().performClick()
        compose.onNodeWithTag("today-dose-confirm").performClick()
        compose.runOnIdle {
            assertEquals(MedicationLogStatus.SKIPPED, status)
            assertNull(actual)
        }
    }

    @Test fun memberSwitchClosesConfirmationAndHidesPreviousMedication() {
        val current = mutableStateOf(state(listOf(dose(1))))
        var writes = 0
        show({ current.value }, onRecordDose = { _, _, _ -> writes++ })
        compose.onNodeWithTag("today-taken-1-1").performScrollTo().performClick()
        compose.onNodeWithTag("today-dose-confirm").assertExists()
        compose.runOnIdle {
            current.value = current.value.copy(member = member.copy(id = 2, name = "测试成员乙", relationship = "父亲"))
        }
        compose.onNodeWithTag("today-dose-confirm").assertDoesNotExist()
        compose.onNodeWithText("测试药物1").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun midnightDateChangeClosesOldConfirmation() {
        val current = mutableStateOf(state(listOf(dose(1))))
        show({ current.value })
        compose.onNodeWithTag("today-taken-1-1").performScrollTo().performClick()
        compose.runOnIdle { current.value = current.value.copy(date = date.plusDays(1), data = null) }
        compose.onNodeWithTag("today-dose-confirm").assertDoesNotExist()
        compose.onNodeWithTag("today-loading").assertExists()
    }

    @Test fun removedDoseInvalidatesOpenConfirmation() {
        val current = mutableStateOf(state(listOf(dose(1))))
        var writes = 0
        show({ current.value }, onRecordDose = { _, _, _ -> writes++ })
        compose.onNodeWithTag("today-taken-1-1").performScrollTo().performClick()
        compose.runOnIdle { current.value = state(emptyList()) }
        compose.onNodeWithTag("today-dose-confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun busyStateDisablesNewDoseActions() {
        show({ state(listOf(dose(1))) }, busy = true)
        compose.onNodeWithTag("today-taken-1-1").assertIsNotEnabled()
        compose.onNodeWithTag("today-skipped-1-1").assertIsNotEnabled()
        compose.onNodeWithTag("today-add-record").assertIsNotEnabled()
    }

    @Test fun endedCourseShowsHistoryWithoutContinuingButtons() {
        val ended = dose(1).let { it.copy(medication = it.medication.copy(archived = true, endedAt = NOW, endDate = date.toString())) }
        show({ state(listOf(ended)) })
        compose.onNodeWithText("未记录 · 疗程已结束").assertExists()
        compose.onNodeWithTag("today-taken-1-1").assertDoesNotExist()
        compose.onNodeWithTag("today-skipped-1-1").assertDoesNotExist()
        compose.onNodeWithText("1 片").assertExists()
    }

    @Test fun futureFollowUpIsReadOnlyAndCanOpenDetail() {
        val item = followUp(1, date.plusDays(1))
        var opened: TodayFollowUpItem? = null
        show({ TodayOverviewState(member, date, overview(upcoming = listOf(item))) }, onOpenFollowUp = { opened = it })
        compose.onNodeWithTag("today-follow-complete-1-${item.dueDate}").assertDoesNotExist()
        compose.onNodeWithTag("today-follow-skip-1-${item.dueDate}").assertDoesNotExist()
        compose.onNodeWithTag("today-follow-open-1-${item.dueDate}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(item, opened) }
    }

    @Test fun dueFollowUpNeedsConfirmationAndCancellationDoesNotSubmit() {
        val item = followUp(1, date)
        var writes = 0
        var skipped: Boolean? = null
        show({ TodayOverviewState(member, date, overview(upcoming = listOf(item))) }, onConfirmFollowUp = { _, value -> writes++; skipped = value })
        compose.onNodeWithTag("today-follow-complete-1-$date").performScrollTo().performClick()
        compose.onNodeWithTag("today-follow-cancel").performClick()
        compose.runOnIdle { assertEquals(0, writes) }
        compose.onNodeWithTag("today-follow-skip-1-$date").performScrollTo().performClick()
        compose.onNodeWithTag("today-follow-confirm").performClick()
        compose.runOnIdle { assertEquals(1, writes); assertEquals(true, skipped) }
    }

    @Test fun asNeededHasNoFictitiousPlanAndNeedsExplicitConfirmation() {
        val medication = dose(1).medication.copy(mode = MedicationMode.AS_NEEDED.name)
        var writes = 0
        show({ TodayOverviewState(member, date, overview(asNeeded = listOf(medication))) }, onRecordAsNeeded = { selected, actual ->
            assertEquals(medication, selected); assertEquals(date, actual.toLocalDate()); writes++
        })
        compose.onNodeWithTag("today-dose-1-1").assertDoesNotExist()
        compose.onNodeWithTag("today-as-needed").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, writes) }
        compose.onNodeWithTag("today-as-needed-confirm").performClick()
        compose.runOnIdle { assertEquals(1, writes) }
    }

    @Test fun changedAsNeededDoseClosesConfirmationUntilRechecked() {
        val medication = dose(1).medication.copy(mode = MedicationMode.AS_NEEDED.name)
        val current = mutableStateOf(TodayOverviewState(member, date, overview(asNeeded = listOf(medication))))
        var writes = 0
        show({ current.value }, onRecordAsNeeded = { _, _ -> writes++ })
        compose.onNodeWithTag("today-as-needed").performScrollTo().performClick()
        compose.onNodeWithText("剂量：9 片").assertExists()
        compose.runOnIdle {
            current.value = current.value.copy(data = overview(asNeeded = listOf(
                medication.copy(doseAmount = "2", updatedAt = "2026-10-08T09:00:00Z")
            )))
        }
        compose.onNodeWithTag("today-as-needed-confirm").assertDoesNotExist()
        compose.onNodeWithText("按需药物资料已更新，请重新打开补记并核对。").assertExists()
        compose.runOnIdle { assertEquals(0, writes) }
        compose.onNodeWithTag("today-as-needed").performScrollTo().performClick()
        compose.onNodeWithText("剂量：2 片").assertExists()
        compose.onNodeWithTag("today-as-needed-cancel").performClick()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun safetySummaryOpensExistingSafetyPage() {
        var opened = 0
        show({ state(emptyList()).copy(safetyMessages = listOf("通知已关闭", "尚未导出备份")) }, onOpenSafety = { opened++ })
        compose.onNodeWithTag("today-open-safety").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    private fun show(
        state: () -> TodayOverviewState,
        onRecordDose: (MedicationDayEntry, MedicationLogStatus, LocalDateTime?) -> Unit = { _, _, _ -> },
        onRecordAsNeeded: (MedicationEntity, LocalDateTime) -> Unit = { _, _ -> },
        onConfirmFollowUp: (TodayFollowUpItem, Boolean) -> Unit = { _, _ -> },
        onOpenMedication: () -> Unit = {},
        onOpenFollowUp: (TodayFollowUpItem) -> Unit = {},
        onOpenSafety: () -> Unit = {},
        onRetry: () -> Unit = {},
        busy: Boolean = false
    ) {
        compose.setContent {
            HealthTimelineTheme {
                TodayScreen(
                    state = state(), padding = PaddingValues(), onSelectMember = {},
                    members = listOf(member, member.copy(id = 2, name = "测试成员乙", relationship = "父亲")),
                    onAddRecord = {}, onRecordDose = onRecordDose, onRecordAsNeeded = onRecordAsNeeded,
                    onConfirmFollowUp = onConfirmFollowUp, onOpenMedication = onOpenMedication,
                    onOpenFollowUp = onOpenFollowUp, onOpenSafety = onOpenSafety, onRetry = onRetry, busy = busy
                )
            }
        }
    }

    private fun state(entries: List<MedicationDayEntry>) = TodayOverviewState(member, date, overview(entries))

    private fun overview(
        entries: List<MedicationDayEntry> = emptyList(),
        asNeeded: List<MedicationEntity> = emptyList(),
        overdue: List<TodayFollowUpItem> = emptyList(),
        upcoming: List<TodayFollowUpItem> = emptyList()
    ) = TodayOverviewData(
        memberId = member.id, date = date, medicationEntries = entries, asNeededMedications = asNeeded,
        overdueFollowUps = overdue, upcomingFollowUps = upcoming
    )

    private fun dose(id: Long, status: MedicationDayStatus = MedicationDayStatus.UNRECORDED): MedicationDayEntry {
        val planned = date.atTime(7 + id.toInt(), 0)
        val medication = MedicationEntity(
            id = id, name = "测试药物$id", doseAmount = "9", doseUnit = "片", startDate = date.toString(),
            createdAt = NOW, updatedAt = NOW, memberId = member.id
        )
        val schedule = MedicationScheduleEntity(
            id = id, medicationId = id, localTime = planned.toLocalTime().toString(), effectiveFrom = date.toString(),
            doseAmountSnapshot = "1", doseUnitSnapshot = "片", updatedAt = NOW
        )
        val log = if (status == MedicationDayStatus.UNRECORDED) null else MedicationLogEntity(
            id = id, medicationId = id, scheduleId = id, scheduledAt = planned.toString(),
            actualAt = if (status == MedicationDayStatus.TAKEN) planned.toString() else null,
            status = if (status == MedicationDayStatus.TAKEN) MedicationLogStatus.TAKEN.name else MedicationLogStatus.SKIPPED.name,
            doseAmountSnapshot = "1", doseUnitSnapshot = "片", createdAt = NOW
        )
        return MedicationDayEntry(medication, schedule, log, planned, log?.actualAt?.let(LocalDateTime::parse), status)
    }

    private fun followUp(id: Long, due: LocalDate) = TodayFollowUpItem(
        schedule = FollowUpScheduleEntity(
            id = id, title = "测试复查$id", recurrenceType = RecurrenceType.ONCE.name,
            anchorDate = due.toString(), anchorDayOfMonth = due.dayOfMonth, nextDueDate = due.toString(),
            createdAt = NOW, updatedAt = NOW, memberId = member.id
        ), occurrence = null, dueDate = due, conditionName = "测试分类"
    )

    private val date = LocalDate.of(2026, 10, 8)
    private val member = FamilyMemberEntity(id = 1, name = "测试成员甲", nickname = "母亲", relationship = "母亲", createdAt = NOW, updatedAt = NOW)

    companion object { private const val NOW = "2026-10-08T08:00:00Z" }
}
