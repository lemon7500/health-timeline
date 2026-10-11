package com.healthtimeline.app.backup

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException

/** Space is checked while streaming; no small, fixed maximum is imposed on an entire backup. */
class BackupResourceBudget(
    private val availableBytes: () -> Long,
    private val checkCancellation: () -> Unit = {},
    private val reserveBytes: Long = STORAGE_RESERVE_BYTES
) {
    init { require(reserveBytes >= 0) }

    fun checkpoint() = checkCancellation()

    fun requireSpace(additionalBytes: Long) {
        checkpoint()
        val available = availableBytes()
        require(additionalBytes >= 0 && available >= reserveBytes &&
            additionalBytes <= available - reserveBytes) { "手机存储空间不足，未修改现有资料" }
    }

    fun inputLimitForDecryption(): Long {
        requireSpace(1)
        // Header and authentication tag do not become the staged plaintext ZIP.
        return Math.addExact(availableBytes() - reserveBytes, MAX_ENCRYPTION_OVERHEAD)
    }

    fun limitInput(input: InputStream, maximumBytes: Long): InputStream {
        require(maximumBytes >= 0)
        return object : FilterInputStream(input) {
            private var count = 0L
            override fun read(): Int {
                checkpoint()
                val value = `in`.read()
                if (value >= 0) count = Math.addExact(count, 1L)
                require(count <= maximumBytes) { "备份超过当前可用存储预算，未修改现有资料" }
                return value
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                checkpoint()
                val remaining = maximumBytes - count
                val requested = if (remaining < length.toLong()) (remaining + 1).toInt() else length
                val read = `in`.read(buffer, offset, requested)
                if (read > 0) count = Math.addExact(count, read.toLong())
                require(count <= maximumBytes) { "备份超过当前可用存储预算，未修改现有资料" }
                return read
            }
        }
    }

    fun limitOutput(output: OutputStream): OutputStream {
        requireSpace(1)
        val maximumBytes = availableBytes() - reserveBytes
        return object : FilterOutputStream(output) {
            private var count = 0L
            override fun write(value: Int) {
                beforeWrite(1)
                out.write(value)
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                beforeWrite(length)
                out.write(buffer, offset, length)
            }
            private fun beforeWrite(length: Int) {
                count = Math.addExact(count, length.toLong())
                require(count <= maximumBytes) { "备份超过当前可用存储预算，未修改现有资料" }
                requireSpace(length.toLong())
            }
        }
    }

    fun copy(input: InputStream, output: OutputStream, maximumBytes: Long = Long.MAX_VALUE): Long {
        val bounded = limitInput(input, maximumBytes)
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        var copied = 0L
        try {
            while (true) {
                val read = bounded.read(buffer)
                if (read < 0) return copied
                if (read == 0) continue
                output.write(buffer, 0, read)
                copied = Math.addExact(copied, read.toLong())
            }
        } finally { buffer.fill(0) }
    }

    fun readManifest(input: InputStream): String {
        val output = ByteArrayOutputStream()
        copy(input, output, MAX_MANIFEST_BYTES)
        return output.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val STORAGE_RESERVE_BYTES = 20L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 10_000_000L
        const val TRANSFER_BUFFER_BYTES = 64 * 1024
        const val MAX_ENCRYPTION_OVERHEAD = 144L
    }
}

internal inline fun <T> runBackupCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
