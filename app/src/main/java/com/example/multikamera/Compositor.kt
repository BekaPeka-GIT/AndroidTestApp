package com.example.multikamera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/** Rysuje obraz z kilku kamer w siatce (podział ekranu). */
class Compositor {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val labelBg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val linePaint = Paint().apply { color = Color.rgb(30, 30, 30) }
    private val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 40, 40) }
    private val matrix = Matrix()

    /** Liczba kolumn i wierszy dla n kamer: 1 -> 1x1, 2 -> 2x1, 3-4 -> 2x2, 5-6 -> 3x2, 7-9 -> 3x3 ... */
    private fun grid(n: Int): Pair<Int, Int> {
        if (n <= 1) return 1 to 1
        if (n == 2) return 2 to 1
        val cols = ceil(sqrt(n.toDouble())).toInt()
        val rows = ceil(n / cols.toDouble()).toInt()
        return cols to rows
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        sources: List<FrameSource>,
        showLabels: Boolean,
        recordingSeconds: Long? = null,
    ) {
        canvas.drawColor(Color.BLACK)
        textPaint.textSize = height / 24f
        if (sources.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Brak kamer", width / 2f, height / 2f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
        val (cols, rows) = grid(sources.size)
        val cellW = width / cols.toFloat()
        val cellH = height / rows.toFloat()
        val cell = RectF()
        sources.forEachIndexed { i, source ->
            val c = i % cols
            val r = i / cols
            // Ostatni, niepełny wiersz wyśrodkowujemy.
            val inRow = if (r == rows - 1) sources.size - r * cols else cols
            val offset = (cols - inRow) * cellW / 2f
            cell.set(offset + c * cellW, r * cellH, offset + (c + 1) * cellW, (r + 1) * cellH)
            source.withFrame { bmp, rotation ->
                if (bmp != null) {
                    val rotated = rotation % 180 != 0
                    val bw = if (rotated) bmp.height else bmp.width
                    val bh = if (rotated) bmp.width else bmp.height
                    val scale = min(cell.width() / bw, cell.height() / bh)
                    matrix.reset()
                    matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                    matrix.postRotate(rotation.toFloat())
                    matrix.postScale(scale, scale)
                    matrix.postTranslate(cell.centerX(), cell.centerY())
                    canvas.save()
                    canvas.clipRect(cell)
                    canvas.drawBitmap(bmp, matrix, bitmapPaint)
                    canvas.restore()
                } else {
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("Czekam na obraz…", cell.centerX(), cell.centerY(), textPaint)
                    textPaint.textAlign = Paint.Align.LEFT
                }
            }
            if (showLabels) {
                val pad = textPaint.textSize / 3
                val tw = textPaint.measureText(source.label)
                canvas.drawRect(
                    cell.left, cell.bottom - textPaint.textSize - 2 * pad,
                    cell.left + tw + 2 * pad, cell.bottom, labelBg
                )
                canvas.drawText(source.label, cell.left + pad, cell.bottom - pad - textPaint.descent(), textPaint)
            }
        }
        // Cienkie linie między okienkami.
        for (c in 1 until cols) canvas.drawRect(c * cellW - 1, 0f, c * cellW + 1, height.toFloat(), linePaint)
        for (r in 1 until rows) canvas.drawRect(0f, r * cellH - 1, width.toFloat(), r * cellH + 1, linePaint)

        if (recordingSeconds != null) {
            val s = textPaint.textSize
            canvas.drawCircle(s, s, s / 2.5f, recPaint)
            val text = "REC %02d:%02d".format(recordingSeconds / 60, recordingSeconds % 60)
            canvas.drawText(text, s * 1.8f, s * 1.35f, textPaint)
        }
    }
}
