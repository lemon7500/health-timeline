package com.healthtimeline.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationFrom1To2PreservesMedicationHistoryAndDeduplicatesNullSchedules() {
        helper.createDatabase(NAME, 1).apply {
            execSQL(
                "INSERT INTO medications(id, conditionId, name, doseAmount, doseUnit, instructions, startDate, endDate, mode, archived, createdAt, updatedAt) " +
                    "VALUES(1, NULL, '药物', '1', '片', '', '2026-09-01', NULL, 'AS_NEEDED', 0, 'now', 'now')"
            )
            repeat(2) { index ->
                execSQL(
                    "INSERT INTO medication_logs(id, medicationId, scheduleId, scheduledAt, actualAt, status, doseAmountSnapshot, doseUnitSnapshot, createdAt) " +
                        "VALUES(${index + 1}, 1, NULL, '2026-09-01T00:00', NULL, 'TAKEN', '1', '片', 'now')"
                )
            }
            close()
        }

        helper.runMigrationsAndValidate(NAME, 2, true, AppDatabase.MIGRATION_1_2).use { database ->
            database.query("SELECT COUNT(*) FROM medication_logs").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test fun migrationFrom2To3AddsStableUniqueUuidsWithoutChangingRows() {
        helper.createDatabase(NAME, 2).apply {
            execSQL("INSERT INTO conditions(id, name, color, notes, archived, createdAt) VALUES(1, '乳腺', 1, '', 0, '2026-09-01T00:00:00Z')")
            execSQL("INSERT INTO conditions(id, name, color, notes, archived, createdAt) VALUES(2, '鼻窦', 2, '', 0, '2026-09-01T00:00:00Z')")
            execSQL(
                "INSERT INTO clinical_records(id, conditionId, recordDate, title, stage, symptoms, diagnosis, treatment, medicationNotes, hospital, clinician, notes, createdAt, updatedAt) " +
                    "VALUES(1, 1, '2026-09-03', '复查', 'CHECKUP', '', '', '', '', '', '', '', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')"
            )
            close()
        }

        helper.runMigrationsAndValidate(NAME, 3, true, AppDatabase.MIGRATION_2_3).use { database ->
            database.query("SELECT id, uuid FROM conditions ORDER BY id").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val first = cursor.getString(1)
                assertEquals(36, first.length)
                assertTrue(cursor.moveToNext())
                val second = cursor.getString(1)
                assertEquals(36, second.length)
                assertNotEquals(first, second)
            }
            database.query("SELECT COUNT(*), LENGTH(uuid) FROM clinical_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(36, cursor.getInt(1))
            }
        }
    }

    @Test fun migrationFrom3To4CreatesDefaultMemberAndPreservesCompleteGraph() {
        helper.createDatabase(NAME, 3).apply {
            execSQL("INSERT INTO conditions VALUES(1, '乳腺', 123456, '分类备注', 0, '2026-09-01T00:00:00Z', '11111111-1111-4111-8111-111111111111')")
            execSQL(
                "INSERT INTO clinical_records VALUES(1, 1, '2026-09-03', '乳腺三个月复查', 'CHECKUP', '疼痛减轻', '恢复正常', " +
                    "'继续观察', '按医嘱服药', '测试医院', '张医生', '三个月后复查', " +
                    "'2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z', '22222222-2222-4222-8222-222222222222')"
            )
            execSQL("INSERT INTO attachments VALUES(1, 1, 'PDF', '报告.pdf', 'application/pdf', 'attachments/1/a.pdf', 4, '${"a".repeat(64)}', '2026-09-01T00:00:00Z', '33333333-3333-4333-8333-333333333333')")
            execSQL(
                "INSERT INTO follow_up_schedules VALUES(1, 1, '三个月复查', 'EVERY_N_MONTHS', 3, '2026-07-09', 9, NULL, '09:00', 0, " +
                    "'2026-10-09', 1, '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', '44444444-4444-4444-8444-444444444444')"
            )
            execSQL("INSERT INTO follow_up_occurrences VALUES(1, 1, '2026-07-09', 'DONE', '2026-07-09T01:00:00Z', '2026-07-09T00:00:00Z', '55555555-5555-4555-8555-555555555555')")
            execSQL(
                "INSERT INTO medications VALUES(1, 1, '测试药物', '1.5', '片', '饭后服用', '2026-09-01', NULL, 'SCHEDULED', 0, " +
                    "'2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z', '66666666-6666-4666-8666-666666666666')"
            )
            execSQL("INSERT INTO medication_schedules VALUES(1, 1, '08:05', 1, '77777777-7777-4777-8777-777777777777')")
            execSQL("INSERT INTO medication_logs VALUES(1, 1, 1, '2026-09-03T08:05', '2026-09-03T08:06', 'TAKEN', '1.5', '片', '2026-09-03T00:00:00Z', '88888888-8888-4888-8888-888888888888')")
            close()
        }

        helper.runMigrationsAndValidate(NAME, 4, true, AppDatabase.MIGRATION_3_4).use { database ->
            database.query("SELECT id, name, nickname, relationship, archived FROM family_members").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals("本人", cursor.getString(1))
                assertEquals("本人", cursor.getString(2))
                assertEquals("本人", cursor.getString(3))
                assertEquals(0, cursor.getInt(4))
            }
            listOf("conditions", "clinical_records", "follow_up_schedules", "medications").forEach { table ->
                database.query("SELECT COUNT(*), MIN(memberId), MAX(memberId) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                    assertEquals(1L, cursor.getLong(1))
                    assertEquals(1L, cursor.getLong(2))
                }
            }
            listOf("attachments", "follow_up_occurrences", "medication_schedules", "medication_logs").forEach { table ->
                database.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            }
            database.query("SELECT id,name,color,notes,archived,createdAt,uuid,memberId FROM conditions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals("乳腺", cursor.getString(1))
                assertEquals(123456, cursor.getInt(2))
                assertEquals("分类备注", cursor.getString(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals("2026-09-01T00:00:00Z", cursor.getString(5))
                assertEquals("11111111-1111-4111-8111-111111111111", cursor.getString(6))
                assertEquals(1L, cursor.getLong(7))
            }
            database.query(
                "SELECT id,conditionId,recordDate,title,stage,symptoms,diagnosis,treatment,medicationNotes,hospital,clinician,notes," +
                    "createdAt,updatedAt,uuid,memberId FROM clinical_records"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals("2026-09-03", cursor.getString(2))
                assertEquals("乳腺三个月复查", cursor.getString(3))
                assertEquals("CHECKUP", cursor.getString(4))
                assertEquals("疼痛减轻", cursor.getString(5))
                assertEquals("恢复正常", cursor.getString(6))
                assertEquals("继续观察", cursor.getString(7))
                assertEquals("按医嘱服药", cursor.getString(8))
                assertEquals("测试医院", cursor.getString(9))
                assertEquals("张医生", cursor.getString(10))
                assertEquals("三个月后复查", cursor.getString(11))
                assertEquals("2026-09-01T00:00:00Z", cursor.getString(12))
                assertEquals("2026-09-02T00:00:00Z", cursor.getString(13))
                assertEquals("22222222-2222-4222-8222-222222222222", cursor.getString(14))
                assertEquals(1L, cursor.getLong(15))
            }
            database.query("SELECT id,recordId,kind,displayName,mimeType,relativePath,sizeBytes,sha256,createdAt,uuid FROM attachments").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals("PDF", cursor.getString(2))
                assertEquals("报告.pdf", cursor.getString(3))
                assertEquals("application/pdf", cursor.getString(4))
                assertEquals("attachments/1/a.pdf", cursor.getString(5))
                assertEquals(4L, cursor.getLong(6))
                assertEquals("a".repeat(64), cursor.getString(7))
                assertEquals("2026-09-01T00:00:00Z", cursor.getString(8))
                assertEquals("33333333-3333-4333-8333-333333333333", cursor.getString(9))
            }
            database.query(
                "SELECT id,conditionId,title,recurrenceType,interval,anchorDate,anchorDayOfMonth,weekday,reminderTime,leadDays," +
                    "nextDueDate,enabled,createdAt,updatedAt,uuid,memberId FROM follow_up_schedules"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals("三个月复查", cursor.getString(2))
                assertEquals("EVERY_N_MONTHS", cursor.getString(3))
                assertEquals(3, cursor.getInt(4))
                assertEquals("2026-07-09", cursor.getString(5))
                assertEquals(9, cursor.getInt(6))
                assertTrue(cursor.isNull(7))
                assertEquals("09:00", cursor.getString(8))
                assertEquals(0, cursor.getInt(9))
                assertEquals("2026-10-09", cursor.getString(10))
                assertEquals(1, cursor.getInt(11))
                assertEquals("2026-09-01T00:00:00Z", cursor.getString(12))
                assertEquals("2026-09-01T00:00:00Z", cursor.getString(13))
                assertEquals("44444444-4444-4444-8444-444444444444", cursor.getString(14))
                assertEquals(1L, cursor.getLong(15))
            }
            database.query("SELECT id,scheduleId,dueDate,status,completedAt,createdAt,uuid FROM follow_up_occurrences").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals("2026-07-09", cursor.getString(2))
                assertEquals("DONE", cursor.getString(3))
                assertEquals("2026-07-09T01:00:00Z", cursor.getString(4))
                assertEquals("2026-07-09T00:00:00Z", cursor.getString(5))
                assertEquals("55555555-5555-4555-8555-555555555555", cursor.getString(6))
            }
            database.query(
                "SELECT id,conditionId,name,doseAmount,doseUnit,instructions,startDate,endDate,mode,archived,createdAt,updatedAt,uuid,memberId " +
                    "FROM medications"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals("测试药物", cursor.getString(2))
                assertEquals("1.5", cursor.getString(3))
                assertEquals("片", cursor.getString(4))
                assertEquals("饭后服用", cursor.getString(5))
                assertEquals("2026-09-01", cursor.getString(6))
                assertNull(cursor.getString(7))
                assertEquals("SCHEDULED", cursor.getString(8))
                assertEquals(0, cursor.getInt(9))
                assertEquals("2026-09-01T00:00:00Z", cursor.getString(10))
                assertEquals("2026-09-02T00:00:00Z", cursor.getString(11))
                assertEquals("66666666-6666-4666-8666-666666666666", cursor.getString(12))
                assertEquals(1L, cursor.getLong(13))
            }
            database.query("SELECT id,medicationId,localTime,enabled,uuid FROM medication_schedules").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals("08:05", cursor.getString(2))
                assertEquals(1, cursor.getInt(3))
                assertEquals("77777777-7777-4777-8777-777777777777", cursor.getString(4))
            }
            database.query(
                "SELECT id,medicationId,scheduleId,scheduledAt,actualAt,status,doseAmountSnapshot,doseUnitSnapshot,createdAt,uuid " +
                    "FROM medication_logs"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals(1L, cursor.getLong(2))
                assertEquals("2026-09-03T08:05", cursor.getString(3))
                assertEquals("2026-09-03T08:06", cursor.getString(4))
                assertEquals("TAKEN", cursor.getString(5))
                assertEquals("1.5", cursor.getString(6))
                assertEquals("片", cursor.getString(7))
                assertEquals("2026-09-03T00:00:00Z", cursor.getString(8))
                assertEquals("88888888-8888-4888-8888-888888888888", cursor.getString(9))
            }
            database.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
        }
    }

    private companion object { const val NAME = "migration-test" }
}
