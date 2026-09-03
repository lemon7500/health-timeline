package com.healthtimeline.app.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.FilterOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCrypto {
    private val magic = "HTBACKUP".toByteArray(Charsets.US_ASCII)
    private const val version = 1
    private const val iterations = 210_000

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        require(password.size >= 8) { "备份密码至少需要 8 位" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val nonClosing = object : FilterOutputStream(output) {
            override fun close() = flush()
        }
        DataOutputStream(nonClosing).use { header ->
            header.write(magic)
            header.writeInt(version)
            header.writeInt(salt.size)
            header.write(salt)
            header.writeInt(iv.size)
            header.write(iv)
            CipherOutputStream(header, cipher).use { encrypted -> input.copyTo(encrypted) }
        }
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        DataInputStream(input).use { header ->
            val actualMagic = ByteArray(magic.size).also(header::readFully)
            require(actualMagic.contentEquals(magic)) { "不是有效的病程日历备份" }
            require(header.readInt() == version) { "不支持的备份版本" }
            val saltSize = header.readInt()
            require(saltSize in 8..64) { "备份文件已损坏" }
            val salt = ByteArray(saltSize).also(header::readFully)
            val ivSize = header.readInt()
            require(ivSize in 12..32) { "备份文件已损坏" }
            val iv = ByteArray(ivSize).also(header::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
            CipherInputStream(header, cipher).use { decrypted -> decrypted.copyTo(output) }
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }
}
