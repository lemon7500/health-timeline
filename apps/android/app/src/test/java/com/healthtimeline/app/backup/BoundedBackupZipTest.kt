package com.healthtimeline.app.backup

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.Deflater
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BoundedBackupZipTest {
    private val temporary = mutableListOf<File>()
    private val manifest = "{\"schemaVersion\":5}".toByteArray()
    private val available = BackupResourceBudget.STORAGE_RESERVE_BYTES + 256L * 1024 * 1024

    @After fun cleanUp() { temporary.forEach { it.delete() } }

    @Test fun `descriptor files with a final manifest are read and extracted`() {
        val bytes = "synthetic report".toByteArray()
        val file = zip(listOf("files/1" to bytes, "manifest.json" to manifest))
        val archive = open(file)
        assertEquals(String(manifest), archive.readManifest())
        val target = temporaryFile()
        archive.extract(mapOf("files/1" to BoundedBackupZip.Target(target, bytes.size.toLong(), hash(bytes))))
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun `stored and empty attachments preserve old numeric archive paths`() {
        val file = zip(listOf("manifest.json" to manifest, "files/7" to ByteArray(0)), stored = true)
        val archive = open(file)
        assertEquals(String(manifest), archive.readManifest())
        val target = temporaryFile()
        archive.extract(mapOf("files/7" to BoundedBackupZip.Target(target, 0, hash(ByteArray(0)))))
        assertEquals(0L, target.length())
    }

    @Test fun `upper case UUID paths are matched without changing the output path`() {
        val id = "12345678-abcd-4abc-8abc-123456789abc"
        val data = byteArrayOf(1, 2, 3)
        val archive = open(zip(listOf("manifest.json" to manifest, "FILES/${id.uppercase()}" to data)))
        archive.readManifest()
        val target = temporaryFile()
        archive.extract(mapOf("files/$id" to BoundedBackupZip.Target(target, 3, hash(data))))
        assertArrayEquals(data, target.readBytes())
    }

    @Test fun `ZIP64 end records retain descriptor and manifest position compatibility`() {
        val file = zip(listOf("files/1" to byteArrayOf(1), "manifest.json" to manifest))
        addZip64End(file)
        assertEquals(String(manifest), open(file).readManifest())
    }

    @Test fun `ZIP64 local and central size extras are accepted for stored content`() {
        val file = zip64StoredManifest()
        ZipFile(file).use { assertEquals(1, it.size()) }
        assertEquals(String(manifest), open(file).readManifest())
    }

    @Test fun `small forced ZIP64 descriptors complete manifest read and attachment extraction`() {
        listOf(true, false).forEach { signature ->
            val data = "synthetic ZIP64 attachment".toByteArray()
            val file = descriptorZip64Fixture(listOf("files/1" to data, "manifest.json" to manifest), signature = signature)
            ZipFile(file).use { old ->
                assertArrayEquals(manifest, old.getInputStream(old.getEntry("manifest.json")).use { it.readBytes() })
                assertArrayEquals(data, old.getInputStream(old.getEntry("files/1")).use { it.readBytes() })
            }
            val archive = open(file)
            assertEquals(String(manifest), archive.readManifest())
            val target = temporaryFile()
            archive.extract(mapOf("files/1" to BoundedBackupZip.Target(target, data.size.toLong(), hash(data))))
            assertArrayEquals(data, target.readBytes())
        }
    }

    @Test fun `stored content with a descriptor remains readable and extractable`() {
        val data = byteArrayOf(1, 2, 3)
        val file = descriptorZip64Fixture(listOf("files/1" to data, "manifest.json" to manifest), stored = true)
        ZipFile(file).use { old -> assertArrayEquals(data, old.getInputStream(old.getEntry("files/1")).use { it.readBytes() }) }
        val archive = open(file)
        assertEquals(String(manifest), archive.readManifest())
        val target = temporaryFile()
        archive.extract(mapOf("files/1" to BoundedBackupZip.Target(target, 3, hash(data))))
        assertArrayEquals(data, target.readBytes())
    }

    @Test fun `empty forced ZIP64 attachment descriptors use their physical width`() {
        val file = descriptorZip64Fixture(listOf("files/1" to ByteArray(0), "manifest.json" to manifest))
        val archive = open(file)
        assertEquals(String(manifest), archive.readManifest())
        val target = temporaryFile()
        archive.extract(mapOf("files/1" to BoundedBackupZip.Target(target, 0, hash(ByteArray(0)))))
        assertEquals(0L, target.length())
    }

    @Test fun `central order need not match physical order`() {
        val bytes = byteArrayOf(4, 5, 6)
        val archive = open(descriptorZip64Fixture(listOf("files/1" to bytes, "manifest.json" to manifest), reverseCentral = true))
        assertEquals(String(manifest), archive.readManifest())
        val target = temporaryFile()
        archive.extract(mapOf("files/1" to BoundedBackupZip.Target(target, 3, hash(bytes))))
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun `unindexed local files and hidden prefixes are rejected before inflation`() {
        val hidden = descriptorZip64Fixture(listOf("files/1" to byteArrayOf(1), "files/2" to byteArrayOf(2),
            "manifest.json" to manifest), omitCentralPath = "files/2")
        assertThrows(IllegalArgumentException::class.java) { open(hidden) }
        val prefixed = descriptorZip64Fixture(listOf("manifest.json" to manifest), localPrefix = byteArrayOf(7))
        assertThrows(IllegalArgumentException::class.java) { open(prefixed) }
    }

    @Test fun `matching metadata cannot hide trailing compressed bytes`() {
        val file = descriptorZip64Fixture(listOf("manifest.json" to manifest), compressedSuffix = byteArrayOf(1, 2, 3))
        val archive = open(file)
        assertThrows(IllegalArgumentException::class.java) { archive.readManifest() }
    }

    @Test fun `distinct archive entries cannot overwrite one canonical destination`() {
        val bytes = byteArrayOf(1, 2, 3)
        val archive = open(zip(listOf("manifest.json" to manifest, "files/1" to bytes, "files/2" to bytes)))
        val target = temporaryFile().apply { writeBytes(byteArrayOf(9)) }
        val alias = File(target.parentFile, "./${target.name}")
        assertThrows(IllegalArgumentException::class.java) {
            archive.extract(mapOf("files/1" to BoundedBackupZip.Target(target, 3, hash(bytes)),
                "files/2" to BoundedBackupZip.Target(alias, 3, hash(bytes))))
        }
        assertArrayEquals(byteArrayOf(9), target.readBytes())
    }

    @Test fun `absent end directory cannot be accepted by streaming local entries`() {
        val file = zip(listOf("manifest.json" to manifest))
        file.writeBytes(file.readBytes().copyOf(find(file.readBytes(), 0x02014b50)))
        assertThrows(IllegalArgumentException::class.java) { open(file) }
    }

    @Test fun `bad central signature is rejected even when a valid EOCD tail remains`() {
        val file = zip(listOf("manifest.json" to manifest))
        mutate(file) { bytes -> bytes[find(bytes, 0x02014b50)] = 0 }
        assertThrows(IllegalArgumentException::class.java) { open(file) }
    }

    @Test fun `central CRC must agree with the descriptor although old ZipFile can read the bytes`() {
        val file = zip(listOf("manifest.json" to manifest))
        mutate(file) { bytes -> val position = find(bytes, 0x02014b50) + 16; bytes[position] = (bytes[position].toInt() xor 1).toByte() }
        ZipFile(file).use { old -> assertArrayEquals(manifest, old.getInputStream(old.getEntry("manifest.json")).use { it.readBytes() }) }
        assertThrows(IllegalArgumentException::class.java) { open(file) }
    }

    @Test fun `local paths cannot silently differ from the central directory`() {
        val file = zip(listOf("manifest.json" to manifest, "files/1" to byteArrayOf(1)))
        mutate(file) { bytes -> val second = find(bytes, 0x04034b50, 1); bytes[second + 30 + 6] = '2'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) { open(file) }
    }

    @Test fun `descriptor CRC mismatches are rejected before creating extraction files`() {
        val file = zip(listOf("manifest.json" to manifest))
        mutate(file) { bytes -> val position = find(bytes, 0x08074b50) + 4; bytes[position] = (bytes[position].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { open(file) }
    }

    @Test fun `local stored CRC mismatches are rejected without descriptors`() {
        val file = zip(listOf("manifest.json" to manifest), stored = true)
        mutate(file) { bytes -> bytes[14] = (bytes[14].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { open(file) }
    }

    @Test fun `the inflater verifies actual CRC rather than trusting matching metadata`() {
        val file = zip(listOf("manifest.json" to manifest), stored = true)
        mutate(file) { bytes ->
            val central = find(bytes, 0x02014b50)
            bytes[14] = (bytes[14].toInt() xor 1).toByte()
            bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
        }
        val archive = open(file)
        assertThrows(java.util.zip.ZipException::class.java) { archive.readManifest() }
    }

    @Test fun `missing and undeclared files fail the complete allowlist before extraction`() {
        val archive = open(zip(listOf("manifest.json" to manifest, "files/1" to byteArrayOf(1))))
        archive.readManifest()
        assertThrows(IllegalArgumentException::class.java) { archive.extract(emptyMap()) }
        val target = temporaryFile().apply { delete() }
        assertThrows(IllegalArgumentException::class.java) {
            archive.extract(mapOf("files/2" to BoundedBackupZip.Target(target, 1, hash(byteArrayOf(1)))))
        }
        assertFalse(target.exists())
    }

    @Test fun `case ambiguous duplicate archive paths are rejected`() {
        val id = "12345678-abcd-4abc-8abc-123456789abc"
        val file = zip(listOf("manifest.json" to manifest, "files/$id" to byteArrayOf(1), "FILES/${id.uppercase()}" to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { open(file) }
    }

    @Test fun `directories and oversized metadata paths are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { open(zip(listOf("manifest.json" to manifest, "files/" to ByteArray(0)))) }
        assertThrows(IllegalArgumentException::class.java) { open(zip(listOf("manifest.json" to manifest, "files/" + "x".repeat(1000) to ByteArray(0)))) }
    }

    @Test fun `central entry count and offset cannot claim missing metadata`() {
        val countFile = zip(listOf("manifest.json" to manifest))
        mutate(countFile) { bytes -> val end = bytes.size - 22; little(bytes).putShort(end + 8, 2); little(bytes).putShort(end + 10, 2) }
        assertThrows(IllegalArgumentException::class.java) { open(countFile) }
        val offsetFile = zip(listOf("manifest.json" to manifest))
        mutate(offsetFile) { bytes -> little(bytes).putInt(bytes.size - 22 + 16, Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { open(offsetFile) }
    }

    @Test fun `cumulative expansion is limited even when every entry is under its file limit`() {
        val file = zip(listOf("manifest.json" to manifest, "files/1" to ByteArray(6 * 1024 * 1024), "files/2" to ByteArray(6 * 1024 * 1024)))
        val small = BackupResourceBudget.STORAGE_RESERVE_BYTES + 1024L
        val archive = BoundedBackupZip.open(file, BackupResourceBudget({ small }), small)
        assertThrows(IllegalArgumentException::class.java) { archive.readManifest() }
    }

    @Test fun `streaming cancellation is checked between blocks and propagates unchanged`() {
        val file = zip(listOf("manifest.json" to manifest, "files/1" to ByteArray(1024 * 1024)))
        val cancellation = CancellationException("synthetic stream cancellation")
        var checkpoints = 0
        var cancelAt = Int.MAX_VALUE
        val archive = BoundedBackupZip.open(file, BackupResourceBudget({ available }, {
            if (++checkpoints == cancelAt) throw cancellation
        }), available)
        cancelAt = checkpoints + 3
        assertSame(cancellation, assertThrows(CancellationException::class.java) { archive.readManifest() })
    }

    @Test fun `cancellation after a real extraction write propagates without completing the file`() {
        val data = ByteArray(512 * 1024).also(java.util.Random(91)::nextBytes)
        val file = zip(listOf("manifest.json" to manifest, "files/1" to data))
        val target = temporaryFile()
        val cancellation = CancellationException("synthetic partial extraction cancellation")
        val archive = BoundedBackupZip.open(file, BackupResourceBudget({ available }, {
            if (target.length() > 0) throw cancellation
        }), available)
        archive.readManifest()
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            archive.extract(mapOf("files/1" to BoundedBackupZip.Target(target, data.size.toLong(), hash(data))))
        })
        assertTrue("Cancellation must follow a real target write", target.length() > 0)
        assertTrue("Cancellation must stop before all bytes are copied", target.length() < data.size)
        // The service owns temporary-directory cleanup; this helper never touches the live database.
    }

    @Test fun `extraction rejects mismatched declared size or digest`() {
        val bytes = byteArrayOf(1, 2, 3)
        val archive = open(zip(listOf("manifest.json" to manifest, "files/1" to bytes)))
        archive.readManifest()
        assertThrows(IllegalArgumentException::class.java) {
            archive.extract(mapOf("files/1" to BoundedBackupZip.Target(temporaryFile(), 2, hash(bytes))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            archive.extract(mapOf("files/1" to BoundedBackupZip.Target(temporaryFile(), 3, hash(byteArrayOf(4, 5, 6)))))
        }
    }

    @Test fun `huge central directory is rejected in a child with a 32 MiB heap`() {
        val paths = listOf(BoundedZipHeapProbe::class.java, BoundedBackupZip::class.java,
            BackupResourceBudget::class.java, kotlin.Unit::class.java, kotlinx.coroutines.Job::class.java)
            .map { File(requireNotNull(it.protectionDomain?.codeSource?.location).toURI()).absolutePath }.distinct()
        val executable = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java")
        val log = temporaryFile()
        val process = ProcessBuilder(executable.absolutePath, "-Xmx32m", "-cp", paths.joinToString(File.pathSeparator),
            BoundedZipHeapProbe::class.java.name).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); error("bounded ZIP probe timed out") }
        assertEquals(log.readText(), 0, process.exitValue())
        assertTrue(log.readText().contains("BOUND_DIRECTORY_REJECTED_WITH_32_MIB_HEAP"))
    }

    private fun open(file: File) = BoundedBackupZip.open(file, BackupResourceBudget({ available }), available)
    private fun temporaryFile() = File.createTempFile("bounded-zip-test-", ".tmp").also(temporary::add)
    private fun zip(entries: List<Pair<String, ByteArray>>, stored: Boolean = false): File = temporaryFile().also { file ->
        ZipOutputStream(file.outputStream()).use { output -> entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name)
            if (stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.compressedSize = bytes.size.toLong(); entry.crc = CRC32().also { it.update(bytes) }.value }
            output.putNextEntry(entry); output.write(bytes); output.closeEntry()
        } }
    }
    private fun mutate(file: File, change: (ByteArray) -> Unit) { file.writeBytes(file.readBytes().also(change)) }
    private fun find(bytes: ByteArray, signature: Int, from: Int = 0): Int = (from..bytes.size - 4)
        .first { little(bytes).getInt(it) == signature }
    private fun little(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun addZip64End(file: File) {
        val bytes = file.readBytes()
        val oldEnd = bytes.size - 22
        val count = little(bytes).getShort(oldEnd + 10).toLong() and 0xffff
        val size = little(bytes).getInt(oldEnd + 12).toLong() and 0xffff_ffffL
        val offset = little(bytes).getInt(oldEnd + 16).toLong() and 0xffff_ffffL
        val output = ByteArrayOutputStream()
        output.write(bytes, 0, oldEnd)
        DataOutputStream(output).apply {
            writeZip64Tail(oldEnd.toLong(), count, size, offset)
            writeLe(0x06054b50, 4); writeLe(0, 2); writeLe(0, 2); writeLe(0xffff, 2); writeLe(0xffff, 2)
            writeLe(0xffff_ffffL, 4); writeLe(0xffff_ffffL, 4); writeLe(0, 2)
        }
        file.writeBytes(output.toByteArray())
    }

    private fun zip64StoredManifest(): File = temporaryFile().also { file ->
        val name = "manifest.json".toByteArray()
        val crc = CRC32().also { it.update(manifest) }.value
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        with(data) {
            writeLe(0x04034b50, 4); writeLe(45, 2); writeLe(0, 2); writeLe(0, 2); writeLe(0, 2); writeLe(0, 2)
            writeLe(crc, 4); writeLe(0xffff_ffffL, 4); writeLe(0xffff_ffffL, 4); writeLe(name.size.toLong(), 2); writeLe(20, 2)
            write(name); writeLe(1, 2); writeLe(16, 2); writeLe(manifest.size.toLong(), 8); writeLe(manifest.size.toLong(), 8); write(manifest)
            val offset = output.size().toLong()
            writeLe(0x02014b50, 4); writeLe(45, 2); writeLe(45, 2); writeLe(0, 2); writeLe(0, 2); writeLe(0, 2); writeLe(0, 2)
            writeLe(crc, 4); writeLe(0xffff_ffffL, 4); writeLe(0xffff_ffffL, 4); writeLe(name.size.toLong(), 2); writeLe(28, 2); writeLe(0, 2)
            writeLe(0, 2); writeLe(0, 2); writeLe(0, 4); writeLe(0xffff_ffffL, 4)
            write(name); writeLe(1, 2); writeLe(24, 2); writeLe(manifest.size.toLong(), 8); writeLe(manifest.size.toLong(), 8); writeLe(0, 8)
            val size = output.size() - offset
            val zip64Offset = output.size().toLong()
            writeZip64Tail(zip64Offset, 1, size, offset)
            writeLe(0x06054b50, 4); writeLe(0, 2); writeLe(0, 2); writeLe(0xffff, 2); writeLe(0xffff, 2)
            writeLe(0xffff_ffffL, 4); writeLe(0xffff_ffffL, 4); writeLe(0, 2)
        }
        file.writeBytes(output.toByteArray())
    }

    private fun DataOutputStream.writeZip64Tail(zip64Offset: Long, count: Long, size: Long, offset: Long) {
        writeLe(0x06064b50, 4); writeLe(44, 8); writeLe(45, 2); writeLe(45, 2); writeLe(0, 4); writeLe(0, 4)
        writeLe(count, 8); writeLe(count, 8); writeLe(size, 8); writeLe(offset, 8)
        writeLe(0x07064b50, 4); writeLe(0, 4); writeLe(zip64Offset, 8); writeLe(1, 4)
    }

    private fun descriptorZip64Fixture(entries: List<Pair<String, ByteArray>>, stored: Boolean = false,
        signature: Boolean = true, reverseCentral: Boolean = false, omitCentralPath: String? = null,
        localPrefix: ByteArray = ByteArray(0), compressedSuffix: ByteArray = ByteArray(0)): File = temporaryFile().also { file ->
        data class FixtureEntry(val name: ByteArray, val bytes: ByteArray, val compressed: ByteArray, val crc: Long, val offset: Long)
        val output = ByteArrayOutputStream()
        val writer = DataOutputStream(output)
        output.write(localPrefix)
        val written = entries.map { (path, bytes) ->
            val compressed = (if (stored) bytes else {
                val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
                try {
                    deflater.setInput(bytes); deflater.finish()
                    val compressedOutput = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (!deflater.finished()) {
                        val count = deflater.deflate(buffer)
                        compressedOutput.write(buffer, 0, count)
                    }
                    compressedOutput.toByteArray()
                } finally { deflater.end() }
            }) + compressedSuffix
            val entry = FixtureEntry(path.toByteArray(), bytes, compressed, CRC32().also { it.update(bytes) }.value, output.size().toLong())
            with(writer) {
                writeLe(0x04034b50, 4); writeLe(45, 2); writeLe(8, 2); writeLe(if (stored) 0 else 8, 2)
                writeLe(0, 2); writeLe(0, 2); writeLe(0, 4); writeLe(0xffff_ffffL, 4); writeLe(0xffff_ffffL, 4)
                writeLe(entry.name.size.toLong(), 2); writeLe(20, 2); write(entry.name)
                writeLe(1, 2); writeLe(16, 2); writeLe(bytes.size.toLong(), 8); writeLe(compressed.size.toLong(), 8)
                write(compressed)
                if (signature) writeLe(0x08074b50, 4)
                writeLe(entry.crc, 4); writeLe(compressed.size.toLong(), 8); writeLe(bytes.size.toLong(), 8)
            }
            entry
        }
        val centralOffset = output.size().toLong()
        val centralEntries = written.filter { String(it.name) != omitCentralPath }.let { if (reverseCentral) it.reversed() else it }
        centralEntries.forEach { entry -> with(writer) {
            writeLe(0x02014b50, 4); writeLe(45, 2); writeLe(45, 2); writeLe(8, 2); writeLe(if (stored) 0 else 8, 2)
            writeLe(0, 2); writeLe(0, 2); writeLe(entry.crc, 4); writeLe(0xffff_ffffL, 4); writeLe(0xffff_ffffL, 4)
            writeLe(entry.name.size.toLong(), 2); writeLe(20, 2); writeLe(0, 2); writeLe(0, 2); writeLe(0, 2); writeLe(0, 4)
            writeLe(entry.offset, 4); write(entry.name)
            writeLe(1, 2); writeLe(16, 2); writeLe(entry.bytes.size.toLong(), 8); writeLe(entry.compressed.size.toLong(), 8)
        } }
        val centralSize = output.size().toLong() - centralOffset
        with(writer) {
            writeLe(0x06054b50, 4); writeLe(0, 2); writeLe(0, 2); writeLe(centralEntries.size.toLong(), 2); writeLe(centralEntries.size.toLong(), 2)
            writeLe(centralSize, 4); writeLe(centralOffset, 4); writeLe(0, 2)
        }
        file.writeBytes(output.toByteArray())
    }
    private fun DataOutputStream.writeLe(value: Long, length: Int) { repeat(length) { writeByte((value ushr (8 * it)).toInt()) } }
}
