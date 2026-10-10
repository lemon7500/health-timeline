package com.healthtimeline.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthtimeline.app.data.ClinicalRecordEntity
import com.healthtimeline.app.data.ClinicalRecordSaveRequest
import com.healthtimeline.app.domain.ClinicalRecordQuickParser
import com.healthtimeline.app.ui.theme.HealthTimelineTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real editor; persistence is replaced with captured save callbacks. */
@RunWith(AndroidJUnit4::class)
class QuickEntrySourceRecoveryTest {
    @get:Rule val compose = createComposeRule()

    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private val savedRecords = mutableListOf<ClinicalRecordEntity>()
    private val savedBatches = mutableListOf<List<ClinicalRecordSaveRequest>>()

    @After fun cleanup() {
        compose.runOnIdle { owner.viewModelStore.clear() }
    }

    @Test fun singleTemplateCanBeRepairedWithOriginalTextBeforeSaving() {
        showEditor()
        parse(template("2026-09-04", "鼻窦炎复查"))
        compose.runOnIdle { assertNoSaves() }

        compose.onNodeWithTag("quick-source-repair-single").performScrollTo().performClick()
        compose.onNodeWithText("补充原始病程").assertExists()
        val original = "9月4日复查鼻窦炎；医生：测试医生\n1）先检查。\n2）再讨论检查结果。"
        enterSource(original)
        compose.onNodeWithTag("quick-source-confirm").performClick()
        compose.onNodeWithTag("quick-source-input").assertDoesNotExist()
        compose.runOnIdle { assertNoSaves() }

        compose.onNodeWithTag("record-save").performClick()
        compose.runOnIdle {
            assertEquals(1, savedRecords.size)
            assertTrue(savedBatches.isEmpty())
            val saved = savedRecords.single()
            assertEquals("2026-09-04", saved.recordDate)
            assertEquals("鼻窦炎复查", saved.title)
            assertEquals(MEMBER_ID, saved.memberId)
            assertEquals("${ClinicalRecordQuickParser.SOURCE_PREFIX}\n$original", saved.notes)
            assertFalse(saved.notes.contains("标题：鼻窦炎复查"))
        }
    }

    @Test fun repairingOnlySecondRecordKeepsOtherSourcesAndInputOrder() {
        showEditor()
        val firstSource = "第一事件原文：鼻窦炎第一次复查。"
        val thirdSource = "第三事件原文：鼻窦炎追加检查。"
        val secondNotes = "待核对报告，不是整理前原文。"
        parse(
            listOf(
                template("2026-09-03", "鼻窦炎第一次复查", "${ClinicalRecordQuickParser.SOURCE_PREFIX}\n$firstSource"),
                template("2026-09-01", "牙龈炎复查", secondNotes),
                template("2026-09-03", "鼻窦炎追加检查", "${ClinicalRecordQuickParser.SOURCE_PREFIX}\n$thirdSource")
            ).joinToString("\n\n")
        )

        compose.onNodeWithTag("quick-source-repair-0").assertDoesNotExist()
        compose.onNodeWithTag("quick-source-repair-2").assertDoesNotExist()
        compose.onNodeWithTag("quick-source-repair-1").performScrollTo().performClick()
        val secondSource = "第二事件真实原文：9月1日牙龈疼痛，医生安排复查；次序保持不变。"
        enterSource(secondSource)
        compose.onNodeWithTag("quick-source-confirm").performClick()
        compose.runOnIdle { assertNoSaves() }

        compose.onNodeWithTag("quick-batch-save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertNoSaves() }
        compose.onNodeWithTag("quick-batch-confirm").performClick()
        compose.runOnIdle {
            assertTrue(savedRecords.isEmpty())
            assertEquals(1, savedBatches.size)
            val records = savedBatches.single().map { it.record }
            assertEquals(listOf("2026-09-03", "2026-09-01", "2026-09-03"), records.map { it.recordDate })
            assertEquals(listOf("鼻窦炎第一次复查", "牙龈炎复查", "鼻窦炎追加检查"), records.map { it.title })
            assertTrue(records.all { it.memberId == MEMBER_ID })
            assertEquals("${ClinicalRecordQuickParser.SOURCE_PREFIX}\n$firstSource", records[0].notes)
            assertEquals("${ClinicalRecordQuickParser.SOURCE_PREFIX}\n$thirdSource", records[2].notes)
            assertTrue(records[1].notes.startsWith("${ClinicalRecordQuickParser.SOURCE_PREFIX}\n$secondSource"))
            assertTrue(records[1].notes.contains(secondNotes))
            assertFalse(records[1].notes.contains(firstSource))
            assertFalse(records[1].notes.contains(thirdSource))
        }
    }

    @Test fun cancellingSourceSupplementDoesNotWriteOrAllowMissingSourceToSave() {
        showEditor()
        parse(template("2026-09-04", "鼻窦炎复查"))
        compose.onNodeWithTag("quick-source-repair-single").performScrollTo().performClick()
        enterSource("取消补充的原文，不应写入任何病历。")
        compose.onNodeWithTag("quick-source-cancel").performClick()

        compose.onNodeWithTag("quick-source-input").assertDoesNotExist()
        compose.onNodeWithTag("quick-source-repair-single").assertExists()
        compose.runOnIdle { assertNoSaves() }
        compose.onNodeWithTag("record-save").performClick()
        compose.runOnIdle { assertNoSaves() }
    }

    @Test fun ordinaryManualRecordCanSaveWithoutOriginalText() {
        showEditor()
        compose.onNodeWithText("小标题 *").performScrollTo().performTextReplacement("鼻窦炎手动记录")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("record-save").assertIsEnabled().performClick()

        compose.onNodeWithTag("quick-source-input").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, savedRecords.size)
            assertTrue(savedBatches.isEmpty())
            val saved = savedRecords.single()
            assertEquals("2026-09-04", saved.recordDate)
            assertEquals("鼻窦炎手动记录", saved.title)
            assertEquals(MEMBER_ID, saved.memberId)
            assertEquals("", saved.notes)
        }
    }

    @Test fun clearingQuickInputCannotBypassOriginalTextRequirement() {
        showEditor()
        parse(template("2026-09-04", "鼻窦炎复查"))
        compose.onNodeWithTag("quick-source-repair-single").assertExists()

        compose.onNodeWithTag("quick-entry-input").performScrollTo().performTextReplacement("")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("record-save").assertIsEnabled().performClick()

        compose.onNodeWithText("补充原始病程").assertExists()
        compose.onNodeWithTag("quick-source-input").assertIsDisplayed()
        compose.runOnIdle { assertNoSaves() }
    }

    @Test fun overlongSourceShowsErrorAndDoesNotCloseSupplementOrSave() {
        showEditor()
        parse(template("2026-09-04", "鼻窦炎复查"))
        compose.onNodeWithTag("quick-source-repair-single").performScrollTo().performClick()
        enterSource("原".repeat(ClinicalRecordQuickParser.MAX_NARRATIVE_LENGTH + 1))
        compose.onNodeWithTag("quick-source-confirm").performClick()

        compose.onNodeWithTag("quick-source-error").assertIsDisplayed()
        compose.onNodeWithTag("quick-source-input").assertExists()
        compose.runOnIdle { assertNoSaves() }
    }

    private fun showEditor() {
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                HealthTimelineTheme {
                    RecordEditorDialog(
                        initialDate = "2026-09-04",
                        record = null,
                        memberId = MEMBER_ID,
                        conditions = emptyList(),
                        onSave = { record, _, _ -> savedRecords.add(record) },
                        onSaveBatch = { records, _ -> savedBatches.add(records.toList()) },
                        onDismiss = {}
                    )
                }
            }
        }
    }

    private fun parse(text: String) {
        compose.onNodeWithTag("quick-entry-input").performScrollTo().performTextReplacement(text)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("quick-entry-parse").performScrollTo().performClick()
    }

    private fun enterSource(text: String) {
        compose.onNodeWithTag("quick-source-input").performTextReplacement(text)
        Espresso.closeSoftKeyboard()
    }

    private fun assertNoSaves() {
        assertTrue(savedRecords.isEmpty())
        assertTrue(savedBatches.isEmpty())
    }

    private fun template(date: String, title: String, notes: String = "") = listOf(
        "日期：$date",
        "标题：$title",
        "记录类型：复查",
        "其他备注：$notes"
    ).joinToString("\n")

    private companion object {
        const val MEMBER_ID = 7L
    }
}
