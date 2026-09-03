package com.healthtimeline.app.ui

import com.healthtimeline.app.data.ClinicalRecordEntity
import com.healthtimeline.app.data.ConditionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSearchTest {
    private val conditions = listOf(
        ConditionEntity(id = 1, name = "乳腺复查", createdAt = "2026-01-01T00:00:00Z"),
        ConditionEntity(id = 2, name = "上颌窦炎术后", createdAt = "2026-01-01T00:00:00Z")
    )

    private val records = listOf(
        record(id = 1, conditionId = 1, date = "2026-07-09", title = "乳腺彩超", diagnosis = "定期复查"),
        record(id = 2, conditionId = 2, date = "2026-09-03", title = "术后复诊", medication = "鼻腔冲洗")
    )

    @Test
    fun searchesAcrossTitleConditionAndClinicalFields() {
        assertEquals(1L, searchCalendarRecords(records, conditions, "彩超").single().id)
        assertEquals(2L, searchCalendarRecords(records, conditions, "上颌窦炎").single().id)
        assertEquals(2L, searchCalendarRecords(records, conditions, "鼻腔冲洗").single().id)
    }

    @Test
    fun blankQueryReturnsNoResultsAndUnknownTextDoesNotMatch() {
        assertTrue(searchCalendarRecords(records, conditions, "  ").isEmpty())
        assertTrue(searchCalendarRecords(records, conditions, "不存在的内容").isEmpty())
    }

    @Test
    fun newestMatchingRecordComesFirst() {
        val result = searchCalendarRecords(records, conditions, "复")
        assertEquals(listOf(2L, 1L), result.map { it.id })
    }

    private fun record(
        id: Long,
        conditionId: Long,
        date: String,
        title: String,
        diagnosis: String = "",
        medication: String = ""
    ) = ClinicalRecordEntity(
        id = id,
        conditionId = conditionId,
        recordDate = date,
        title = title,
        diagnosis = diagnosis,
        medicationNotes = medication,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z"
    )
}
