package com.healthtimeline.app.backup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Always rebuild from the authoritative database after an import attempt. */
internal suspend fun <T> withBackupReminderRecovery(
    pause: suspend () -> Unit = {},
    rebuild: suspend () -> Unit,
    onRebuildFailed: (Exception) -> Unit = {},
    operation: suspend () -> T
): T {
    try {
        pause()
        return operation()
    } finally {
        withContext(NonCancellable) {
            try {
                rebuild()
            } catch (failure: Exception) {
                // Reporting cannot replace the original import error or cancellation.
                try { onRebuildFailed(failure) } catch (_: Exception) { }
            }
        }
    }
}
