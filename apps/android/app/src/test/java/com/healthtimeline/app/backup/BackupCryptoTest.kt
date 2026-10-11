package com.healthtimeline.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.InvalidCipherTextException
import java.util.concurrent.TimeUnit

class BackupCryptoTest {
    @Test fun `round trip restores original bytes`() {
        val original = ByteArray(64 * 1024) { (it % 251).toByte() }
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(original), encrypted, "correct-password".toCharArray())
        val restored = ByteArrayOutputStream()
        BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), restored, "correct-password".toCharArray())
        assertArrayEquals(original, restored.toByteArray())
    }

    @Test fun `wrong password never emits accepted backup`() {
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream("private health data".toByteArray()), encrypted, "correct-password".toCharArray())
        assertThrows(InvalidCipherTextException::class.java) {
            BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), ByteArrayOutputStream(), "wrong-password".toCharArray())
        }
    }

    @Test fun `tampered ciphertext is rejected`() {
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream("private health data".toByteArray()), encrypted, "correct-password".toCharArray())
        val bytes = encrypted.toByteArray().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte() }
        assertThrows(InvalidCipherTextException::class.java) {
            BackupCrypto.decrypt(ByteArrayInputStream(bytes), ByteArrayOutputStream(), "correct-password".toCharArray())
        }
    }

    @Test fun `encrypt does not close destination owned by caller`() {
        val output = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream("data".toByteArray()), output, "correct-password".toCharArray())
        output.write(1)
    }

    @Test fun `empty content round trips with an authenticated container`() {
        val container = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(ByteArray(0)), container, "correct-password".toCharArray())
        val restored = ByteArrayOutputStream()
        BackupCrypto.decrypt(ByteArrayInputStream(container.toByteArray()), restored, "correct-password".toCharArray())
        org.junit.Assert.assertEquals(64, container.size()) // Unchanged 48-byte header and 16-byte tag.
        assertArrayEquals(ByteArray(0), restored.toByteArray())
    }

    @Test fun `empty containers remain compatible in both legacy JCA directions`() {
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 16).toByte() }
        val legacyEncrypt = Cipher.getInstance("AES/GCM/NoPadding")
        legacyEncrypt.init(Cipher.ENCRYPT_MODE, legacyKey(salt), GCMParameterSpec(128, iv))
        val legacyContainer = headerFixture().copyOf(48) + legacyEncrypt.doFinal(ByteArray(0))
        val restored = ByteArrayOutputStream()
        BackupCrypto.decrypt(ByteArrayInputStream(legacyContainer), restored, "correct-password".toCharArray())
        assertArrayEquals(ByteArray(0), restored.toByteArray())

        val newContainer = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(ByteArray(0)), newContainer, "correct-password".toCharArray())
        DataInputStream(ByteArrayInputStream(newContainer.toByteArray())).use { input ->
            assertArrayEquals("HTBACKUP".toByteArray(Charsets.US_ASCII), ByteArray(8).also(input::readFully))
            org.junit.Assert.assertEquals(1, input.readInt())
            val newSalt = ByteArray(input.readInt()).also(input::readFully)
            val newIv = ByteArray(input.readInt()).also(input::readFully)
            val legacyDecrypt = Cipher.getInstance("AES/GCM/NoPadding")
            legacyDecrypt.init(Cipher.DECRYPT_MODE, legacyKey(newSalt), GCMParameterSpec(128, newIv))
            assertArrayEquals(ByteArray(0), legacyDecrypt.doFinal(input.readBytes()))
        }
    }

    @Test fun `damaged magic is rejected before authentication`() {
        val container = headerFixture().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(ByteArrayInputStream(container), ByteArrayOutputStream(), "correct-password".toCharArray())
        }
    }

    @Test fun `unsupported container versions are rejected before authentication`() {
        listOf(-1, 0, 2, Int.MAX_VALUE).forEach { version ->
            val container = headerFixture().also { ByteBuffer.wrap(it).putInt(8, version) }
            assertThrows(IllegalArgumentException::class.java) {
                BackupCrypto.decrypt(ByteArrayInputStream(container), ByteArrayOutputStream(), "correct-password".toCharArray())
            }
        }
    }

    @Test fun `out of range salt lengths are rejected without allocating claimed bytes`() {
        listOf(-1, 0, 7, 65, Int.MAX_VALUE).forEach { length ->
            val container = headerFixture().also { ByteBuffer.wrap(it).putInt(12, length) }
            assertThrows(IllegalArgumentException::class.java) {
                BackupCrypto.decrypt(ByteArrayInputStream(container), ByteArrayOutputStream(), "correct-password".toCharArray())
            }
        }
    }

    @Test fun `out of range IV lengths are rejected without allocating claimed bytes`() {
        listOf(-1, 0, 11, 33, Int.MAX_VALUE).forEach { length ->
            val container = headerFixture().also { ByteBuffer.wrap(it).putInt(32, length) }
            assertThrows(IllegalArgumentException::class.java) {
                BackupCrypto.decrypt(ByteArrayInputStream(container), ByteArrayOutputStream(), "correct-password".toCharArray())
            }
        }
    }

    @Test fun `truncated header fails with EOF at every header field boundary`() {
        val complete = headerFixture()
        listOf(0, 7, 8, 11, 12, 15, 16, 31, 32, 35, 36, 47).forEach { length ->
            assertThrows(EOFException::class.java) {
                BackupCrypto.decrypt(ByteArrayInputStream(complete.copyOf(length)), ByteArrayOutputStream(), "correct-password".toCharArray())
            }
        }
    }

    @Test fun `truncated authentication tag is rejected even for empty content`() {
        val container = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(ByteArray(0)), container, "correct-password".toCharArray())
        listOf(0, 1, 15).forEach { remainingTagBytes ->
            val truncated = container.toByteArray().copyOf(48 + remainingTagBytes)
            assertThrows(InvalidCipherTextException::class.java) {
                BackupCrypto.decrypt(ByteArrayInputStream(truncated), ByteArrayOutputStream(), "correct-password".toCharArray())
            }
        }
    }

    @Test fun `new decrypt reads the unchanged legacy JCA container`() {
        val original = ByteArray(256 * 1024 + 17) { (it % 251).toByte() }
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 16).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, legacyKey(salt), GCMParameterSpec(128, iv))
        val container = ByteArrayOutputStream()
        DataOutputStream(container).apply {
            write("HTBACKUP".toByteArray(Charsets.US_ASCII))
            writeInt(1); writeInt(salt.size); write(salt); writeInt(iv.size); write(iv)
            write(cipher.doFinal(original))
        }
        val restored = ByteArrayOutputStream()
        BackupCrypto.decrypt(ByteArrayInputStream(container.toByteArray()), restored, "correct-password".toCharArray())
        assertArrayEquals(original, restored.toByteArray())
    }

    @Test fun `new encrypted container is readable by legacy JCA decrypt`() {
        val original = ByteArray(256 * 1024 + 17) { (it % 251).toByte() }
        val container = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(original), container, "correct-password".toCharArray())
        DataInputStream(ByteArrayInputStream(container.toByteArray())).use { input ->
            assertArrayEquals("HTBACKUP".toByteArray(Charsets.US_ASCII), ByteArray(8).also(input::readFully))
            org.junit.Assert.assertEquals(1, input.readInt())
            val salt = ByteArray(input.readInt()).also(input::readFully)
            val iv = ByteArray(input.readInt()).also(input::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, legacyKey(salt), GCMParameterSpec(128, iv))
            assertArrayEquals(original, cipher.doFinal(input.readBytes()))
        }
    }

    @Test fun `decrypt emits bounded staging chunks before reaching ciphertext EOF`() {
        val original = ByteArray(256 * 1024 + 17) { (it % 251).toByte() }
        val container = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(original), container, "correct-password".toCharArray())
        var reachedEof = false
        var streamedBeforeEof = false
        var total = 0
        val input = object : ByteArrayInputStream(container.toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { if (it < 0) reachedEof = true }
        }
        val output = object : OutputStream() {
            override fun write(value: Int) { error("expected bounded block writes") }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                assertTrue(length <= BackupResourceBudget.TRANSFER_BUFFER_BYTES + 32)
                if (!reachedEof) streamedBeforeEof = true
                total += length
            }
        }
        BackupCrypto.decrypt(input, output, "correct-password".toCharArray())
        assertTrue(streamedBeforeEof)
        org.junit.Assert.assertEquals(original.size, total)
    }

    @Test fun `backup larger than a small process heap is streamed without full buffering`() {
        val paths = listOf(BackupStreamingHeapProbe::class.java, BackupCrypto::class.java,
            GCMBlockCipher::class.java, kotlin.Unit::class.java, kotlinx.coroutines.Job::class.java)
            .map { File(requireNotNull(it.protectionDomain?.codeSource?.location).toURI()).absolutePath }.distinct()
        val executable = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java")
        val log = File.createTempFile("backup-heap-probe-", ".log")
        try {
            val process = ProcessBuilder(executable.absolutePath, "-Xmx32m", "-cp", paths.joinToString(File.pathSeparator),
                BackupStreamingHeapProbe::class.java.name).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("bounded backup heap probe timed out")
            }
            org.junit.Assert.assertEquals(log.readText(), 0, process.exitValue())
            assertTrue(log.readText().contains("STREAMED_64_MIB_WITH_32_MIB_HEAP"))
        } finally { log.delete() }
    }

    /** Structurally valid header with a deliberately unauthenticated dummy tag for header-only failures. */
    private fun headerFixture(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write("HTBACKUP".toByteArray(Charsets.US_ASCII))
            output.writeInt(1)
            output.writeInt(16)
            output.write(ByteArray(16) { it.toByte() })
            output.writeInt(12)
            output.write(ByteArray(12) { (it + 16).toByte() })
            output.write(ByteArray(16))
        }
    }.toByteArray()

    private fun legacyKey(salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec("correct-password".toCharArray(), salt, 210_000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
}
