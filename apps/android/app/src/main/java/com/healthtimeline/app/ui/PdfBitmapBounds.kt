package com.healthtimeline.app.ui

import kotlin.math.min

internal data class PdfBitmapSize(val width: Int, val height: Int)

/** Bound decoded pixels, not file bytes: a tiny PDF can describe an enormous page. */
internal fun boundedPdfBitmapSize(pageWidth: Int, pageHeight: Int): PdfBitmapSize {
    require(pageWidth > 0 && pageHeight > 0) { "PDF 页面尺寸无效" }
    val scale = min(1600.0 / pageWidth, 2000.0 / pageHeight)
    val width = (pageWidth * scale).toInt().coerceIn(1, 1600)
    val height = (pageHeight * scale).toInt().coerceIn(1, 2000)
    require(width.toLong() * height <= 3_200_000L) { "PDF 页面超过安全渲染上限" }
    return PdfBitmapSize(width, height)
}
