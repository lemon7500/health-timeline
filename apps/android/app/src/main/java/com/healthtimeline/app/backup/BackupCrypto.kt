package com.healthtimeline.app.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

object BackupCrypto {
    private val magic = "HTBACKUP".toByteArray(Charsets.US_ASCII)
    private const val version = 1
    private const val iterations = 210_000

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray, budget: BackupResourceBudget? = null) {
        require(password.size >= 8) { "备份密码至少需要 8 位" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val header = DataOutputStream(budget?.limitOutput(output) ?: output)
        header.write(magic)
        header.writeInt(version)
        header.writeInt(salt.size)
        header.write(salt)
        header.writeInt(iv.size)
        header.write(iv)
        transform(input, header, password, salt, iv, true, budget)
        header.flush()
    }

    /** Output is unauthenticated staging data until this method returns successfully. */
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray, budget: BackupResourceBudget? = null) {
        val boundedInput = budget?.limitInput(input, budget.inputLimitForDecryption()) ?: input
        DataInputStream(boundedInput).use { header ->
            val actualMagic = ByteArray(magic.size).also(header::readFully)
            require(actualMagic.contentEquals(magic)) { "不是有效的病程日历备份" }
            require(header.readInt() == version) { "不支持的备份版本" }
            val saltSize = header.readInt()
            require(saltSize in 8..64) { "备份文件已损坏" }
            val salt = ByteArray(saltSize).also(header::readFully)
            val ivSize = header.readInt()
            require(ivSize in 12..32) { "备份文件已损坏" }
            val iv = ByteArray(ivSize).also(header::readFully)
            transform(header, budget?.limitOutput(output) ?: output, password, salt, iv, false, budget)
        }
    }

    private fun transform(
        input: InputStream,
        output: OutputStream,
        password: CharArray,
        salt: ByteArray,
        iv: ByteArray,
        encrypting: Boolean,
        budget: BackupResourceBudget?
    ) {
        budget?.checkpoint()
        val key = deriveKey(password, salt)
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        val inputBuffer = ByteArray(BackupResourceBudget.TRANSFER_BUFFER_BYTES)
        val outputBuffer = ByteArray(inputBuffer.size + 32)
        try {
            cipher.init(encrypting, AEADParameters(KeyParameter(key), 128, iv))
            while (true) {
                budget?.checkpoint()
                val read = input.read(inputBuffer)
                if (read < 0) break
                if (read == 0) continue
                val written = cipher.processBytes(inputBuffer, 0, read, outputBuffer, 0)
                if (written > 0) output.write(outputBuffer, 0, written)
            }
            budget?.checkpoint()
            val written = cipher.doFinal(outputBuffer, 0)
            if (written > 0) output.write(outputBuffer, 0, written)
        } finally {
            key.fill(0)
            inputBuffer.fill(0)
            outputBuffer.fill(0)
            cipher.reset()
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally { spec.clearPassword() }
    }
}
