package com.healthtimeline.app.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Forked with a 32 MiB heap; neither the plaintext nor ciphertext is held in memory. */
object BackupStreamingHeapProbe {
    @JvmStatic fun main(args: Array<String>) {
        val size = 64L * 1024 * 1024 + 17
        val encrypted = File.createTempFile("backup-small-heap-", ".htbackup")
        try {
            encrypted.outputStream().use { output -> BackupCrypto.encrypt(syntheticInput(size), output, password()) }
            val expected = MessageDigest.getInstance("SHA-256")
            syntheticInput(size).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    expected.update(buffer, 0, read)
                }
            }
            var restoredSize = 0L
            val actual = MessageDigest.getInstance("SHA-256")
            val output = object : OutputStream() {
                override fun write(value: Int) = error("expected block writes")
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    check(length <= BackupResourceBudget.TRANSFER_BUFFER_BYTES + 32)
                    restoredSize += length
                    actual.update(buffer, offset, length)
                }
            }
            encrypted.inputStream().use { BackupCrypto.decrypt(it, output, password()) }
            check(restoredSize == size && expected.digest().contentEquals(actual.digest()))
            println("STREAMED_64_MIB_WITH_32_MIB_HEAP")
        } finally { encrypted.delete() }
    }

    private fun syntheticInput(size: Long): InputStream = object : InputStream() {
        private var position = 0L
        override fun read(): Int = if (position == size) -1 else (position++ % 251).toInt()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == size) return -1
            val count = minOf(length.toLong(), size - position).toInt()
            repeat(count) { index -> buffer[offset + index] = (position++ % 251).toByte() }
            return count
        }
    }
    private fun password() = "heap-probe-test-only".toCharArray()
}
