package com.healthtimeline.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class AttachmentStore(
    private val context: Context,
    private val dao: AttachmentDao
) {
    internal val storageRoot: File get() = context.filesDir
    companion object { const val MAX_FILE_BYTES = 100L * 1024L * 1024L }

    suspend fun import(recordId: Long, uri: Uri): Result<AttachmentEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val metadata = metadata(resolver, uri)
            require(metadata.size in 0..MAX_FILE_BYTES) { "单个文件不能超过 100 MB" }
            val mime = resolver.getType(uri) ?: guessMime(metadata.name)
            val kind = when {
                mime.startsWith("image/") -> AttachmentKind.IMAGE
                mime == "application/pdf" -> AttachmentKind.PDF
                else -> error("仅支持图片和 PDF")
            }
            require(context.filesDir.usableSpace > metadata.size + 10L * 1024L * 1024L) { "手机存储空间不足" }

            val recordDir = File(context.filesDir, "attachments/$recordId")
            require(recordDir.isDirectory || recordDir.mkdirs()) { "无法创建附件目录" }
            val extension = if (kind == AttachmentKind.PDF) ".pdf" else extensionFromName(metadata.name)
            val relative = "attachments/$recordId/${UUID.randomUUID()}$extension"
            val destination = File(context.filesDir, relative)
            val partial = File(destination.parentFile, "${destination.name}.partial")
            val digest = MessageDigest.getInstance("SHA-256")
            var moved = false
            try {
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_FILE_BYTES) { "单个文件不能超过 100 MB" }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                } ?: error("无法读取所选文件")
                require(partial.renameTo(destination)) { "无法保存附件" }
                moved = true
                validateContent(destination, kind)
                val entity = AttachmentEntity(
                    recordId = recordId,
                    kind = kind.name,
                    displayName = metadata.name.take(200),
                    mimeType = mime,
                    relativePath = relative,
                    sizeBytes = destination.length(),
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                    createdAt = Instant.now().toString()
                )
                val id = dao.insert(entity)
                entity.copy(id = id)
            } catch (error: Throwable) {
                if (moved) destination.delete()
                throw error
            } finally {
                partial.delete()
            }
        }
    }

    suspend fun delete(value: AttachmentEntity) = withContext(Dispatchers.IO) {
        dao.delete(value)
        File(context.filesDir, value.relativePath).delete()
    }

    suspend fun deleteFilesForRecord(recordId: Long) = withContext(Dispatchers.IO) {
        dao.forRecord(recordId).forEach { File(context.filesDir, it.relativePath).delete() }
        File(context.filesDir, "attachments/$recordId").deleteRecursively()
    }

    fun file(value: AttachmentEntity): File = File(context.filesDir, value.relativePath)

    private data class Metadata(val name: String, val size: Long)

    private fun metadata(resolver: ContentResolver, uri: Uri): Metadata {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0) ?: "检查报告"
                val size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
                return Metadata(name, size)
            }
        }
        return Metadata(uri.lastPathSegment ?: "检查报告", 0L)
    }

    private fun extensionFromName(name: String): String {
        val suffix = name.substringAfterLast('.', "").lowercase()
        return if (suffix.matches(Regex("[a-z0-9]{1,5}"))) ".$suffix" else ".img"
    }

    private fun guessMime(name: String): String = if (name.endsWith(".pdf", true)) "application/pdf" else "image/*"

    private fun validateContent(file: File, kind: AttachmentKind) {
        when (kind) {
            AttachmentKind.PDF -> {
                val signature = ByteArray(5)
                val count = file.inputStream().use { it.read(signature) }
                require(count == signature.size && signature.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) {
                    "所选文件不是有效的 PDF"
                }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        require(renderer.pageCount > 0) { "PDF 没有可显示的页面" }
                    }
                }
            }
            AttachmentKind.IMAGE -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                require(options.outWidth > 0 && options.outHeight > 0) { "所选文件不是有效的图片" }
            }
        }
    }
}
