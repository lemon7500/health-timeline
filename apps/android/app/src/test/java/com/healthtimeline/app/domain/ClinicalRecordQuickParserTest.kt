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

    @Test fun `template title is automatically prefixed with condition name`() {
        val result = ClinicalRecordQuickParser.parse(
            "日期：2026-09-06\n标题：复查\n病情分类：上颌窦炎"
        )

        assertFalse(result.hasErrors)
        assertEquals("上颌窦炎复查", result.title)
    }

    @Test fun `condition is moved to the first position of title`() {
        assertEquals("鼻窦炎术后复查", ClinicalRecordQuickParser.titleWithCondition("术后复查鼻窦炎", "鼻窦炎"))
        assertEquals("鼻窦炎复查", ClinicalRecordQuickParser.titleWithCondition("复查－鼻窦炎", "鼻窦炎"))
        assertEquals("鼻窦炎术后复查", ClinicalRecordQuickParser.titleWithCondition("鼻窦炎术后复查", "鼻窦炎"))
    }

    @Test fun `natural Chinese narrative is organized locally and keeps original text`() {
        val input = """
            8月29日再次去见了关丽梅医生，头颈部CT报告出来了，关医生意见
            1）牙骨髓炎，有可能是下颌骨坏死，目前的状况不能判断病情是否还在进展，现在如果手术，之后还会继续出现问题，就无法一次性解决问题。
            2）按照6月17日才打地舒单抗，正常也需要3～6个月才能手术取坏的牙根，不然担心伤口不能愈合。
            3）判定目前不是进行手术的好时机。等12月份再来找她，需要手术去除牙根，也有可能不需要手术。
            4）目前先每天保持好口腔卫生，控制住炎症。有开了药，如果口腔炎症严重，再吃。
        """.trimIndent()

        val result = ClinicalRecordQuickParser.parse(input, LocalDate.of(2026, 9, 6))

        assertFalse(result.hasErrors)
        assertEquals(LocalDate.of(2026, 8, 29), result.recordDate)
        assertEquals("牙骨髓炎", result.conditionName)
        assertEquals("牙骨髓炎头颈部CT复查", result.title)
        assertEquals(VisitStage.CHECKUP, result.stage)
        assertEquals("关丽梅医生", result.clinician)
        assertTrue(result.diagnosis.contains("牙骨髓炎"))
        assertTrue(result.diagnosis.contains("下颌骨坏死"))
        assertFalse(result.diagnosis.contains("手术"))
        assertFalse(result.diagnosis.contains("不能判断病情"))
        assertTrue(result.treatment.contains("手术"))
        assertTrue(result.medicationNotes.contains("地舒单抗"))
        assertTrue(result.medicationNotes.contains("开了药"))
        assertEquals("原文记录：\n$input", result.notes)
        assertTrue(result.unrecognizedSegments.isEmpty())
        assertTrue(result.issues.any { it.code == "INFERRED_YEAR" })
        assertTrue(result.issues.any { it.code == "NARRATIVE_MODE" })
    }

    @Test fun `doctor names are recognized from common narrative expressions`() {
        val samples = listOf(
            Triple("9月1日去找李医生复查鼻窦炎。", "李医生", "鼻窦炎"),
            Triple("9月1日向王小明主任咨询鼻窦炎。", "王小明主任", "鼻窦炎"),
            Triple("9月1日，赵敏医生建议继续观察鼻窦炎。", "赵敏医生", "鼻窦炎"),
            Triple("9月1日到口腔科找关丽梅医生复查牙骨髓炎。", "关丽梅医生", "牙骨髓炎")
        )

        samples.forEach { (input, expectedDoctor, expectedCondition) ->
            val result = ClinicalRecordQuickParser.parse(input, LocalDate.of(2026, 9, 7))
            assertFalse("解析失败：$input", result.hasErrors)
            assertEquals("医生识别错误：$input", expectedDoctor, result.clinician)
            assertEquals("疾病识别错误：$input", expectedCondition, result.conditionName)
        }
    }

    @Test fun `explicit diagnosis excludes advice in the same sentence`() {
        val result = ClinicalRecordQuickParser.parse(
            "9月2日找陈医生复查。诊断为术后恢复正常并建议继续用药和观察。",
            LocalDate.of(2026, 9, 7)
        )

        assertFalse(result.hasErrors)
        assertEquals("术后恢复正常", result.diagnosis)
        assertFalse(result.diagnosis.contains("建议"))
        assertFalse(result.diagnosis.contains("用药"))
    }

    @Test fun `generic doctor wording is not mistaken for a doctor name`() {
        val result = ClinicalRecordQuickParser.parse(
            "9月2日复查鼻窦炎，医生建议继续观察。",
            LocalDate.of(2026, 9, 7)
        )

        assertFalse(result.hasErrors)
        assertEquals("", result.clinician)
    }

    @Test fun `several natural dated paragraphs become separate records`() {
        val result = ClinicalRecordQuickParser.parseMany(
            """
                8月29日复查上颌窦炎，鼻塞减轻。
                9月3日再次复查上颌窦炎，医生建议继续冲洗。
            """.trimIndent(),
            LocalDate.of(2026, 9, 6)
        )

        assertFalse(result.hasErrors)
        assertEquals(2, result.records.size)
        assertEquals(LocalDate.of(2026, 8, 29), result.records[0].recordDate)
        assertEquals(LocalDate.of(2026, 9, 3), result.records[1].recordDate)
        assertTrue(result.records.all { it.title?.contains("上颌窦炎") == true })
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

    @Test fun `AI formatted records support multiple events on the same date`() {
        val result = ClinicalRecordQuickParser.parseMany(
            """
                日期：2026-09-01
                标题：鼻窦炎复查
                病情分类：鼻窦炎
                记录类型：复查
                症状/病情：鼻塞减轻
                诊断：鼻窦炎
                治疗方案：继续冲洗
                就诊用药记录：
                医院：甲医院
                医生：李医生
                其他备注：9月1日复查鼻窦炎，鼻塞减轻。

                日期：2026-09-01
                标题：牙骨髓炎用药记录
                病情分类：牙骨髓炎
                记录类型：就诊后
                症状/病情：牙龈疼痛
                诊断：牙骨髓炎
                治疗方案：保持口腔卫生
                就诊用药记录：炎症严重时按医嘱服药
                医院：
                医生：王主任
                其他备注：同日记录牙骨髓炎处理情况。
            """.trimIndent()
        )

        assertFalse(result.hasErrors)
        assertEquals(2, result.records.size)
        assertEquals(LocalDate.of(2026, 9, 1), result.records[0].recordDate)
        assertEquals(LocalDate.of(2026, 9, 1), result.records[1].recordDate)
        assertEquals("李医生", result.records[0].clinician)
        assertEquals("王主任", result.records[1].clinician)
    }

    @Test fun `markdown wrapper is reported as a blocking format error`() {
        val result = ClinicalRecordQuickParser.parseMany(
            "```text\n日期：2026-09-01\n标题：鼻窦炎复查\n```"
        )

        assertTrue(result.hasErrors)
        assertTrue(result.issues.any { it.code == "MARKDOWN_WRAPPER" && it.severity == IssueSeverity.ERROR })
    }

    @Test fun `AI explanation is preserved and visibly reported`() {
        val result = ClinicalRecordQuickParser.parseMany(
            "以下是整理结果\n日期：2026-09-01\n标题：鼻窦炎复查\n病情分类：鼻窦炎"
        )

        assertFalse(result.hasErrors)
        assertTrue(result.records.single().unrecognizedSegments.contains("以下是整理结果"))
        assertTrue(result.records.single().issues.any { it.code == "UNRECOGNIZED_CONTENT" })
    }

    @Test fun `other notes is terminal and preserves labels numbering and order`() {
        val source = """
            日期：2026-09-01
            标题：鼻窦炎复查
            病情分类：鼻窦炎
            记录类型：复查
            症状/病情：鼻塞
            诊断：鼻窦炎
            治疗方案：继续冲洗
            就诊用药记录：
            医院：甲医院
            医生：李医生
            其他备注：原文记录：
            1）先检查。
            医生：原文里的医生标签不能被重新解析
            2）再复诊。
            诊断：原文里的诊断标签也必须保留
        """.trimIndent()

        val result = ClinicalRecordQuickParser.parse(source)

        assertFalse(result.hasErrors)
        assertEquals("李医生", result.clinician)
        assertEquals("鼻窦炎", result.diagnosis)
        assertEquals(
            "原文记录：\n1）先检查。\n医生：原文里的医生标签不能被重新解析\n2）再复诊。\n诊断：原文里的诊断标签也必须保留",
            result.notes
        )
    }

    @Test fun `batch entry preserves semicolons and dates inside terminal original notes`() {
        val rawNotes = "原文记录：\n1）先检查；2）再复诊。\n日期：2026-10-01\n当天服药后鼻塞减轻。"
        val result = ClinicalRecordQuickParser.parseMany(
            "日期：2026-10-11\n标题：鼻窦炎复查\n其他备注：$rawNotes"
        )

        assertFalse(result.hasErrors)
        assertEquals(1, result.records.size)
        assertEquals(LocalDate.of(2026, 10, 11), result.records.single().recordDate)
        assertEquals(rawNotes, result.records.single().notes)
    }

    @Test fun `semicolon template treats other notes as raw terminal content`() {
        val rawNotes = "原文记录：先检查；医生：原文医生;诊断：原文诊断；日期：2026-10-01"
        val result = ClinicalRecordQuickParser.parseMany(
            "日期：2026-10-11；标题：鼻窦炎复查;医生：李医生；诊断：鼻窦炎；其他备注：$rawNotes"
        )

        assertFalse(result.hasErrors)
        assertEquals(1, result.records.size)
        assertEquals("李医生", result.records.single().clinician)
        assertEquals("鼻窦炎", result.records.single().diagnosis)
        assertEquals(rawNotes, result.records.single().notes)
    }

    @Test fun `batch entry keeps natural narrative punctuation and only normalizes newlines`() {
        val input = "8月29日复查鼻窦炎；医生建议先冲洗;再观察。\r\n1）疼痛减轻；2）继续复查。"
        val result = ClinicalRecordQuickParser.parseMany(input, LocalDate.of(2026, 10, 11))

        assertFalse(result.hasErrors)
        assertEquals(1, result.records.size)
        assertEquals("原文记录：\n${input.replace("\r\n", "\n")}", result.records.single().notes)
    }

    @Test fun `batch original source retains whitespace after newline normalization`() {
        val rawNotes = "原文记录：\r\n  1）先检查；再复诊。  \r\n医生：原文内容  "
        val result = ClinicalRecordQuickParser.parseMany(
            "日期：2026-10-11\r\n标题：鼻窦炎复查\r\n其他备注：$rawNotes"
        )

        assertFalse(result.hasErrors)
        assertEquals(rawNotes.replace("\r\n", "\n"), result.records.single().notes)
    }

    @Test fun `date and title pair inside raw notes without a boundary is a blocking ambiguity`() {
        val rawNotes = "原文记录：\n先检查；再复诊。\n日期：2026-10-01\n标题：原检查标题\n医生：原文医生"
        val result = ClinicalRecordQuickParser.parseMany(
            "日期：2026-10-11\n标题：鼻窦炎复查\n其他备注：$rawNotes"
        )

        assertTrue(result.hasErrors)
        assertTrue(result.issues.any { it.code == "AMBIGUOUS_RECORD_BOUNDARY" })
        assertEquals(1, result.records.size)
        assertEquals(rawNotes, result.records.single().notes)
    }

    @Test fun `blank line and partial template inside raw notes does not discard source`() {
        val rawNotes = "原文记录：\n先检查。\n\n日期：2026-10-01\n标题：原检查标题"
        val result = ClinicalRecordQuickParser.parseMany(
            "日期：2026-10-11\n标题：鼻窦炎复查\n其他备注：$rawNotes"
        )

        assertTrue(result.hasErrors)
        assertTrue(result.issues.any { it.code == "AMBIGUOUS_RECORD_BOUNDARY" })
        assertEquals(1, result.records.size)
        assertEquals(rawNotes, result.records.single().notes)
    }

    @Test fun `complete repeated AI templates retain embedded source labels and semicolons`() {
        val firstNotes = "原文记录：\n1）先检查；2）再复诊。\n日期：2026-10-01\n医生：原文医生"
        val secondNotes = "原文记录：\n同日先检查;后复诊。"
        fun record(title: String, notes: String) = """
            日期：2026-10-11
            标题：$title
            病情分类：鼻窦炎
            记录类型：复查
            症状/病情：
            诊断：鼻窦炎
            治疗方案：
            就诊用药记录：
            医院：
            医生：李医生
            其他备注：
        """.trimIndent() + notes
        val result = ClinicalRecordQuickParser.parseMany(
            record("鼻窦炎先检查", firstNotes) + "\n\n" + record("鼻窦炎后复诊", secondNotes)
        )

        assertFalse(result.hasErrors)
        assertEquals(2, result.records.size)
        assertEquals("鼻窦炎先检查", result.records[0].title)
        assertEquals("鼻窦炎后复诊", result.records[1].title)
        assertEquals(firstNotes, result.records[0].notes)
        assertEquals(secondNotes, result.records[1].notes)
    }

    @Test fun `legacy template framing retains trailing original blank lines`() {
        val original = "原文记录：\n先检查。  \n\n"
        val first = ClinicalRecordQuickParser.templateFor(LocalDate.of(2026, 10, 11))
            .replace("标题：", "标题：鼻窦炎先检查")
            .replace("其他备注：", "其他备注：$original")
        val second = ClinicalRecordQuickParser.templateFor(LocalDate.of(2026, 10, 12))
            .replace("标题：", "标题：鼻窦炎后复诊")
            .replace("其他备注：", "其他备注：原文记录：后复查。")
        val result = ClinicalRecordQuickParser.parseMany(first + "\n\n" + second)

        assertFalse(result.hasErrors)
        assertEquals(2, result.records.size)
        assertEquals(original, result.records.first().notes)
    }

    @Test fun `semicolon dated natural events retain their raw punctuation in order`() {
        val result = ClinicalRecordQuickParser.parseMany(
            "8月29日复查鼻窦炎；9月3日再次复查鼻窦炎。",
            LocalDate.of(2026, 10, 11)
        )

        assertFalse(result.hasErrors)
        assertEquals(2, result.records.size)
        assertEquals("原文记录：\n8月29日复查鼻窦炎；", result.records[0].notes)
        assertEquals("原文记录：\n9月3日再次复查鼻窦炎。", result.records[1].notes)
    }

    @Test fun `explicit separators support short templates with terminal source and keep order`() {
        val firstNotes = "原文记录：\n10月11日先检查；医生：原文医生。"
        val secondNotes = "原文记录：\n10月11日再复诊;诊断：原文诊断。"
        val result = ClinicalRecordQuickParser.parseMany(
            "日期：2026-10-11\n标题：鼻窦炎先检查\n其他备注：$firstNotes\n---\n" +
                "日期：2026-10-11；标题：鼻窦炎后复诊；其他备注：$secondNotes"
        )

        assertFalse(result.hasErrors)
        assertEquals(2, result.records.size)
        assertEquals("鼻窦炎先检查", result.records[0].title)
        assertEquals("鼻窦炎后复诊", result.records[1].title)
        assertEquals(firstNotes, result.records[0].notes)
        assertEquals(secondNotes, result.records[1].notes)
    }

    @Test fun `ambiguous source separators are kept and the repair instruction is explicit`() {
        val rawNotes = "原文记录：\n先检查；后复诊。\n---\n医生：原文医生\n诊断：原文诊断"
        val result = ClinicalRecordQuickParser.parseMany(
            "日期：2026-10-11\n标题：鼻窦炎复查\n其他备注：$rawNotes"
        )

        assertTrue(result.hasErrors)
        assertEquals(1, result.records.size)
        assertEquals(rawNotes, result.records.single().notes)
        assertTrue(result.issues.any {
            it.code == "AMBIGUOUS_RECORD_BOUNDARY" && it.message.contains("第 5 行") &&
                it.message.contains("记录之间另起一行写 ---")
        })
    }

    @Test fun `explicit separator removes framing only and retains trailing original blank lines`() {
        val original = "原文记录：\n先检查；再复诊。  \n\n"
        val result = ClinicalRecordQuickParser.parseMany(
            "日期：2026-10-11\n标题：鼻窦炎复查\n其他备注：$original\n---\n" +
                "日期：2026-10-12\n标题：鼻窦炎再复查\n其他备注：原文记录：后复查。"
        )
        assertFalse(result.hasErrors)
        assertEquals(2, result.records.size)
        assertEquals(original, result.records.first().notes)
    }

    @Test fun `explicit separator followed by an invalid date retains the offending record`() {
        val result = ClinicalRecordQuickParser.parseMany(
            "日期：2026-10-11\n标题：鼻窦炎复查\n其他备注：原文记录：先检查。\n---\n" +
                "日期：2026-02-30\n标题：鼻窦炎再复诊\n其他备注：原文记录：再复诊。"
        )

        assertTrue(result.hasErrors)
        assertEquals(2, result.records.size)
        assertFalse(result.records[0].hasErrors)
        assertTrue(result.records[1].issues.any { it.code == "INVALID_DATE" })
        assertEquals("原文记录：再复诊。", result.records[1].notes)
    }

    @Test fun `explicit separators accept dated natural records without rewriting source`() {
        val first = "  10月11日复查鼻窦炎；先检查。  "
        val second = "10月12日复查鼻窦炎;后复诊。"
        val result = ClinicalRecordQuickParser.parseMany("$first\n---\n$second", LocalDate.of(2026, 10, 11))

        assertFalse(result.hasErrors)
        assertEquals(2, result.records.size)
        assertEquals("原文记录：\n$first", result.records[0].notes)
        assertEquals("原文记录：\n$second", result.records[1].notes)
    }

    @Test fun `explicit separator keeps leading blank lines in following original narrative`() {
        val first = "10月11日复查鼻窦炎。"
        val second = "\n\n  10月12日复查鼻窦炎。"
        val result = ClinicalRecordQuickParser.parseMany("$first\n---\n$second", LocalDate.of(2026, 10, 11))

        assertFalse(result.hasErrors)
        assertEquals(2, result.records.size)
        assertEquals("原文记录：\n$second", result.records[1].notes)
    }

    @Test fun `semicolon templates without terminal notes still split into multiple records`() {
        val result = ClinicalRecordQuickParser.parseMany(
            "日期：2026-10-11；标题：鼻窦炎先检查；医生：李医生；" +
                "日期：2026-10-12;标题：鼻窦炎后复诊;医生：王医生"
        )

        assertFalse(result.hasErrors)
        assertEquals(2, result.records.size)
        assertEquals(LocalDate.of(2026, 10, 11), result.records[0].recordDate)
        assertEquals(LocalDate.of(2026, 10, 12), result.records[1].recordDate)
        assertEquals("李医生", result.records[0].clinician)
        assertEquals("王医生", result.records[1].clinician)
    }
}
