package com.healthtimeline.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
        assertThrows(Throwable::class.java) {
            BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), ByteArrayOutputStream(), "wrong-password".toCharArray())
        }
    }

    @Test fun `tampered ciphertext is rejected`() {
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream("private health data".toByteArray()), encrypted, "correct-password".toCharArray())
        val bytes = encrypted.toByteArray().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte() }
        assertThrows(Throwable::class.java) {
            BackupCrypto.decrypt(ByteArrayInputStream(bytes), ByteArrayOutputStream(), "correct-password".toCharArray())
        }
    }

    @Test fun `encrypt does not close destination owned by caller`() {
        val output = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream("data".toByteArray()), output, "correct-password".toCharArray())
        output.write(1)
    }
}
