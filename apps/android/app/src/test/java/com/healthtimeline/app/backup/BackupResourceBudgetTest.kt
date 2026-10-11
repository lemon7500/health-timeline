package com.healthtimeline.app.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BackupResourceBudgetTest {
    @Test fun `unknown length input stops at its storage budget`() {
        var reads = 0
        val input = object : InputStream() {
            override fun available() = 0
            override fun read(): Int { reads++; return 1 }
        }
        val budget = BackupResourceBudget({ 100L }, reserveBytes = 0)
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { budget.copy(input, output, 100) }
        assertEquals(101, reads)
        assertTrue(output.size() <= 100)
    }

    @Test fun `space loss stops writes while keeping the reserve`() {
        var available = 100L
        val bytes = ByteArrayOutputStream()
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { bytes.write(value); available-- }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes.write(buffer, offset, length); available -= length }
        }
        val budget = BackupResourceBudget({ available }, reserveBytes = 20)
        val limited = budget.limitOutput(output)
        limited.write(ByteArray(64))
        assertThrows(IllegalArgumentException::class.java) { limited.write(ByteArray(32)) }
        assertEquals(64, bytes.size())
        assertEquals(36L, available)
    }

    @Test fun `cancellation propagates unchanged before further reads`() {
        val cancellation = CancellationException("synthetic cancellation")
        var checkpoints = 0
        val budget = BackupResourceBudget({ Long.MAX_VALUE / 2 }, {
            if (++checkpoints == 2) throw cancellation
        }, reserveBytes = 0)
        var readBytes = 0
        val input = object : InputStream() {
            override fun read(): Int = error("block reads expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { readBytes += length; return length }
        }
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            runBackupCatching { budget.copy(input, ByteArrayOutputStream()) }
        })
        assertEquals(BackupResourceBudget.TRANSFER_BUFFER_BYTES, readBytes)
    }

    @Test fun `manifest is bounded by actual bytes rather than claimed ZIP size`() {
        val budget = BackupResourceBudget({ Long.MAX_VALUE / 2 })
        val oversized = object : InputStream() {
            var remaining = BackupResourceBudget.MAX_MANIFEST_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 'x'.code else -1
        }
        assertThrows(IllegalArgumentException::class.java) { budget.readManifest(oversized) }
    }

    @Test fun `large available space does not impose a small fixed container limit`() {
        val budget = BackupResourceBudget({ 5L * 1024 * 1024 * 1024 })
        assertTrue(budget.inputLimitForDecryption() > 4L * 1024 * 1024 * 1024)
        budget.requireSpace(4L * 1024 * 1024 * 1024)
    }
}
