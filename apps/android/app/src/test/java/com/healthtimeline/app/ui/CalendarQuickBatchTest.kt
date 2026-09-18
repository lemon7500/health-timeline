package com.healthtimeline.app.ui

import com.healthtimeline.app.data.ConditionEntity
import com.healthtimeline.app.data.RecordConditionResolution
import com.healthtimeline.app.data.VisitStage
import com.healthtimeline.app.domain.IssueSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarQuickBatchTest {
    @Test fun `edited batch values are the values prepared for atomic save`() {
        val draft = validDraft().copy(
            date = "2026-09-04",
            title = "复查",
            conditionName = "鼻窦炎",
            stage = VisitStage.CHECKUP.name,
            diagnosis = "恢复良好",
            clinician = "李医生"
        )
        val condition = ConditionEntity(
            id = 7,
            name = "鼻窦炎",
            createdAt = "2026-09-01T00:00:00Z",
            memberId = 3
        )

        assertTrue(draft.validationIssues(false).isEmpty())
        val request = prepareQuickBatchRequests(
            drafts = listOf(draft),
            conditions = listOf(condition),
            memberId = 3,
            appendUnknownToNotes = false,
            timestamp = "2026-09-07T00:00:00Z"
        ).single()

        assertEquals("2026-09-04", request.record.recordDate)
        assertEquals("鼻窦炎复查", request.record.title)
        assertEquals("恢复良好", request.record.diagnosis)
        assertEquals("李医生", request.record.clinician)
        assertEquals(7L, request.record.conditionId)
        assertEquals(3L, request.record.memberId)
        assertEquals(RecordConditionResolution.Selected, request.conditionResolution)
    }

    @Test fun `invalid edited batch values are blocked before save`() {
        val issues = validDraft().copy(
            date = "9月4日",
            title = "",
            clinician = "医".repeat(101)
        ).validationIssues(false)

        assertTrue(issues.any { it.code == "INVALID_DATE" })
        assertTrue(issues.any { it.code == "MISSING_TITLE" })
        assertTrue(issues.any { it.code == "CLINICIAN_TOO_LONG" })
    }

    @Test fun `unknown text can be appended without losing edited notes`() {
        val draft = validDraft().copy(
            conditionName = "新分类",
            notes = "原文记录：\n人工修改后的备注",
            unrecognizedSegments = listOf("原文剩余内容")
        )

        assertFalse(draft.validationIssues(true).any { it.severity == IssueSeverity.ERROR })
        val request = prepareQuickBatchRequests(
            drafts = listOf(draft),
            conditions = emptyList(),
            memberId = 1,
            appendUnknownToNotes = true,
            timestamp = "2026-09-07T00:00:00Z"
        ).single()

        assertEquals("原文记录：\n人工修改后的备注\n原文剩余内容", request.record.notes)
        assertEquals(RecordConditionResolution.Create("新分类"), request.conditionResolution)
    }

    private fun validDraft() = EditableQuickDraft(
        date = "2026-09-03",
        title = "病情记录",
        conditionName = "",
        stage = VisitStage.OTHER.name,
        symptoms = "",
        diagnosis = "",
        treatment = "",
        medicationNotes = "",
        hospital = "",
        clinician = "",
        notes = "原文记录：\n原文",
        parserWarnings = emptyList(),
        unrecognizedSegments = emptyList()
    )
}
