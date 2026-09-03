package com.healthtimeline.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RepositoryIntegrityTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HealthRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = HealthRepository(database, AttachmentStore(context, database.attachmentDao()))
    }

    @After fun tearDown() { database.close() }

    @Test fun duplicateFollowUpDeliveryDoesNotAdvanceUntilCompletion() = runBlocking {
        val scheduleId = repository.saveFollowUp(
            FollowUpScheduleEntity(
                title = "乳腺复查",
                recurrenceType = RecurrenceType.EVERY_N_MONTHS.name,
                interval = 3,
                anchorDate = "2026-07-09",
                anchorDayOfMonth = 9,
                nextDueDate = "2026-07-09",
                createdAt = NOW,
                updatedAt = NOW
            )
        )
        val due = LocalDate.of(2026, 7, 9)

        repository.processDueFollowUp(scheduleId, due)
        repository.processDueFollowUp(scheduleId, due)

        assertEquals(1, database.followUpDao().allOccurrences().size)
        assertEquals("2026-07-09", database.followUpDao().scheduleById(scheduleId)?.nextDueDate)
        val occurrence = database.followUpDao().allOccurrences().single()
        repository.completeOccurrence(occurrence.id)
        assertEquals("2026-10-09", database.followUpDao().scheduleById(scheduleId)?.nextDueDate)
    }

    @Test fun asNeededDoseCannotBeRecordedTwiceForSameSlot() = runBlocking {
        val medicationId = repository.saveMedication(
            MedicationEntity(
                name = "药物",
                doseAmount = "1",
                doseUnit = "片",
                startDate = "2026-09-01",
                mode = MedicationMode.AS_NEEDED.name,
                createdAt = NOW,
                updatedAt = NOW
            ),
            emptyList()
        ).medicationId
        val scheduledAt = "2026-09-01T00:00"
        assertTrue(repository.markDose(medicationId, null, scheduledAt, MedicationLogStatus.TAKEN))
        assertFalse(repository.markDose(medicationId, null, scheduledAt, MedicationLogStatus.TAKEN))
        assertEquals(1, database.medicationDao().allLogs().size)
    }

    private companion object { const val NOW = "2026-09-01T00:00:00Z" }
}
