package com.healthtimeline.app.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

/**
 * ZIP metadata is walked with fixed buffers before the platform streaming inflater is used.
 * No ZipFile is constructed: its central-directory allocation precedes application limits.
 * Only backup-protocol file paths are supported; split/encrypted ZIPs and directory entries
 * have never been produced by this application's exporters.
 */
internal class BoundedBackupZip private constructor(
    private val file: File,
    private val names: Set<String>,
    private val directory: Directory,
    private val physicalOffsets: LongArray,
    private val budget: BackupResourceBudget,
    private val expandedLimit: Long
) {
    data class Target(val file: File, val sizeBytes: Long, val sha256: String)

    fun readManifest(): String = requireNotNull(scan(null)) { "备份缺少清单" }

    fun extract(targets: Map<String, Target>) {
        val normalized = targets.entries.associate { (path, target) -> normalize(path) to target }
        require(normalized.size == targets.size && "manifest.json" !in normalized) { "备份附件路径重复" }
        require(names == normalized.keys + "manifest.json") { "备份包含多余文件或附件缺失" }
        normalized.values.forEach { require(it.sizeBytes in 0..MAX_FILE_BYTES) { "附件大小异常" } }
        val destinations = hashSetOf<String>()
        normalized.values.forEach { require(destinations.add(it.file.canonicalPath)) { "备份附件目标路径重复" } }
        scan(normalized)
    }

    private fun scan(targets: Map<String, Target>?): String? {
        var manifest: String? = null
        var expanded = 0L
        val buffer = ByteArray(BackupResourceBudget.TRANSFER_BUFFER_BYTES)
        try {
            RandomAccessFile(file, "r").use { input ->
                // Entry objects are transient. Only bounded names and a primitive offset index are retained.
                walkDirectory(input, directory, budget) { entry ->
                    val name = normalize(entry.path)
                    val isManifest = entry.path == "manifest.json"
                    val target = if (isManifest) null else targets?.get(name)
                    if (targets != null && !isManifest) requireNotNull(target) { "备份包含未声明文件" }
                    val maximum = if (isManifest) BackupResourceBudget.MAX_MANIFEST_BYTES else target?.sizeBytes ?: MAX_FILE_BYTES
                    val captured = if (isManifest && targets == null) ByteArrayOutputStream() else null
                    val digest = if (target != null) MessageDigest.getInstance("SHA-256") else null
                    val crc = CRC32()
                    var actual = 0L
                    val dataOffset = validateLocal(input, entry, nextPhysicalOffset(entry.fields.offset), budget)
                    input.seek(dataOffset)
                    val region = CompressedRegion(input, entry.fields.compressed, budget)
                    val inflater = if (entry.method == 8) Inflater(true) else null
                    val content = if (inflater != null) InflaterInputStream(region, inflater, buffer.size) else region
                    fun consume(output: OutputStream?) {
                        while (true) {
                            budget.checkpoint()
                            val read = content.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            actual = Math.addExact(actual, read.toLong())
                            expanded = Math.addExact(expanded, read.toLong())
                            require(actual <= maximum && actual <= entry.fields.size && expanded <= expandedLimit) {
                                "备份解压内容超过存储预算或附件大小限制"
                            }
                            crc.update(buffer, 0, read)
                            digest?.update(buffer, 0, read)
                            output?.write(buffer, 0, read)
                        }
                    }
                    try {
                        if (target == null) consume(captured)
                        else target.file.outputStream().use { output ->
                            consume(budget.limitOutput(output))
                            output.fd.sync()
                        }
                        require(actual == entry.fields.size && region.remaining == 0L &&
                            (inflater == null || (inflater.finished() && inflater.bytesRead == entry.fields.compressed))) {
                            "备份实际压缩数据与声明大小不一致"
                        }
                        if (crc.value != entry.crc) throw ZipException("备份文件CRC校验失败")
                    } finally {
                        inflater?.end()
                    }
                    if (target != null) {
                        val hash = requireNotNull(digest).digest().joinToString("") { "%02x".format(it) }
                        require(actual == target.sizeBytes && hash == target.sha256) { "附件大小或校验值不符" }
                    }
                    if (captured != null) {
                        require(actual > 0) { "备份清单大小异常" }
                        manifest = captured.toString(Charsets.UTF_8.name())
                    }
                }
            }
            return manifest
        } finally { buffer.fill(0) }
    }

    private fun nextPhysicalOffset(offset: Long): Long {
        val index = physicalOffsets.binarySearch(offset)
        require(index >= 0) { "备份本地文件未在中央目录声明" }
        return if (index == physicalOffsets.lastIndex) directory.offset else physicalOffsets[index + 1]
    }

    /** A close on an inflater must never close the RandomAccessFile owned by the directory walk. */
    private class CompressedRegion(private val input: RandomAccessFile, length: Long,
        private val budget: BackupResourceBudget) : InputStream() {
        var remaining: Long = length
            private set
        override fun read(): Int {
            budget.checkpoint()
            if (remaining == 0L) return -1
            val value = input.read()
            require(value >= 0) { "备份压缩数据已截断" }
            remaining--
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            budget.checkpoint()
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val read = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            require(read > 0) { "备份压缩数据已截断" }
            remaining -= read
            return read
        }
    }

    companion object {
        private const val MAX_ENTRIES = 100_001 // Manifest plus the protocol's 100,000 attachments.
        private const val MAX_PATH_BYTES = 64
        private const val MAX_FILE_BYTES = 100L * 1024 * 1024
        private const val UINT32_MAX = 0xffff_ffffL
        private val pathPattern = Regex("(?i:files)/([0-9]{1,20}|[0-9a-fA-F-]{36})")

        fun open(file: File, budget: BackupResourceBudget, availableStorageBytes: Long): BoundedBackupZip {
            budget.checkpoint()
            require(availableStorageBytes >= BackupResourceBudget.STORAGE_RESERVE_BYTES) { "手机存储空间不足，未修改现有资料" }
            val expandedLimit = Math.addExact(availableStorageBytes - BackupResourceBudget.STORAGE_RESERVE_BYTES,
                BackupResourceBudget.MAX_MANIFEST_BYTES)
            val metadata = RandomAccessFile(file, "r").use { validateDirectory(it, budget) }
            return BoundedBackupZip(file, metadata.names, metadata.directory, metadata.offsets, budget, expandedLimit)
        }

        private fun normalize(path: String): String = path.lowercase(Locale.ROOT)

        private data class Directory(val offset: Long, val size: Long, val count: Long, val end: Long)
        private data class Zip64Fields(val size: Long, val compressed: Long, val offset: Long, val disk: Long)
        private data class Entry(val path: String, val name: ByteArray, val fields: Zip64Fields,
            val flags: Int, val method: Int, val crc: Long)
        private data class Metadata(val directory: Directory, val names: Set<String>, val offsets: LongArray)

        private fun validateDirectory(input: RandomAccessFile, budget: BackupResourceBudget): Metadata {
            val directory = locateDirectory(input, budget)
            require(directory.count in 1..MAX_ENTRIES.toLong()) { "备份文件数量异常" }
            require(directory.size >= directory.count * 46L && add(directory.offset, directory.size) == directory.end) {
                "备份中央目录范围异常"
            }
            val names = hashSetOf<String>()
            val offsets = LongArray(directory.count.toInt())
            var index = 0
            walkDirectory(input, directory, budget) { entry ->
                require(names.add(normalize(entry.path))) { "备份包含重复文件路径" }
                offsets[index++] = entry.fields.offset
            }
            offsets.sort()
            require(offsets.first() == 0L) { "备份包含未声明的文件前缀" }
            for (position in 1..offsets.lastIndex) require(offsets[position - 1] < offsets[position]) {
                "备份本地文件位置重复"
            }
            // Validate physical coverage before any inflation: no hidden local file, gap, or overlap.
            walkDirectory(input, directory, budget) { entry ->
                val position = offsets.binarySearch(entry.fields.offset)
                val next = if (position == offsets.lastIndex) directory.offset else offsets[position + 1]
                validateLocal(input, entry, next, budget)
            }
            return Metadata(directory, names, offsets)
        }

        private fun walkDirectory(input: RandomAccessFile, directory: Directory, budget: BackupResourceBudget,
            action: (Entry) -> Unit) {
            val header = ByteArray(46)
            input.seek(directory.offset)
            repeat(directory.count.toInt()) {
                budget.checkpoint()
                require(input.filePointer <= directory.end - header.size) { "备份中央目录已截断" }
                input.readFully(header)
                require(u32(header, 0) == 0x02014b50L) { "备份中央目录签名损坏" }
                val flags = u16(header, 8)
                val method = u16(header, 10)
                require(flags and 1 == 0 && method in listOf(0, 8)) { "备份ZIP加密或压缩方式不受支持" }
                val nameLength = u16(header, 28)
                val extraLength = u16(header, 30)
                val commentLength = u16(header, 32)
                require(nameLength in 1..MAX_PATH_BYTES && add(input.filePointer,
                    nameLength.toLong() + extraLength + commentLength) <= directory.end) { "备份路径或中央目录大小异常" }
                val nameBytes = ByteArray(nameLength).also(input::readFully)
                val name = nameBytes.toString(Charsets.UTF_8)
                require(name == "manifest.json" || pathPattern.matches(name)) { "备份包含不安全或未支持的路径" }
                val fields = readExtra(input, extraLength, u32(header, 24), u32(header, 20),
                    u32(header, 42), u16(header, 34).toLong(), budget)
                require(fields.disk == 0L && fields.size in 0..(if (name == "manifest.json")
                    BackupResourceBudget.MAX_MANIFEST_BYTES else MAX_FILE_BYTES)) { "备份附件大小或分卷信息异常" }
                require(method != 0 || fields.compressed == fields.size) { "未压缩附件大小异常" }
                val next = add(input.filePointer, commentLength.toLong())
                action(Entry(name, nameBytes, fields, flags, method, u32(header, 16)))
                input.seek(next)
            }
            require(input.filePointer == directory.end) { "备份中央目录数量或长度不一致" }
        }

        private fun locateDirectory(input: RandomAccessFile, budget: BackupResourceBudget): Directory {
            val length = input.length()
            require(length >= 22) { "备份ZIP尾部已截断" }
            val tail = ByteArray(minOf(length, 65_557L).toInt())
            val start = length - tail.size
            input.seek(start); input.readFully(tail)
            var found = -1
            for (position in tail.size - 22 downTo 0) {
                if (position and 1023 == 0) budget.checkpoint()
                if (u32(tail, position) == 0x06054b50L && position + 22 + u16(tail, position + 20) == tail.size) {
                    found = position; break
                }
            }
            require(found >= 0) { "备份缺少有效ZIP结束目录" }
            require(u16(tail, found + 4) == 0 && u16(tail, found + 6) == 0) { "备份不支持分卷ZIP" }
            val endOffset = start + found
            val count = u16(tail, found + 10).toLong()
            val countOnDisk = u16(tail, found + 8).toLong()
            val size = u32(tail, found + 12)
            val offset = u32(tail, found + 16)
            val needs64 = count == 0xffffL || countOnDisk == 0xffffL || size == UINT32_MAX || offset == UINT32_MAX
            var locator: ByteArray? = null
            if (endOffset >= 20) {
                input.seek(endOffset - 20)
                val candidate = ByteArray(20).also(input::readFully)
                if (u32(candidate, 0) == 0x07064b50L) locator = candidate
            }
            if (locator == null) {
                require(!needs64 && count == countOnDisk) { "备份ZIP64目录缺失或分卷计数异常" }
                return Directory(offset, size, count, endOffset)
            }
            require(u32(locator, 4) == 0L && u32(locator, 16) == 1L) { "备份不支持分卷ZIP64" }
            val zip64Offset = u64(locator, 8)
            require(zip64Offset <= endOffset - 20 - 56) { "备份ZIP64范围异常" }
            input.seek(zip64Offset)
            val header = ByteArray(56).also(input::readFully)
            require(u32(header, 0) == 0x06064b50L && u64(header, 4) >= 44 &&
                add(zip64Offset, add(12L, u64(header, 4))) == endOffset - 20) { "备份ZIP64结束目录损坏" }
            require(u32(header, 16) == 0L && u32(header, 20) == 0L && u64(header, 24) == u64(header, 32)) {
                "备份不支持分卷ZIP64"
            }
            val actualCount = u64(header, 32)
            val actualSize = u64(header, 40)
            val actualOffset = u64(header, 48)
            require((count == 0xffffL || count == actualCount) && (countOnDisk == 0xffffL || countOnDisk == actualCount) &&
                (size == UINT32_MAX || size == actualSize) && (offset == UINT32_MAX || offset == actualOffset)) {
                "备份ZIP与ZIP64目录不一致"
            }
            return Directory(actualOffset, actualSize, actualCount, zip64Offset)
        }

        private fun validateLocal(input: RandomAccessFile, entry: Entry, physicalEnd: Long,
            budget: BackupResourceBudget): Long {
            budget.checkpoint()
            val name = entry.name
            val fields = entry.fields
            val flags = entry.flags
            val method = entry.method
            val crc = entry.crc
            require(fields.offset <= physicalEnd - 30) { "备份本地文件范围异常" }
            input.seek(fields.offset)
            val header = ByteArray(30).also(input::readFully)
            require(u32(header, 0) == 0x04034b50L && u16(header, 6) == flags && u16(header, 8) == method) {
                "备份本地文件头与中央目录不一致"
            }
            val nameLength = u16(header, 26)
            val extraLength = u16(header, 28)
            require(nameLength == name.size && add(input.filePointer, nameLength.toLong() + extraLength) <= physicalEnd) {
                "备份本地文件路径或范围异常"
            }
            require(ByteArray(nameLength).also(input::readFully).contentEquals(name)) { "备份本地文件路径与中央目录不一致" }
            val local = readExtra(input, extraLength, u32(header, 22), u32(header, 18), 0L, 0L, budget)
            val dataOffset = input.filePointer
            val dataEnd = add(dataOffset, fields.compressed)
            require(dataEnd <= physicalEnd) { "备份压缩数据已截断" }
            if (flags and 8 == 0) {
                require(local.size == fields.size && local.compressed == fields.compressed && u32(header, 14) == crc) {
                    "备份本地文件大小或CRC与中央目录不一致"
                }
                require(dataEnd == physicalEnd) { "备份本地文件之间存在未声明内容" }
            } else {
                require((local.size == 0L || local.size == fields.size) &&
                    (local.compressed == 0L || local.compressed == fields.compressed) &&
                    (u32(header, 14) == 0L || u32(header, 14) == crc)) { "备份数据描述符前的文件头异常" }
                input.seek(dataEnd)
                val descriptorLength = physicalEnd - dataEnd
                require(descriptorLength in listOf(12L, 16L, 20L, 24L)) { "备份数据描述符范围或本地文件布局异常" }
                val descriptor = ByteArray(descriptorLength.toInt()).also(input::readFully)
                val signed = descriptor.size == 16 || descriptor.size == 24
                val position = if (signed) 4 else 0
                require(!signed || u32(descriptor, 0) == 0x08074b50L) { "备份数据描述符签名损坏" }
                val sizesMatch = if (descriptor.size - position == 12) {
                    u32(descriptor, position + 4) == fields.compressed && u32(descriptor, position + 8) == fields.size
                } else {
                    u64(descriptor, position + 4) == fields.compressed && u64(descriptor, position + 12) == fields.size
                }
                require(u32(descriptor, position) == crc && sizesMatch) { "备份数据描述符大小或CRC与中央目录不一致" }
            }
            return dataOffset
        }

        private fun readExtra(input: RandomAccessFile, length: Int, size: Long, compressed: Long,
            offset: Long, disk: Long, budget: BackupResourceBudget): Zip64Fields {
            val end = add(input.filePointer, length.toLong())
            var actualSize = size
            var actualCompressed = compressed
            var actualOffset = offset
            var actualDisk = disk
            var found = false
            while (input.filePointer < end) {
                budget.checkpoint()
                require(end - input.filePointer >= 4) { "备份ZIP扩展字段已截断" }
                val header = ByteArray(4).also(input::readFully)
                val fieldEnd = add(input.filePointer, u16(header, 2).toLong())
                require(fieldEnd <= end) { "备份ZIP扩展字段长度异常" }
                if (u16(header, 0) == 1) {
                    require(!found) { "备份ZIP64扩展字段重复" }; found = true
                    fun value64(): Long {
                        require(fieldEnd - input.filePointer >= 8) { "备份ZIP64扩展字段已截断" }
                        return u64(ByteArray(8).also(input::readFully), 0)
                    }
                    if (size == UINT32_MAX) actualSize = value64()
                    if (compressed == UINT32_MAX) actualCompressed = value64()
                    if (offset == UINT32_MAX) actualOffset = value64()
                    if (disk == 0xffffL) {
                        require(fieldEnd - input.filePointer >= 4) { "备份ZIP64分卷字段已截断" }
                        actualDisk = u32(ByteArray(4).also(input::readFully), 0)
                    }
                }
                input.seek(fieldEnd)
            }
            require((size != UINT32_MAX && compressed != UINT32_MAX && offset != UINT32_MAX && disk != 0xffffL) || found) {
                "备份ZIP64扩展字段缺失"
            }
            return Zip64Fields(actualSize, actualCompressed, actualOffset, actualDisk)
        }

        private fun add(left: Long, right: Long): Long {
            require(left >= 0 && right >= 0) { "备份ZIP包含负数范围" }
            return Math.addExact(left, right)
        }
        private fun u16(bytes: ByteArray, position: Int): Int =
            (bytes[position].toInt() and 255) or ((bytes[position + 1].toInt() and 255) shl 8)
        private fun u32(bytes: ByteArray, position: Int): Long =
            (0..3).fold(0L) { value, index -> value or ((bytes[position + index].toLong() and 255) shl (index * 8)) }
        private fun u64(bytes: ByteArray, position: Int): Long {
            val value = (0..7).fold(0L) { value, index -> value or ((bytes[position + index].toLong() and 255) shl (index * 8)) }
            require(value >= 0) { "备份ZIP64整数超出支持范围" }
            return value
        }
    }
}
