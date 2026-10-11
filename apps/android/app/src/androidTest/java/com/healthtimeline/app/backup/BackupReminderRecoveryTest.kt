package com.healthtimeline.app.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthtimeline.app.data.*
import com.healthtimeline.app.reminders.AlarmScheduler
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Real Room suspend queries and Android scheduling calls; not a notification timing test. */
@RunWith(AndroidJUnit4::class)
class BackupReminderRecoveryTest {
    private lateinit var fixture: File
    private lateinit var database: AppDatabase
    private lateinit var repository: HealthRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var member: FamilyMemberEntity
    private lateinit var original: FollowUpScheduleEntity
    private val ownedIds = mutableSetOf<Long>()

    @Before fun setUp() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        fixture = File(base.cacheDir, "reminder-cancel-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val files = File(fixture, "files").apply { check(mkdirs()) }
        val cache = File(fixture, "cache").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = HealthRepository(database, AttachmentStore(context, database.attachmentDao()))
        scheduler = AlarmScheduler(context, repository)
        member = repository.ensureDefaultMember()
        original = followUp(9_000_000_001L, "虚构复查")
        database.followUpDao().insertSchedule(original)
        scheduler.rescheduleAll()
    }

    @After fun tearDown() {
        ownedIds.forEach { scheduler.cancelFollowUp(it) }
        database.close()
        fixture.deleteRecursively()
    }

    @Test fun cancelledImportBeforeCommitStillQueriesRoomAndRebuildsOriginalSchedules() = runBlocking {
        val importing = CompletableDeferred<Unit>()
        var rebuiltIds: List<Long>? = null
        val job = launch(Dispatchers.Default) {
            withBackupReminderRecovery(
                pause = { scheduler.cancelAllPersisted() },
                rebuild = {
                    rebuiltIds = repository.enabledFollowUps().map { it.id }
                    scheduler.rescheduleAll()
                }
            ) { importing.complete(Unit); awaitCancellation() }
        }
        importing.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf(original.id), rebuiltIds)
        assertEquals(listOf(original), database.followUpDao().allSchedules())
    }

    @Test fun cancelledImportAfterRealRoomCommitRebuildsOnlyTheNewIds() = runBlocking {
        val imported = followUp(9_000_000_002L, "虚构导入复查")
        val committed = CompletableDeferred<Unit>()
        var rebuiltIds: List<Long>? = null
        val job = launch(Dispatchers.Default) {
            withBackupReminderRecovery(
                pause = { scheduler.cancelAllPersisted() },
                rebuild = {
                    rebuiltIds = repository.enabledFollowUps().map { it.id }
                    scheduler.rescheduleAll()
                }
            ) {
                database.withTransaction {
                    database.followUpDao().clearSchedules()
                    database.followUpDao().insertSchedule(imported)
                }
                committed.complete(Unit)
                awaitCancellation()
            }
        }
        committed.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf(imported.id), rebuiltIds)
        assertEquals(listOf(imported), database.followUpDao().allSchedules())
    }

    @Test fun cancellationDuringPauseStillReachesRealRoomRebuild() = runBlocking {
        val partiallyPaused = CompletableDeferred<Unit>()
        var rebuiltIds: List<Long>? = null
        var began = false
        val job = launch(Dispatchers.Default) {
            withBackupReminderRecovery(
                pause = {
                    scheduler.cancelFollowUp(original.id)
                    partiallyPaused.complete(Unit)
                    awaitCancellation()
                },
                rebuild = {
                    rebuiltIds = repository.enabledFollowUps().map { it.id }
                    scheduler.rescheduleAll()
                }
            ) { began = true }
        }
        partiallyPaused.await()
        job.cancelAndJoin()
        assertFalse(began)
        assertEquals(listOf(original.id), rebuiltIds)
        assertEquals(listOf(original), database.followUpDao().allSchedules())
    }

    @Test fun baselineCancelledFinallyCannotReadRoomButProtectedFinallyCan() = runBlocking {
        val importing = CompletableDeferred<Unit>()
        var baselineRebuilt = false
        val job = launch(Dispatchers.Default) {
            scheduler.cancelAllPersisted()
            try { importing.complete(Unit); awaitCancellation() }
            finally {
                // Exact old failure mechanism: suspending Room query in a cancelled Job.
                runCatching {
                    repository.enabledFollowUps()
                    scheduler.rescheduleAll()
                    baselineRebuilt = true
                }
            }
        }
        importing.await()
        job.cancelAndJoin()
        assertFalse(baselineRebuilt)
        withBackupReminderRecovery(rebuild = { scheduler.rescheduleAll() }) { }
        assertEquals(listOf(original), database.followUpDao().allSchedules())
    }

    private fun followUp(id: Long, title: String): FollowUpScheduleEntity {
        ownedIds += id
        val due = LocalDate.now().plusDays(10)
        val now = Instant.now().toString()
        return FollowUpScheduleEntity(
            id = id, memberId = member.id, title = title, recurrenceType = "ONCE",
            anchorDate = due.toString(), anchorDayOfMonth = due.dayOfMonth,
            nextDueDate = due.toString(), createdAt = now, updatedAt = now
        )
    }
}
