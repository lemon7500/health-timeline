package com.healthtimeline.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.healthtimeline.app.data.AttachmentEntity
import com.healthtimeline.app.data.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

@Composable
fun AttachmentViewerDialog(attachment: AttachmentEntity, file: File, onDismiss: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val pageCountResult by produceState<Result<Int>?>(initialValue = null, file) {
        value = runCatching {
            require(file.isFile) { "附件文件不存在" }
            if (attachment.kind == AttachmentKind.PDF.name) pdfPageCount(file) else 1
        }
    }
    val pageCount = pageCountResult?.getOrNull() ?: 0
    val bitmapResult by produceState<Result<Bitmap?>?>(initialValue = null, file, page, pageCount) {
        value = if (pageCount <= 0) {
            Result.failure(IllegalArgumentException("附件无法读取"))
        } else {
            runCatching {
                val rendered = if (attachment.kind == AttachmentKind.PDF.name) renderPdf(file, page) else decodeImage(file)
                requireNotNull(rendered) { "附件格式损坏或手机内存不足" }
            }
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offsetX += pan.x
        offsetY += pan.y
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(attachment.displayName, maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 620.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFF1F1F1)), contentAlignment = Alignment.Center) {
                    bitmapResult?.getOrNull()?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = attachment.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                                .transformable(transform)
                        )
                    } ?: if (pageCountResult == null || bitmapResult == null) {
                        CircularProgressIndicator()
                    } else {
                        Text("附件损坏、缺失或暂时无法读取", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (attachment.kind == AttachmentKind.PDF.name) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { page = (page - 1).coerceAtLeast(0); scale = 1f }, enabled = page > 0) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一页")
                        }
                        Text("${page + 1} / $pageCount")
                        IconButton(onClick = { page = (page + 1).coerceAtMost(pageCount - 1); scale = 1f }, enabled = page + 1 < pageCount) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "下一页")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private suspend fun decodeImage(file: File): Bitmap? = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

private suspend fun pdfPageCount(file: File): Int = withContext(Dispatchers.IO) {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { it.pageCount.coerceAtLeast(1) }
    }
}

private suspend fun renderPdf(file: File, pageIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(pageIndex.coerceIn(0, renderer.pageCount - 1)).use { page ->
                val scale = min(1600f / page.width, 2000f / page.height).coerceAtLeast(0.1f)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }
}
