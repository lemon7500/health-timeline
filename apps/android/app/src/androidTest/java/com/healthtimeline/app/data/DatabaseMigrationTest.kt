package com.healthtimeline.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private companion object { const val NAME = "migration-test" }
}
