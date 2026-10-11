package com.healthtimeline.app.backup

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File

/** Generates only empty synthetic files; the central directory exceeds this child's heap. */
object BoundedZipHeapProbe {
    @JvmStatic fun main(args: Array<String>) {
        val file = File.createTempFile("bounded-zip-heap-", ".zip")
        try {
            val count = 600_000
            val name = ByteArray(9)
            DataOutputStream(BufferedOutputStream(file.outputStream())).use { output ->
                fun filename(index: Int) {
                    var value = index; name[0] = 'e'.code.toByte()
                    for (position in 8 downTo 1) { name[position] = ('0'.code + value % 10).toByte(); value /= 10 }
                }
                repeat(count) { index ->
                    filename(index)
                    output.le(0x04034b50, 4); output.le(20, 2); repeat(4) { output.le(0, 2) }
                    repeat(3) { output.le(0, 4) }; output.le(9, 2); output.le(0, 2); output.write(name)
                }
                repeat(count) { index ->
                    filename(index)
                    output.le(0x02014b50, 4); output.le(20, 2); output.le(20, 2); repeat(4) { output.le(0, 2) }
                    repeat(3) { output.le(0, 4) }; output.le(9, 2); repeat(4) { output.le(0, 2) }
                    output.le(0, 4); output.le(39L * index, 4); output.write(name)
                }
                val offset = 39L * count
                val size = 55L * count
                output.le(0x06064b50, 4); output.le(44, 8); output.le(45, 2); output.le(45, 2); output.le(0, 4); output.le(0, 4)
                output.le(count.toLong(), 8); output.le(count.toLong(), 8); output.le(size, 8); output.le(offset, 8)
                output.le(0x07064b50, 4); output.le(0, 4); output.le(offset + size, 8); output.le(1, 4)
                output.le(0x06054b50, 4); output.le(0, 2); output.le(0, 2); output.le(0xffff, 2); output.le(0xffff, 2)
                output.le(size, 4); output.le(offset, 4); output.le(0, 2)
            }
            val available = BackupResourceBudget.STORAGE_RESERVE_BYTES + 256L * 1024 * 1024
            try {
                BoundedBackupZip.open(file, BackupResourceBudget({ available }), available)
                error("Huge entry count should have been rejected")
            } catch (error: IllegalArgumentException) {
                check(error.message.orEmpty().contains("数量"))
                println("BOUND_DIRECTORY_REJECTED_WITH_32_MIB_HEAP")
            }
        } finally { file.delete() }
    }

    private fun DataOutputStream.le(value: Long, bytes: Int) { repeat(bytes) { writeByte((value ushr (it * 8)).toInt()) } }
}
