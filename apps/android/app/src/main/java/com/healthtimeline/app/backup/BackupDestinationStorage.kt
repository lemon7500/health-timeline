package com.healthtimeline.app.backup

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import kotlinx.coroutines.CancellationException

internal object BackupDestinationStorage {
    /** Pipe-backed document providers have no useful target filesystem capacity to report. */
    fun availableBytes(descriptor: FileDescriptor): Long? = try {
        if (!OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) null
        else Os.fstatvfs(descriptor).let { safeAvailableBytes(it.f_bavail, it.f_frsize) }
    } catch (error: Exception) { null }

    fun safeAvailableBytes(availableBlocks: Long, fragmentSize: Long): Long? {
        if (availableBlocks < 0 || fragmentSize <= 0) return null
        return try { Math.multiplyExact(availableBlocks, fragmentSize) }
        catch (error: ArithmeticException) { null }
    }

    fun budget(
        descriptor: FileDescriptor,
        targetAvailableBytes: (FileDescriptor) -> Long?,
        fallbackAvailableBytes: () -> Long,
        checkpoint: () -> Unit
    ): BackupResourceBudget = BackupResourceBudget({
        val measured = try { targetAvailableBytes(descriptor) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { null }
        // A measured full filesystem is not "unknown": zero must stop the write.
        measured?.takeIf { it >= 0 } ?: fallbackAvailableBytes()
    }, checkpoint)
}
