package com.healthtimeline.app.backup

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BackupSecretLifecycleTest {
    @Test fun cancellationBeforeCoroutineStartsStillErasesTheOwnedPassword() = runTest {
        val password = "test-only-secret".toCharArray()
        var entered = false
        val job = launch { entered = true }.eraseBackupPasswordOnCompletion(password)
        job.cancelAndJoin()
        assertFalse(entered)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun completedOperationErasesItsPassword() = runTest {
        val password = "test-only-secret".toCharArray()
        var entered = false
        val job = launch { entered = true }.eraseBackupPasswordOnCompletion(password)
        job.join()
        assertTrue(entered)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun registeringAfterCancellationImmediatelyErasesThePassword() {
        val password = "test-only-secret".toCharArray()
        val job = Job().apply { cancel() }
        assertSame(job, job.eraseBackupPasswordOnCompletion(password))
        assertTrue(password.all { it == '\u0000' })
    }
}
