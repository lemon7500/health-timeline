package com.healthtimeline.app.backup

import java.io.FileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class BackupDestinationStorageTest {
    @Test fun `target space decreasing during export stops before the next block`() {
        val reserve = BackupResourceBudget.STORAGE_RESERVE_BYTES
        var targetSpace = reserve + 80L
        val bytes = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(value: Int) { bytes.write(value); targetSpace-- }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                bytes.write(buffer, offset, length)
                targetSpace -= length
            }
        }
        val budget = BackupDestinationStorage.budget(FileDescriptor(), { targetSpace }, { reserve + 1000 }, {})
        val bounded = budget.limitOutput(output)
        bounded.write(ByteArray(64))
        assertThrows(IllegalArgumentException::class.java) { bounded.write(ByteArray(32)) }
        assertEquals(64, bytes.size())
    }

    @Test fun `a measured large target is not capped by small internal space`() {
        val reserve = BackupResourceBudget.STORAGE_RESERVE_BYTES
        val budget = BackupDestinationStorage.budget(FileDescriptor(), { reserve + 200_000 }, { reserve + 1 }, {})
        val bytes = ByteArrayOutputStream()
        budget.copy(ByteArrayInputStream(ByteArray(100_000)), budget.limitOutput(bytes))
        assertEquals(100_000, bytes.size())
    }

    @Test fun `unknown destination uses the conservative fallback but zero does not`() {
        val reserve = BackupResourceBudget.STORAGE_RESERVE_BYTES
        val unknown = BackupDestinationStorage.budget(FileDescriptor(), { null }, { reserve + 64 }, {})
        assertThrows(IllegalArgumentException::class.java) { unknown.limitOutput(ByteArrayOutputStream()).write(ByteArray(65)) }
        val full = BackupDestinationStorage.budget(FileDescriptor(), { 0 }, { Long.MAX_VALUE / 2 }, {})
        assertThrows(IllegalArgumentException::class.java) { full.limitOutput(ByteArrayOutputStream()) }
    }

    @Test fun `filesystem size multiplication is overflow checked`() {
        assertEquals(8_192L, BackupDestinationStorage.safeAvailableBytes(2, 4_096))
        assertNull(BackupDestinationStorage.safeAvailableBytes(Long.MAX_VALUE, 4_096))
        assertNull(BackupDestinationStorage.safeAvailableBytes(-1, 4_096))
        assertNull(BackupDestinationStorage.safeAvailableBytes(1, 0))
        assertEquals(0L, BackupDestinationStorage.safeAvailableBytes(0, 4_096))
    }
}
