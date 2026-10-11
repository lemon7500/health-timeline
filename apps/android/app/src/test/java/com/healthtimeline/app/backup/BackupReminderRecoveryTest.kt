package com.healthtimeline.app.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class BackupReminderRecoveryTest {
    @Test fun cancellationDuringPartialPauseStillRebuildsOriginalReminders() = runTest {
        val old = setOf("old-medication", "old-follow-up")
        val reminders = old.toMutableSet()
        val paused = CompletableDeferred<Unit>()
        var operationStarted = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            withBackupReminderRecovery(
                pause = { reminders.remove("old-medication"); paused.complete(Unit); awaitCancellation() },
                rebuild = { yield(); reminders.clear(); reminders.addAll(old) }
            ) { operationStarted = true }
        }
        paused.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(operationStarted)
        assertEquals(old, reminders)
    }

    @Test fun cancellationBeforeCommitRebuildsOriginalDatabaseState() = runTest {
        var database = setOf("original")
        val reminders = database.toMutableSet()
        val importing = CompletableDeferred<Unit>()
        var rebuildCount = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            withBackupReminderRecovery(
                pause = { reminders.clear() },
                rebuild = { yield(); reminders.addAll(database); rebuildCount++ }
            ) { importing.complete(Unit); awaitCancellation() }
        }
        importing.await()
        job.cancelAndJoin()
        assertEquals(setOf("original"), database)
        assertEquals(database, reminders)
        assertEquals(1, rebuildCount)
        assertTrue(job.isCancelled)
    }

    @Test fun cancellationAfterCommitRebuildsNewDatabaseNotOldIds() = runTest {
        var database = setOf("original")
        val reminders = database.toMutableSet()
        val committed = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            withBackupReminderRecovery(
                pause = { reminders.clear() },
                rebuild = { yield(); reminders.clear(); reminders.addAll(database) }
            ) { database = setOf("imported"); committed.complete(Unit); awaitCancellation() }
        }
        committed.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(setOf("imported"), database)
        assertEquals(database, reminders)
    }

    @Test fun legacyImportWithoutPauseAlsoRebuildsAfterCancelledCommit() = runTest {
        var database = setOf("original")
        val reminders = database.toMutableSet()
        val committed = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            withBackupReminderRecovery(rebuild = { yield(); reminders.clear(); reminders.addAll(database) }) {
                database = database + "legacy"
                committed.complete(Unit)
                awaitCancellation()
            }
        }
        committed.await()
        job.cancelAndJoin()
        assertEquals(setOf("original", "legacy"), reminders)
    }

    @Test fun failedPauseStillRebuildsAndPreservesOriginalFailure() = runTest {
        val original = IllegalStateException("synthetic pause failure")
        var rebuilt = false
        val caught = runCatching {
            withBackupReminderRecovery(
                pause = { throw original },
                rebuild = { yield(); rebuilt = true }
            ) { fail("Import must not begin") }
        }.exceptionOrNull()
        assertSame(original, caught)
        assertTrue(rebuilt)
    }

    @Test fun failedRebuildReportsButDoesNotHideSuccessfulCommit() = runTest {
        val failure = IllegalStateException("synthetic scheduling failure")
        var reported: Exception? = null
        val result = withBackupReminderRecovery(
            rebuild = { throw failure },
            onRebuildFailed = { reported = it }
        ) { "committed" }
        assertEquals("committed", result)
        assertSame(failure, reported)
    }

    @Test fun failedRebuildAndWarningCannotMaskOriginalImportFailure() = runTest {
        val original = IllegalArgumentException("synthetic invalid backup")
        val caught = runCatching {
            withBackupReminderRecovery(
                rebuild = { throw IllegalStateException("synthetic alarm error") },
                onRebuildFailed = { throw IllegalStateException("synthetic warning error") }
            ) { throw original }
        }.exceptionOrNull()
        assertSame(original, caught)
    }

    @Test fun successfulImportReturnsValueAndRebuildsExactlyOnce() = runTest {
        val events = mutableListOf<String>()
        val result = withBackupReminderRecovery(
            pause = { events += "pause" },
            rebuild = { yield(); events += "rebuild" }
        ) { events += "commit"; 42 }
        assertEquals(42, result)
        assertEquals(listOf("pause", "commit", "rebuild"), events)
    }
}
