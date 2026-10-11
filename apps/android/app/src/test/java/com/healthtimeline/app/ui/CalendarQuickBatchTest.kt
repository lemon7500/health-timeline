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
    @Test fun `source record accepts either Chinese or English colon`() {
        val draft = validDraft().copy(notes = "原文记录:\n9月3日复查，医生建议下月再复诊。")

        assertFalse(draft.validationIssues(false).any { it.code == "MISSING_SOURCE_RECORD" })
    }

    @Test fun `missing source error tells user which repair action to use`() {
        val issue = validDraft().copy(notes = "待核对报告").validationIssues(false)
            .single { it.code == "MISSING_SOURCE_RECORD" }

        assertTrue(issue.message.contains("补充原文"))
        assertTrue(issue.message.contains("这条"))
    }

    @Test fun `formatted AI records without originals are parseable but require source completion`() {
        val parsed = com.healthtimeline.app.domain.ClinicalRecordQuickParser.parseMany(
            "日期：2026-09-03\n标题：鼻窦炎复查\n其他备注：待核对报告\n---\n" +
                "日期：2026-09-04\n标题：牙龈炎复查\n其他备注：",
            java.time.LocalDate.of(2026, 9, 4)
        )
        assertFalse(parsed.hasErrors)
        assertEquals(2, parsed.records.size)
        assertTrue(parsed.records.all { record ->
            record.toEditableQuickDraft().validationIssues(false).any { it.code == "MISSING_SOURCE_RECORD" }
        })
    }

    @Test fun `completion preserves existing notes and original punctuation and ordering`() {
        val original = "9月3日复查；医生：测试医生\n1）先拍片。\n2）再看报告：诊断仍待确认。"
        val notes = completeSourceRecordNotes("待核对报告", original)

        assertEquals("原文记录：\n$original\n\n补充备注：\n待核对报告", notes)
        assertTrue(validDraft().copy(notes = notes).validationIssues(false).isEmpty())
        assertEquals("", initialSourceRecordText("待核对报告"))
    }

    @Test fun `batch save preserves terminal source whitespace dates and semicolons`() {
        val input = "日期：2026-09-03\r\n标题：鼻窦炎复查\r\n其他备注：原文记录：\r\n" +
            "先拍片；日期：2026-08-29；医生：测试医生  \r\n1）讨论报告。\r\n\r\n"
        val parsed = com.healthtimeline.app.domain.ClinicalRecordQuickParser.parseMany(input, java.time.LocalDate.of(2026, 9, 4))
        assertFalse(parsed.hasErrors)
        val request = prepareQuickBatchRequests(
            parsed.records.map { it.toEditableQuickDraft() }, emptyList(), 3, false, "2026-09-07T00:00:00Z"
        ).single()
        assertEquals("原文记录：\n先拍片；日期：2026-08-29；医生：测试医生  \n1）讨论报告。\n\n", request.record.notes)
    }

    @Test fun `source completion rejects empty or label only text`() {
        listOf("", "  ", "原文记录：\n", "原文记录:  ").forEach { source ->
            assertTrue(runCatching { completeSourceRecordNotes("已有备注", source) }.isFailure)
        }
        assertFalse(hasSourceRecord("原文记录: \n"))
        assertFalse(hasSourceRecord("原文记录："))
    }

    @Test fun `completion normalizes newline and avoids duplicate markers or notes`() {
        assertEquals("原文记录：\n第一步\n第二步", completeSourceRecordNotes("原文记录：", "原文记录:\r\n第一步\r第二步"))
        val once = completeSourceRecordNotes("", "真实原文")
        assertEquals(once, completeSourceRecordNotes(once, "真实原文"))
    }

    @Test fun `source completion preserves source and existing notes whitespace after normalizing newlines`() {
        val original = "\r\n  9月3日先检查；再复诊。  \r\n\r\n"
        val existing = "  待核对报告。  \r\n\r\n"
        val normalizedOriginal = "\n  9月3日先检查；再复诊。  \n\n"
        val normalizedExisting = "  待核对报告。  \n\n"
        val completed = completeSourceRecordNotes(existing, original)
        assertEquals("原文记录：\n$normalizedOriginal\n\n补充备注：\n$normalizedExisting", completed)
        assertTrue(hasSourceRecord(completed))
        assertEquals(normalizedOriginal, initialSourceRecordText("原文记录：\r\n$original"))
    }

    @Test fun `source prefill and labelled completion remove only one structural label newline`() {
        val original = "\n  第一步。  \n\n第二步；保留顺序。  \n\n"
        val prefixed = "原文记录：\n$original"
        assertEquals(original, initialSourceRecordText(prefixed))
        assertEquals(prefixed, completeSourceRecordNotes("", "原文记录:\r\n" + original.replace("\n", "\r\n")))
        assertEquals(prefixed, completeSourceRecordNotes(prefixed, original))
        assertEquals("原文记录：\n  第一步。  ", completeSourceRecordNotes("", "原文记录:  第一步。  "))
        assertTrue(runCatching { completeSourceRecordNotes("", "  原文记录:\n  ") }.isFailure)
    }

    @Test fun `combined source and existing notes length is checked without truncation`() {
        val original = "文".repeat(com.healthtimeline.app.domain.ClinicalRecordQuickParser.MAX_NARRATIVE_LENGTH - "原文记录：\n".length)
        assertEquals(10_000, completeSourceRecordNotes("", original).length)
        assertTrue(runCatching { completeSourceRecordNotes("旧备注", original) }.isFailure)
        assertTrue(runCatching { completeSourceRecordNotes("", original + "文") }.isFailure)
    }

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
