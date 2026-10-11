package com.healthtimeline.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthtimeline.app.backup.BackupSnapshot
import com.healthtimeline.app.backup.PortableSnapshotMapper
import com.healthtimeline.shared.PortableJson
import com.healthtimeline.shared.PortableSnapshotValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

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
            database.query("SELECT COUNT(*) FROM medications").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            database.query("SELECT id FROM medication_logs").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
        }
    }

    @Test fun migrationFrom1To2ArchivesDifferentLogsWithoutChangingTheirMedicalFacts() {
        val expected = createLegacyMedicationConflicts()
        helper.runMigrationsAndValidate(NAME, 2, true, AppDatabase.MIGRATION_1_2).use { database ->
            assertLegacyLogFacts(database, expected)
            database.query("SELECT name,instructions,archived,updatedAt FROM medications WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("药物", cursor.getString(0))
                assertEquals("原服用说明", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(LEGACY_CREATED_AT, cursor.getString(3))
            }
            database.query("SELECT name,instructions,archived FROM medications WHERE id<>1").use { cursor ->
                assertEquals(expected.size - 1, cursor.count)
                while (cursor.moveToNext()) {
                    assertTrue(isLegacyMedicationHistoryCopy(cursor.getString(0), cursor.getString(1)))
                    assertTrue(cursor.getString(1).contains("不是新医嘱"))
                    assertTrue(cursor.getString(1).contains("实际停药时间未知"))
                    assertEquals(1, cursor.getInt(2))
                }
            }
            database.query("SELECT COUNT(*) FROM medication_schedules WHERE medicationId<>1 AND enabled<>0").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM medication_logs l JOIN medication_schedules s ON s.id=l.scheduleId WHERE l.medicationId<>s.medicationId").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            val medicationCount = queryCount(database, "medications")
            val scheduleCount = queryCount(database, "medication_schedules")
            // A retry cannot create extra history copies or change the surviving IDs.
            AppDatabase.MIGRATION_1_2.migrate(database)
            assertLegacyLogFacts(database, expected)
            assertEquals(medicationCount, queryCount(database, "medications"))
            assertEquals(scheduleCount, queryCount(database, "medication_schedules"))
            database.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
        }
    }

    @Test fun legacyConflictHistorySurvivesFullMigrationCalendarAndBackupRoundTrip() = runBlocking {
        val fullName = "药".repeat(100)
        val fullInstructions = "说明".repeat(2_500)
        val expected = createLegacyMedicationConflicts(fullName, fullInstructions)
        helper.runMigrationsAndValidate(
            NAME, 8, true,
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8
        ).use { database ->
            assertLegacyLogFacts(database, expected)
            database.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
        try {
            val medications = database.medicationDao().allMedications()
            val schedules = database.medicationDao().allSchedules()
            val logs = database.medicationDao().allLogs()
            val original = medications.single { it.id == 1L }
            assertEquals(fullName, original.name)
            assertEquals(fullInstructions, original.instructions)
            assertEquals("2026-09-01", original.startDate)
            assertNull(original.endDate)
            assertFalse(original.archived)
            assertEquals(LEGACY_CREATED_AT, original.updatedAt)
            val copies = medications.filter(::isLegacyMedicationHistoryCopy)
            assertEquals(expected.size - 1, copies.size)
            assertTrue(copies.all { it.archived && it.endDate == null && it.endedAt == null && it.name.length <= 100 })
            assertTrue(copies.all { it.instructions.contains(fullName) })
            assertTrue(copies.all { it.memberId == original.memberId })
            assertTrue(logs.all { it.uuid.length == 36 })
            assertEquals(logs.size, logs.map { it.uuid }.distinct().size)
            assertEquals(logs.size, logs.map { it.medicationId to it.scheduledAt }.distinct().size)
            val copyIds = copies.map { it.id }.toSet()
            assertTrue(database.medicationDao().enabledSchedules("2026-09-01").none { it.medicationId in copyIds })
            val month = projectMedicationMonth(YearMonth.of(2026, 9), medications, schedules, logs)
            val historyEntries = month.entriesByDate.values.flatten().filter { it.medication.id in copyIds }
            assertEquals(copies.size, historyEntries.size)
            assertTrue(historyEntries.all { it.log != null && it.status != MedicationDayStatus.UNRECORDED })
            assertEquals(expected.filter { it.id != 1L }.map { it.id }.toSet(), historyEntries.map { it.log!!.id }.toSet())
            assertTrue(month.entries(LocalDate.of(2026, 9, 2)).none { it.medication.id in copyIds })
            val repository = HealthRepository(database, AttachmentStore(context, database.attachmentDao()))
            val restoreFailure = runCatching { repository.restoreMedication(copies.first().id) }.exceptionOrNull()
            assertTrue(restoreFailure is IllegalArgumentException)
            assertTrue(database.medicationDao().medicationById(copies.first().id)!!.archived)
            val snapshot = BackupSnapshot(
                conditions = emptyList(), records = emptyList(), attachments = emptyList(),
                followUps = emptyList(), occurrences = emptyList(), medications = medications,
                medicationSchedules = schedules, medicationLogs = logs,
                members = database.familyMemberDao().all()
            )
            val portable = PortableSnapshotMapper.toPortable(snapshot, "00000000-0000-4000-8000-000000000001", LEGACY_CREATED_AT)
            val decoded = PortableJson.decode(PortableJson.encode(portable))
            PortableSnapshotValidator.validate(decoded)
            val restored = PortableSnapshotMapper.toEntities(decoded, snapshot) { _, _ -> error("No attachments in fixture") }
            assertEquals(snapshot.medicationLogs, restored.medicationLogs)
            assertEquals(snapshot.medications, restored.medications)
            assertEquals(snapshot.medicationSchedules, restored.medicationSchedules)
            assertTrue(restored.medications.filter(::isLegacyMedicationHistoryCopy).all { it.endDate == null && it.endedAt == null })
        } finally { database.close() }
    }

    @Test fun interruptedLegacyHistoryCopyRollsBackDeduplicationAndEarlierCopies() {
        val expected = createLegacyMedicationConflicts()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = context.getDatabasePath(NAME).absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL(
                "CREATE TRIGGER stop_second_history_copy BEFORE INSERT ON medications " +
                    "WHEN NEW.archived=1 AND (SELECT COUNT(*) FROM medications WHERE archived=1)>=1 " +
                    "BEGIN SELECT RAISE(ABORT, 'simulated interrupted legacy migration'); END"
            )
        }
        val failure = runCatching {
            helper.runMigrationsAndValidate(NAME, 2, true, AppDatabase.MIGRATION_1_2).close()
        }.exceptionOrNull()
        assertTrue(failure != null)
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            database.rawQuery("SELECT COUNT(*) FROM medications", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            database.rawQuery("SELECT COUNT(*) FROM medication_schedules", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            // Even the completely identical duplicate must survive a failed transaction.
            val originalLogs = (expected + expected.first().copy(id = 2L)).sortedBy { it.id }
            database.rawQuery(
                "SELECT id,medicationId,scheduleId,scheduledAt,actualAt,status,doseAmountSnapshot,doseUnitSnapshot,createdAt " +
                    "FROM medication_logs ORDER BY id", null
            ).use { cursor ->
                assertEquals(originalLogs.size, cursor.count)
                originalLogs.forEach { log ->
                    assertTrue(cursor.moveToNext())
                    assertEquals(log.id, cursor.getLong(0))
                    assertEquals(1L, cursor.getLong(1))
                    if (log.scheduleId == null) assertTrue(cursor.isNull(2))
                    else assertEquals(log.scheduleId, cursor.getLong(2))
                    assertEquals(log.scheduledAt, cursor.getString(3))
                    assertEquals(log.actualAt, cursor.getString(4))
                    assertEquals(log.status, cursor.getString(5))
                    assertEquals(log.doseAmount, cursor.getString(6))
                    assertEquals(log.doseUnit, cursor.getString(7))
                    assertEquals(log.createdAt, cursor.getString(8))
                }
            }
        }
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL("DROP TRIGGER stop_second_history_copy")
        }
        helper.runMigrationsAndValidate(NAME, 2, true, AppDatabase.MIGRATION_1_2).use { database ->
            assertLegacyLogFacts(database, expected)
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

    @Test fun migrationFrom4To5PreservesRowsAndCreatesMedicationHistory() {
        helper.createDatabase(NAME, 4).apply {
            execSQL(
                "INSERT INTO family_members(id,name,nickname,relationship,archived,createdAt,updatedAt,uuid) " +
                    "VALUES(1,'张女士','妈妈','母亲',0,'2026-09-01T00:00:00Z','2026-09-01T00:00:00Z','10000000-0000-4000-8000-000000000001')"
            )
            execSQL(
                "INSERT INTO clinical_records(id,conditionId,recordDate,title,stage,symptoms,diagnosis,treatment,medicationNotes,hospital,clinician,notes,createdAt,updatedAt,uuid,memberId) " +
                    "VALUES(1,NULL,'2026-09-03','较早记录','OTHER','','','','','','','','2026-09-01T00:00:00Z','2026-09-01T00:00:00Z','20000000-0000-4000-8000-000000000001',1)"
            )
            execSQL(
                "INSERT INTO clinical_records(id,conditionId,recordDate,title,stage,symptoms,diagnosis,treatment,medicationNotes,hospital,clinician,notes,createdAt,updatedAt,uuid,memberId) " +
                    "VALUES(2,NULL,'2026-09-03','最新记录','OTHER','','','','','','','','2026-09-02T00:00:00Z','2026-09-02T00:00:00Z','20000000-0000-4000-8000-000000000002',1)"
            )
            execSQL(
                "INSERT INTO medications(id,conditionId,name,doseAmount,doseUnit,instructions,startDate,endDate,mode,archived,createdAt,updatedAt,uuid,memberId) " +
                    "VALUES(1,NULL,'测试药物','1.5','片','饭后','2026-09-01','2026-09-30','SCHEDULED',0,'2026-09-01T00:00:00Z','2026-09-02T00:00:00Z','30000000-0000-4000-8000-000000000001',1)"
            )
            execSQL(
                "INSERT INTO medication_schedules(id,medicationId,localTime,enabled,uuid) " +
                    "VALUES(1,1,'08:05',1,'40000000-0000-4000-8000-000000000001')"
            )
            execSQL(
                "INSERT INTO medication_logs(id,medicationId,scheduleId,scheduledAt,actualAt,status,doseAmountSnapshot,doseUnitSnapshot,createdAt,uuid) " +
                    "VALUES(1,1,1,'2026-09-03T08:05','2026-09-03T08:10','TAKEN','1.5','片','2026-09-03T00:00:00Z','50000000-0000-4000-8000-000000000001')"
            )
            close()
        }

        helper.runMigrationsAndValidate(NAME, 5, true, AppDatabase.MIGRATION_4_5).use { database ->
            database.query("SELECT title,dayOrder FROM clinical_records ORDER BY dayOrder").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("最新记录", cursor.getString(0))
                assertEquals(0L, cursor.getLong(1))
                assertTrue(cursor.moveToNext())
                assertEquals("较早记录", cursor.getString(0))
                assertEquals(1L, cursor.getLong(1))
            }
            database.query(
                "SELECT effectiveFrom,effectiveTo,doseAmountSnapshot,doseUnitSnapshot,updatedAt FROM medication_schedules"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("2026-09-01", cursor.getString(0))
                assertEquals("2026-09-30", cursor.getString(1))
                assertEquals("1.5", cursor.getString(2))
                assertEquals("片", cursor.getString(3))
                assertEquals("2026-09-02T00:00:00Z", cursor.getString(4))
            }
            database.query("SELECT updatedAt FROM medication_logs").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("2026-09-03T00:00:00Z", cursor.getString(0))
            }
            database.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
        }
    }

    @Test fun migrationFrom5To6PreservesRowsAndRecordsArchivedCourseEnd() {
        helper.createDatabase(NAME, 5).apply {
            execSQL(
                "INSERT INTO family_members(id,name,nickname,relationship,archived,createdAt,updatedAt,uuid) " +
                    "VALUES(1,'张女士','母亲','母亲',0,'2026-09-01T00:00:00Z','2026-09-01T00:00:00Z','10000000-0000-4000-8000-000000000001')"
            )
            execSQL(
                "INSERT INTO medications(id,conditionId,name,doseAmount,doseUnit,instructions,startDate,endDate,mode,archived,createdAt,updatedAt,uuid,memberId) " +
                    "VALUES(1,NULL,'已结束药物','1','片','','2026-09-01',NULL,'SCHEDULED',1,'2026-09-01T00:00:00Z','2026-09-08T03:04:05Z','30000000-0000-4000-8000-000000000001',1)"
            )
            execSQL(
                "INSERT INTO medications(id,conditionId,name,doseAmount,doseUnit,instructions,startDate,endDate,mode,archived,createdAt,updatedAt,uuid,memberId) " +
                    "VALUES(2,NULL,'进行中药物','2','粒','','2026-09-02',NULL,'SCHEDULED',0,'2026-09-02T00:00:00Z','2026-09-02T00:00:00Z','30000000-0000-4000-8000-000000000002',1)"
            )
            execSQL(
                "INSERT INTO medications(id,conditionId,name,doseAmount,doseUnit,instructions,startDate,endDate,mode,archived,createdAt,updatedAt,uuid,memberId) " +
                    "VALUES(3,NULL,?,'1','片','普通用户填写的说明','2026-09-01',NULL,'SCHEDULED',1,'2026-09-01T00:00:00Z','2026-09-08T03:04:05Z','30000000-0000-4000-8000-000000000003',1)",
                arrayOf<Any>(LEGACY_MEDICATION_HISTORY_NAME_PREFIX + "用户自行命名")
            )
            close()
        }

        helper.runMigrationsAndValidate(NAME, 6, true, AppDatabase.MIGRATION_5_6).use { database ->
            database.query("SELECT endDate,endedAt FROM medications WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("2026-09-08", cursor.getString(0))
                assertEquals("2026-09-08T03:04:05Z", cursor.getString(1))
            }
            database.query("SELECT endDate,endedAt FROM medications WHERE id=2").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
            database.query("SELECT COUNT(*) FROM medications").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3L, cursor.getLong(0))
            }
            // A matching user-entered name alone is not the reserved migration marker.
            database.query("SELECT endDate,endedAt FROM medications WHERE id=3").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("2026-09-08", cursor.getString(0))
                assertEquals("2026-09-08T03:04:05Z", cursor.getString(1))
            }
            database.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
        }
    }

    @Test fun migrationFrom6To7MakesPreviouslyEndedCourseRecoverable() {
        helper.createDatabase(NAME, 6).apply {
            execSQL(
                "INSERT INTO family_members(id,name,nickname,relationship,archived,createdAt,updatedAt,uuid) " +
                    "VALUES(1,'张女士','母亲','母亲',0,'2026-09-01T00:00:00Z','2026-09-01T00:00:00Z','10000000-0000-4000-8000-000000000001')"
            )
            execSQL(
                "INSERT INTO medications(id,conditionId,name,doseAmount,doseUnit,instructions,startDate,endDate,mode,archived,createdAt,updatedAt,uuid,memberId,endedAt) " +
                    "VALUES(1,NULL,'已结束药物','1','片','','2026-09-01','2026-09-08','SCHEDULED',1,'2026-09-01T00:00:00Z','2026-09-08T03:04:05Z','30000000-0000-4000-8000-000000000001',1,'2026-09-08T03:04:05Z')"
            )
            execSQL(
                "INSERT INTO medication_schedules(id,medicationId,localTime,enabled,uuid,effectiveFrom,effectiveTo,doseAmountSnapshot,doseUnitSnapshot,updatedAt) " +
                    "VALUES(1,1,'08:00',0,'40000000-0000-4000-8000-000000000001','2026-09-01','2026-09-08','1','片','2026-09-08T03:04:05Z')"
            )
            execSQL(
                "INSERT INTO medication_schedules(id,medicationId,localTime,enabled,uuid,effectiveFrom,effectiveTo,doseAmountSnapshot,doseUnitSnapshot,updatedAt) " +
                    "VALUES(2,1,'07:30',0,'40000000-0000-4000-8000-000000000002','2026-08-01','2026-09-08','0.5','片','2026-08-01T03:04:05Z')"
            )
            close()
        }

        helper.runMigrationsAndValidate(NAME, 7, true, AppDatabase.MIGRATION_6_7).use { database ->
            database.query("SELECT archivedPreviousEndDate FROM medications WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            database.query("SELECT enabled,effectiveTo,pausedByCourseEnd FROM medication_schedules WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertTrue(cursor.isNull(1))
                assertEquals(1, cursor.getInt(2))
            }
            database.query("SELECT enabled,effectiveTo,pausedByCourseEnd FROM medication_schedules WHERE id=2").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals("2026-09-08", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
            }
            database.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
        }
    }

    @Test fun migrationFrom7To8AddsEmptyTrashMarkersWithoutChangingData() {
        helper.createDatabase(NAME, 7).apply {
            execSQL(
                "INSERT INTO family_members(id,name,nickname,relationship,archived,createdAt,updatedAt,uuid) " +
                    "VALUES(1,'张女士','母亲','母亲',0,'2026-09-01T00:00:00Z','2026-09-01T00:00:00Z','10000000-0000-4000-8000-000000000001')"
            )
            execSQL(
                "INSERT INTO clinical_records(id,conditionId,recordDate,title,stage,symptoms,diagnosis,treatment,medicationNotes,hospital,clinician,notes,createdAt,updatedAt,uuid,memberId,dayOrder) " +
                    "VALUES(1,NULL,'2026-09-03','迁移病历','OTHER','','','','','','','','2026-09-01T00:00:00Z','2026-09-02T00:00:00Z','20000000-0000-4000-8000-000000000001',1,0)"
            )
            execSQL(
                "INSERT INTO attachments(id,recordId,kind,displayName,mimeType,relativePath,sizeBytes,sha256,createdAt,uuid) " +
                    "VALUES(1,1,'PDF','报告.pdf','application/pdf','attachments/1/a.pdf',4,'${"a".repeat(64)}','2026-09-01T00:00:00Z','30000000-0000-4000-8000-000000000001')"
            )
            execSQL(
                "INSERT INTO follow_up_schedules(id,conditionId,title,recurrenceType,interval,anchorDate,anchorDayOfMonth,weekday,reminderTime,leadDays,nextDueDate,enabled,createdAt,updatedAt,uuid,memberId) " +
                    "VALUES(1,NULL,'迁移复查','ONCE',1,'2026-10-01',1,NULL,'09:00',3,'2026-10-01',1,'2026-09-01T00:00:00Z','2026-09-01T00:00:00Z','40000000-0000-4000-8000-000000000001',1)"
            )
            execSQL(
                "INSERT INTO medications(id,conditionId,name,doseAmount,doseUnit,instructions,startDate,endDate,mode,archived,createdAt,updatedAt,uuid,memberId,endedAt,archivedPreviousEndDate) " +
                    "VALUES(1,NULL,'迁移药物','1','片','','2026-09-01',NULL,'AS_NEEDED',0,'2026-09-01T00:00:00Z','2026-09-01T00:00:00Z','50000000-0000-4000-8000-000000000001',1,NULL,NULL)"
            )
            close()
        }

        helper.runMigrationsAndValidate(NAME, 8, true, AppDatabase.MIGRATION_7_8).use { database ->
            listOf("clinical_records", "attachments", "follow_up_schedules", "medications").forEach { table ->
                database.query("SELECT COUNT(*), COUNT(deletedAt) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                    assertEquals(0L, cursor.getLong(1))
                }
            }
            database.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
        }
    }

    @Test fun allMigrationsFrom1To8ValidateOnAnEmptyDatabase() {
        helper.createDatabase(NAME, 1).close()
        helper.runMigrationsAndValidate(
            NAME,
            8,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8
        ).close()
    }

    private data class LegacyMigrationLog(
        val id: Long,
        val scheduleId: Long? = null,
        val scheduledAt: String = "2026-09-01T00:00",
        val actualAt: String? = "2026-09-01T08:00",
        val status: String = "TAKEN",
        val doseAmount: String = "1",
        val doseUnit: String = "片",
        val createdAt: String = LEGACY_CREATED_AT
    )

    private fun createLegacyMedicationConflicts(
        medicationName: String = "药物",
        instructions: String = "原服用说明"
    ): List<LegacyMigrationLog> {
        val first = LegacyMigrationLog(1L)
        val expected = listOf(
            first,
            first.copy(id = 3L, actualAt = "2026-09-01T20:00"),
            first.copy(id = 4L, actualAt = null, status = "SKIPPED"),
            first.copy(id = 5L, doseAmount = "2"),
            first.copy(id = 6L, doseUnit = "粒"),
            first.copy(id = 7L, createdAt = "2026-09-01T01:00:00Z"),
            first.copy(id = 8L, scheduleId = 1L),
            first.copy(id = 9L, scheduleId = 2L)
        )
        helper.createDatabase(NAME, 1).apply {
            execSQL(
                "INSERT INTO medications(id,conditionId,name,doseAmount,doseUnit,instructions,startDate,endDate,mode,archived,createdAt,updatedAt) " +
                    "VALUES(1,NULL,?,'1','片',?,'2026-09-01',NULL,'SCHEDULED',0,?,?)",
                arrayOf<Any>(medicationName, instructions, LEGACY_CREATED_AT, LEGACY_CREATED_AT)
            )
            execSQL("INSERT INTO medication_schedules(id,medicationId,localTime,enabled) VALUES(1,1,'08:00',1)")
            execSQL("INSERT INTO medication_schedules(id,medicationId,localTime,enabled) VALUES(2,1,'20:00',1)")
            (expected + first.copy(id = 2L)).forEach { log ->
                execSQL(
                    "INSERT INTO medication_logs(id,medicationId,scheduleId,scheduledAt,actualAt,status,doseAmountSnapshot,doseUnitSnapshot,createdAt) " +
                        "VALUES(?,1,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(log.id, log.scheduleId, log.scheduledAt, log.actualAt, log.status,
                        log.doseAmount, log.doseUnit, log.createdAt)
                )
            }
            close()
        }
        return expected
    }

    private fun assertLegacyLogFacts(database: SupportSQLiteDatabase, expected: List<LegacyMigrationLog>) {
        database.query(
            "SELECT id,medicationId,scheduleId,scheduledAt,actualAt,status,doseAmountSnapshot,doseUnitSnapshot,createdAt " +
                "FROM medication_logs ORDER BY id"
        ).use { cursor ->
            assertEquals(expected.size, cursor.count)
            expected.forEach { log ->
                assertTrue(cursor.moveToNext())
                assertEquals(log.id, cursor.getLong(0))
                assertEquals(log.scheduledAt, cursor.getString(3))
                assertEquals(log.actualAt, cursor.getString(4))
                assertEquals(log.status, cursor.getString(5))
                assertEquals(log.doseAmount, cursor.getString(6))
                assertEquals(log.doseUnit, cursor.getString(7))
                assertEquals(log.createdAt, cursor.getString(8))
                if (log.id == 1L) {
                    assertEquals(1L, cursor.getLong(1))
                    assertTrue(cursor.isNull(2))
                } else {
                    assertTrue(cursor.getLong(1) > 1L)
                    if (log.scheduleId == null) assertTrue(cursor.isNull(2))
                    else assertTrue(cursor.getLong(2) > 2L)
                }
            }
        }
    }

    private fun queryCount(database: SupportSQLiteDatabase, table: String): Long =
        database.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private companion object {
        const val NAME = "migration-test"
        const val LEGACY_CREATED_AT = "2026-09-01T00:00:00Z"
    }
}
