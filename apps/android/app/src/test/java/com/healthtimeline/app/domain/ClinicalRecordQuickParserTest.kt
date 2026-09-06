package com.healthtimeline.app.domain

import com.healthtimeline.app.data.VisitStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ClinicalRecordQuickParserTest {
    @Test fun `complete template maps every field`() {
        val result = ClinicalRecordQuickParser.parse(
            """
                日期：2026-09-06
                标题：上颌窦炎术后复查
                病情分类：上颌窦炎
                记录类型：复查
                症状/病情：鼻塞减轻，偶有头痛
                诊断：术后恢复正常
                治疗方案：继续冲洗鼻腔
                就诊用药记录：按医嘱继续使用鼻喷剂
                医院：某某医院
                医生：张医生
                其他备注：三个月后再次复查
            """.trimIndent()
        )

        assertFalse(result.hasErrors)
        assertEquals(LocalDate.of(2026, 9, 6), result.recordDate)
        assertEquals("上颌窦炎术后复查", result.title)
        assertEquals("上颌窦炎", result.conditionName)
        assertEquals(VisitStage.CHECKUP, result.stage)
        assertEquals("鼻塞减轻，偶有头痛", result.symptoms)
        assertEquals("术后恢复正常", result.diagnosis)
        assertEquals("继续冲洗鼻腔", result.treatment)
        assertEquals("按医嘱继续使用鼻喷剂", result.medicationNotes)
        assertEquals("某某医院", result.hospital)
        assertEquals("张医生", result.clinician)
        assertEquals("三个月后再次复查", result.notes)
    }

    @Test fun `single line aliases and slash date are accepted`() {
        val result = ClinicalRecordQuickParser.parse(
            "医生:李医生；用药:每日一次；标题:复诊；日期:2026/9/6；病情:稳定；类型:就诊后"
        )
        assertFalse(result.hasErrors)
        assertEquals(LocalDate.of(2026, 9, 6), result.recordDate)
        assertEquals(VisitStage.AFTER_VISIT, result.stage)
        assertEquals("稳定", result.symptoms)
        assertEquals("每日一次", result.medicationNotes)
    }

    @Test fun `Chinese date and multiline continuation are accepted`() {
        val result = ClinicalRecordQuickParser.parse("日期：2026年9月6日\n标题：复查\n诊断：第一行\n第二行")
        assertFalse(result.hasErrors)
        assertEquals(LocalDate.of(2026, 9, 6), result.recordDate)
        assertEquals("第一行\n第二行", result.diagnosis)
    }

    @Test fun `missing required fields and fuzzy date are rejected`() {
        val missing = ClinicalRecordQuickParser.parse("诊断：稳定")
        assertTrue(missing.hasErrors)
        assertTrue(missing.issues.any { it.code == "MISSING_DATE" })
        assertTrue(missing.issues.any { it.code == "MISSING_TITLE" })

        val fuzzy = ClinicalRecordQuickParser.parse("日期：昨天\n标题：复查")
        assertTrue(fuzzy.hasErrors)
        assertNull(fuzzy.recordDate)
        assertTrue(fuzzy.issues.any { it.code == "INVALID_DATE" })
    }

    @Test fun `invalid calendar date is rejected`() {
        val result = ClinicalRecordQuickParser.parse("日期：2026-02-30\n标题：复查")
        assertTrue(result.hasErrors)
        assertTrue(result.issues.any { it.code == "INVALID_DATE" })
    }

    @Test fun `conflicting scalar values are errors`() {
        val result = ClinicalRecordQuickParser.parse("日期：2026-09-06\n日期：2026-09-07\n标题：复查")
        assertTrue(result.hasErrors)
        assertTrue(result.issues.any { it.code == "CONFLICTING_DATE" })
    }

    @Test fun `repeated narratives are merged with warning`() {
        val result = ClinicalRecordQuickParser.parse("日期：2026-09-06\n标题：复查\n症状：鼻塞\n症状：头痛")
        assertFalse(result.hasErrors)
        assertEquals("鼻塞\n头痛", result.symptoms)
        assertTrue(result.issues.any { it.code == "MERGED_SYMPTOMS" })
    }

    @Test fun `unknown labeled text is preserved`() {
        val result = ClinicalRecordQuickParser.parse("日期：2026-09-06\n标题：复查\n血压：120/80")
        assertFalse(result.hasErrors)
        assertEquals(listOf("血压：120/80"), result.unrecognizedSegments)
        assertTrue(result.issues.any { it.code == "UNRECOGNIZED_CONTENT" })
    }

    @Test fun `colon inside known field content is preserved`() {
        val result = ClinicalRecordQuickParser.parse("日期：2026-09-06\n标题：复查\n备注：联系电话：123456")
        assertFalse(result.hasErrors)
        assertEquals("联系电话：123456", result.notes)
    }

    @Test fun `oversized input and fields fail without truncation`() {
        val inputResult = ClinicalRecordQuickParser.parse("字".repeat(ClinicalRecordQuickParser.MAX_INPUT_LENGTH + 1))
        assertTrue(inputResult.issues.any { it.code == "INPUT_TOO_LONG" })

        val title = "标".repeat(101)
        val fieldResult = ClinicalRecordQuickParser.parse("日期：2026-09-06\n标题：$title")
        assertEquals(title, fieldResult.title)
        assertTrue(fieldResult.issues.any { it.code == "TITLE_TOO_LONG" })
    }

    @Test fun `condition normalization ignores spaces and case`() {
        assertEquals(
            ClinicalRecordQuickParser.normalizeConditionName(" Breast Check "),
            ClinicalRecordQuickParser.normalizeConditionName("breastcheck")
        )
    }

    @Test fun `long entry is split into several dated records`() {
        val result = ClinicalRecordQuickParser.parseMany(
            """
                日期：2026-09-06
                标题：第一次复查
                症状：鼻塞

                日期：2026-09-09
                标题：第二次复查
                诊断：恢复良好

                日期：2026年9月12日；标题：第三次复查；治疗：继续冲洗
            """.trimIndent()
        )

        assertFalse(result.hasErrors)
        assertEquals(3, result.records.size)
        assertEquals(LocalDate.of(2026, 9, 9), result.records[1].recordDate)
        assertEquals("恢复良好", result.records[1].diagnosis)
        assertEquals("继续冲洗", result.records[2].treatment)
    }

    @Test fun `batch reports the exact record containing an error`() {
        val result = ClinicalRecordQuickParser.parseMany(
            "日期：2026-09-06\n标题：有效\n日期：2026-02-30\n标题：无效"
        )

        assertTrue(result.hasErrors)
        assertFalse(result.records[0].hasErrors)
        assertTrue(result.records[1].issues.any { it.code == "INVALID_DATE" })
    }
}
