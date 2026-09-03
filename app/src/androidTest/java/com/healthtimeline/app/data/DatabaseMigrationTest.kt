package com.healthtimeline.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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

    private companion object { const val NAME = "migration-test" }
}
