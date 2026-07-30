/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.uwuaosp.prism

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class RegionKind {
    Text,
    Image,
}

internal data class SelectionRegion(
    val id: Long,
    val kind: RegionKind,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun normalized(): SelectionRegion = copy(
        left = min(left, right).coerceIn(0f, 1f),
        top = min(top, bottom).coerceIn(0f, 1f),
        right = max(left, right).coerceIn(0f, 1f),
        bottom = max(top, bottom).coerceIn(0f, 1f),
    )
}

internal data class ProcessedRegion(
    val selection: SelectionRegion,
    val bitmap: Bitmap,
    val text: String? = null,
)

internal fun cropBitmap(source: Bitmap, selection: SelectionRegion): Bitmap {
    val region = selection.normalized()
    val left = (region.left * source.width).toInt().coerceIn(0, source.width - 1)
    val top = (region.top * source.height).toInt().coerceIn(0, source.height - 1)
    val right = (region.right * source.width).toInt().coerceIn(left + 1, source.width)
    val bottom = (region.bottom * source.height).toInt().coerceIn(top + 1, source.height)
    return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
}

internal suspend fun writeTemporaryCrop(
    context: Context,
    bitmap: Bitmap,
    requestId: Long,
): File = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "ocr-crops").apply { mkdirs() }
    val output = File(directory, "region-$requestId.png")
    FileOutputStream(output).use { stream ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
    }
    output
}

internal fun clearTemporaryCrops(context: Context) {
    File(context.cacheDir, "ocr-crops").listFiles()?.forEach { file ->
        if (file.isFile) file.delete()
    }
}

internal suspend fun saveRegionsToPictures(
    context: Context,
    source: Bitmap,
    selections: List<SelectionRegion>,
): Int = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val timestamp = LocalDateTime.now().format(FILE_TIME_FORMAT)
    var saved = 0
    selections.sortedBy { it.id }.forEachIndexed { index, selection ->
        val bitmap = cropBitmap(source, selection)
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "uwuPrism-$timestamp-${index + 1}.png",
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/uwuPrism",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: error("Cannot create image")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            } ?: error("Cannot open image destination")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            saved += 1
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        } finally {
            bitmap.recycle()
        }
    }
    saved
}

internal suspend fun writeTextExport(
    context: Context,
    uri: Uri,
    regions: List<ProcessedRegion>,
) = withContext(Dispatchers.IO) {
    val text = regions
        .sortedBy { it.selection.id }
        .mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString(separator = "\n\n")
    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
        it.write(text)
    } ?: error("Cannot open text destination")
}

internal suspend fun writePdfExport(
    context: Context,
    uri: Uri,
    regions: List<ProcessedRegion>,
) = withContext(Dispatchers.IO) {
    val document = PdfDocument()
    try {
        var pageNumber = 1
        regions.sortedBy { it.selection.id }.forEach { region ->
            if (region.selection.kind == RegionKind.Text) {
                pageNumber += appendTextPages(
                    document,
                    region.text.orEmpty(),
                    pageNumber,
                )
            } else {
                val pageInfo = PdfDocument.PageInfo.Builder(
                    PDF_WIDTH,
                    PDF_HEIGHT,
                    pageNumber,
                ).create()
                val page = document.startPage(pageInfo)
                try {
                    drawImagePage(page.canvas, region.bitmap)
                } finally {
                    document.finishPage(page)
                }
                pageNumber += 1
            }
        }
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            document.writeTo(output)
        } ?: error("Cannot open PDF destination")
    } finally {
        document.close()
    }
}

private fun appendTextPages(
    document: PdfDocument,
    text: String,
    firstPageNumber: Int,
): Int {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 15f
    }
    val lines = wrapText(text, paint, PDF_WIDTH - PDF_MARGIN * 2)
    val lineHeight = paint.fontSpacing
    val linesPerPage = (
        (PDF_HEIGHT - PDF_MARGIN * 2) / lineHeight
        ).toInt().coerceAtLeast(1)
    val pages = lines.chunked(linesPerPage).ifEmpty { listOf(emptyList()) }
    pages.forEachIndexed { index, pageLines ->
        val pageInfo = PdfDocument.PageInfo.Builder(
            PDF_WIDTH,
            PDF_HEIGHT,
            firstPageNumber + index,
        ).create()
        val page = document.startPage(pageInfo)
        try {
            page.canvas.drawColor(Color.WHITE)
            var y = PDF_MARGIN - paint.fontMetrics.ascent
            pageLines.forEach { line ->
                if (line.isNotEmpty()) {
                    page.canvas.drawText(line, PDF_MARGIN.toFloat(), y, paint)
                }
                y += lineHeight
            }
        } finally {
            document.finishPage(page)
        }
    }
    return pages.size
}

private fun wrapText(text: String, paint: Paint, maxWidth: Int): List<String> {
    val lines = mutableListOf<String>()
    text.lineSequence().forEach { paragraph ->
        if (paragraph.isEmpty()) {
            lines += ""
            return@forEach
        }
        var start = 0
        while (start < paragraph.length) {
            val remaining = paragraph.substring(start)
            var count = paint.breakText(
                remaining,
                true,
                maxWidth.toFloat(),
                null,
            ).coerceAtLeast(1)
            if (start + count < paragraph.length) {
                val whitespace = remaining
                    .substring(0, count)
                    .indexOfLast(Char::isWhitespace)
                if (whitespace > 0) {
                    count = whitespace
                }
            }
            lines += remaining.substring(0, count).trimEnd()
            start += count
            while (start < paragraph.length && paragraph[start].isWhitespace()) {
                start += 1
            }
        }
    }
    return lines
}

private fun drawImagePage(canvas: Canvas, bitmap: Bitmap) {
    canvas.drawColor(Color.WHITE)
    val availableWidth = (PDF_WIDTH - PDF_MARGIN * 2).toFloat()
    val availableHeight = (PDF_HEIGHT - PDF_MARGIN * 2).toFloat()
    val scale = min(availableWidth / bitmap.width, availableHeight / bitmap.height)
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    val target = RectF(
        (PDF_WIDTH - width) / 2f,
        (PDF_HEIGHT - height) / 2f,
        (PDF_WIDTH + width) / 2f,
        (PDF_HEIGHT + height) / 2f,
    )
    canvas.drawBitmap(bitmap, null as Rect?, target, Paint(Paint.ANTI_ALIAS_FLAG))
}

private val FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private const val PDF_WIDTH = 595
private const val PDF_HEIGHT = 842
private const val PDF_MARGIN = 42
