package com.healthtimeline.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Job

/** Owns only FileProvider camera cache files, never stored medical attachments. */
class CameraCaptureStore(private val context: Context) {
    private val directory = File(context.cacheDir, "camera")

    fun createUri(): Uri {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建拍照临时目录，请检查剩余空间" }
        val file = File.createTempFile("report-", ".jpg", directory)
        return try { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }
        catch (error: Exception) { file.delete(); throw error }
    }

    fun discard(uri: Uri): Boolean = runCatching {
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.files") return@runCatching false
        val parts = uri.pathSegments
        if (parts.size != 2 || parts[0] != "camera") return@runCatching false
        val file = File(directory, parts[1])
        if (!owns(file)) return@runCatching false
        !file.exists() || file.delete()
    }.getOrDefault(false)

    fun discardAfter(importJob: Job, uri: Uri) {
        importJob.invokeOnCompletion { discard(uri) }
    }

    fun cleanupInterrupted(now: Long = System.currentTimeMillis()): Int =
        directory.listFiles().orEmpty().count { file ->
            // Leave a recent capture alone: its system camera may still be writing after process recreation.
            runCatching { owns(file) && file.isFile && now - file.lastModified() >= STALE_AFTER_MS && file.delete() }.getOrDefault(false)
        }

    private fun owns(file: File): Boolean =
        file.name.matches(Regex("report-[A-Za-z0-9-]+\\.jpg")) && file.canonicalFile.parentFile == directory.canonicalFile

    companion object { internal const val STALE_AFTER_MS = 24L * 60 * 60 * 1000 }
}
