package com.healthtimeline.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FollowUpDeletionEffectsTest {
    @Test fun `database deletion failures never cancel or rebuild existing reminders`() = runTest {
        for (failure in listOf(IOException("database write failed"), IllegalArgumentException("stale UUID"))) {
            val events = mutableListOf<String>()
            var alarmPresent = true
            var dataPresent = true
            val result = runCatching {
                commitFollowUpDeletionThenRefresh(
                    deleteData = { events += "delete"; throw failure },
                    cancelReminder = { alarmPresent = false; events += "cancel" },
                    rebuildReminders = { events += "rebuild"; dataPresent = false }
                )
            }
            assertEquals(failure, result.exceptionOrNull())
            assertEquals(listOf("delete"), events)
            assertTrue(dataPresent)
            assertTrue(alarmPresent)
        }
    }

    @Test fun `successful deletion commits before cancel and authoritative reminder rebuild`() = runTest {
        val events = mutableListOf<String>()
        var dataPresent = true
        var alarmPresent = true
        val result = commitFollowUpDeletionThenRefresh(
            deleteData = { dataPresent = false; events += "delete" },
            cancelReminder = { assertFalse(dataPresent); alarmPresent = false; events += "cancel" },
            rebuildReminders = { assertFalse(dataPresent); assertFalse(alarmPresent); events += "rebuild" }
        )
        assertTrue(result)
        assertEquals(listOf("delete", "cancel", "rebuild"), events)
    }

    @Test fun `failed alarm cancellation still rebuilds after the committed deletion`() = runTest {
        val events = mutableListOf<String>()
        val result = commitFollowUpDeletionThenRefresh(
            deleteData = { events += "delete" },
            cancelReminder = { events += "cancel"; throw IllegalStateException("alarm service failure") },
            rebuildReminders = { events += "rebuild" }
        )
        assertFalse(result)
        assertEquals(listOf("delete", "cancel", "rebuild"), events)
    }

    @Test fun `failed reminder rebuild reports partial reminder failure without undoing deletion`() = runTest {
        var committed = false
        var cancelled = false
        val result = commitFollowUpDeletionThenRefresh(
            deleteData = { committed = true },
            cancelReminder = { cancelled = true },
            rebuildReminders = { throw IOException("alarm rebuild failed") }
        )
        assertFalse(result)
        assertTrue(committed)
        assertTrue(cancelled)
    }

    @Test fun `coroutine cancellation is propagated without reporting success`() = runTest {
        val cancellation = CancellationException("cancelled")
        var rebuilt = false
        val result = runCatching {
            commitFollowUpDeletionThenRefresh(
                deleteData = {},
                cancelReminder = { throw cancellation },
                rebuildReminders = { rebuilt = true }
            )
        }
        assertEquals(cancellation, result.exceptionOrNull())
        assertFalse(rebuilt)
    }
}
