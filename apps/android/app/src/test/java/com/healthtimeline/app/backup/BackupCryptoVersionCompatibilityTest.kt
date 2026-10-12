package com.healthtimeline.app.backup

import com.healthtimeline.shared.PortableJson
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import java.util.zip.ZipInputStream

/** Crypto-only interoperability. The Android legacy-fixture tests separately exercise actual restore. */
class BackupCryptoVersionCompatibilityTest {
    @Test fun `immutable BC 183 golden backups decrypt using actual BC 186 for every manifest version`() {
        assertNewRuntime()
        val fixtures = fixtures()
        for (version in 1..5) {
            val container = container(fixtures, version)
            assertUnchangedHeader(container)
            val plain = decrypt(container)
            assertEquals(fixtures.getProperty("v$version.plaintextSha256"), hash(plain))
            val entries = unzip(plain)
            assertTrue(entries.keys.contains("manifest.json"))
            val manifest = String(entries.getValue("manifest.json"), Charsets.UTF_8)
            assertTrue(Regex("\"schemaVersion\"\\s*:\\s*$version(?:\\D|$)").containsMatchIn(manifest))
            if (version >= 2) assertEquals(version, PortableJson.decode(manifest).schemaVersion)
            val attachmentPaths = fixtures.getProperty("v$version.attachmentPaths").split(',')
            assertEquals(attachmentPaths.toSet(), entries.keys - "manifest.json")
            attachmentPaths.forEachIndexed { index, path ->
                assertEquals(fixtures.getProperty("attachment.${index + 1}.sha256"), hash(entries.getValue(path)))
            }
        }
    }

    @Test fun `new BC 186 encrypted v1 through v5 backups decrypt in isolated actual BC 183 process`() {
        assertNewRuntime()
        val fixtures = fixtures()
        for (version in 1..5) {
            val plain = decrypt(container(fixtures, version))
            val encrypted = ByteArrayOutputStream().also {
                BackupCrypto.encrypt(ByteArrayInputStream(plain), it, password())
            }.toByteArray()
            assertUnchangedHeader(encrypted)
            assertArrayEquals(plain, runLegacy("decrypt", encrypted))
        }
    }

    @Test fun `fresh BC 183 ciphertext crosses block and transfer boundaries and decrypts in BC 186`() {
        assertNewRuntime()
        val original = ByteArray(256 * 1024 + 17) { (it % 251).toByte() }
        assertArrayEquals(original, decrypt(runLegacy("encrypt", original)))
    }

    @Test fun `legacy golden backup still rejects wrong password and damaged authentication tag`() {
        assertNewRuntime()
        val original = container(fixtures(), 5)
        assertThrows(InvalidCipherTextException::class.java) {
            BackupCrypto.decrypt(ByteArrayInputStream(original), ByteArrayOutputStream(), "wrong-password".toCharArray())
        }
        val corrupted = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(InvalidCipherTextException::class.java) { decrypt(corrupted) }
    }

    private fun assertNewRuntime() {
        val jar = File(requireNotNull(GCMBlockCipher::class.java.protectionDomain?.codeSource?.location).toURI())
        JarFile(jar).use { assertEquals("1.86", it.manifest.mainAttributes.getValue("Bundle-Version")) }
        assertEquals("2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f", hash(jar.readBytes()))
    }

    private fun fixtures() = Properties().apply {
        requireNotNull(BackupCryptoVersionCompatibilityTest::class.java.classLoader
            ?.getResourceAsStream("backup-compat/bc183-golden.properties")).use(::load)
        assertEquals("1.83", getProperty("generator.bcVersion"))
        assertEquals(LegacyBackupCryptoProbe.LEGACY_JAR_SHA256, getProperty("generator.bcSha256"))
        assertEquals(LegacyBackupCryptoProbe.PUBLIC_PASSWORD, getProperty("public.password"))
    }

    private fun container(fixtures: Properties, version: Int): ByteArray =
        Base64.getDecoder().decode(fixtures.getProperty("v$version.containerBase64")).also {
            assertEquals(fixtures.getProperty("v$version.containerSha256"), hash(it))
        }

    private fun password() = LegacyBackupCryptoProbe.PUBLIC_PASSWORD.toCharArray()

    private fun decrypt(container: ByteArray) = ByteArrayOutputStream().also {
        BackupCrypto.decrypt(ByteArrayInputStream(container), it, password())
    }.toByteArray()

    private fun assertUnchangedHeader(container: ByteArray) = DataInputStream(ByteArrayInputStream(container)).use {
        assertArrayEquals("HTBACKUP".toByteArray(Charsets.US_ASCII), ByteArray(8).also(it::readFully))
        assertEquals(1, it.readInt())
        assertEquals(16, it.readInt())
        it.readFully(ByteArray(16))
        assertEquals(12, it.readInt())
        it.readFully(ByteArray(12))
        assertTrue(container.size >= 64)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertTrue("duplicate ZIP entry", entries.put(entry.name, zip.readBytes()) == null)
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun runLegacy(mode: String, input: ByteArray): ByteArray {
        val legacyJar = File(requireNotNull(System.getProperty("healthtimeline.legacyBcJar")) {
            "Build must inject the detached, non-transitive BC 1.83 compatibility configuration"
        })
        assertTrue(legacyJar.isFile)
        assertEquals(LegacyBackupCryptoProbe.LEGACY_JAR_SHA256, hash(legacyJar.readBytes()))
        val classes = File(requireNotNull(LegacyBackupCryptoProbe::class.java.protectionDomain?.codeSource?.location).toURI())
        val executable = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java")
        val directory = Files.createTempDirectory("bc183-compat-").toFile()
        val source = File(directory, "input.bin").apply { writeBytes(input) }
        val output = File(directory, "output.bin")
        val log = File(directory, "probe.log")
        try {
            // Only the Java helper classes and legacy JAR: no production BC 1.86 on this classpath.
            val process = ProcessBuilder(executable.absolutePath, "-Xmx32m", "-cp",
                listOf(classes.absolutePath, legacyJar.absolutePath).joinToString(File.pathSeparator),
                LegacyBackupCryptoProbe::class.java.name, mode, source.absolutePath, output.absolutePath)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Isolated legacy backup probe timed out")
            }
            assertEquals(log.readText(), 0, process.exitValue())
            assertTrue(log.readText().contains("BC_VERSION=1.83;JAR_SHA256=${LegacyBackupCryptoProbe.LEGACY_JAR_SHA256};MODE=$mode"))
            return output.readBytes()
        } finally {
            source.delete(); output.delete(); log.delete(); directory.delete()
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
