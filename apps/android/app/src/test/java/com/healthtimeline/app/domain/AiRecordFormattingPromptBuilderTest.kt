package com.healthtimeline.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRecordFormattingPromptBuilderTest {
    @Test fun `prompt contains strict template privacy rules and default year`() {
        val prompt = AiRecordFormattingPromptBuilder.build(2026)

        listOf(
            "日期：", "标题：", "病情分类：", "记录类型：", "症状/病情：", "诊断：",
            "治疗方案：", "就诊用药记录：", "医院：", "医生：", "其他备注："
        ).forEach { assertTrue("缺少字段：$it", prompt.contains(it)) }
        assertTrue(prompt.contains("默认年份 2026"))
        assertTrue(prompt.contains("病名必须放在标题最前面"))
        assertTrue(prompt.contains("错误示例：“术后复查鼻窦炎”"))
        assertTrue(prompt.contains("不得猜测"))
        assertTrue(prompt.contains("完整原文片段"))
        assertTrue(prompt.contains("原文事件在用户输入中的先后顺序"))
        assertTrue(prompt.contains("编号、项目和句子的先后顺序不得重排"))
        assertTrue(prompt.contains("必须以“原文记录：”开始"))
        assertTrue(prompt.contains("删除姓名、身份证号、电话号码、住址"))
        assertTrue(prompt.trimEnd().endsWith("用户信息如下："))
        assertFalse(prompt.contains("患者真实原文占位内容"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unreasonable default year is rejected`() {
        AiRecordFormattingPromptBuilder.build(0)
    }
}
