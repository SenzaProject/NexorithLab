package com.nexo.nexorith.lab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.EditText

/** EditText kode dengan kolom nomor baris yang tetap di kiri saat digulir horizontal. */
class CodeEditText(ctx: Context) : EditText(ctx) {

    var showLineNumbers: Boolean = true
        set(v) {
            field = v
            applyPadding()
            invalidate()
        }

    private val gutterW = ctx.dp(46)
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.dim
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val gutterPaint = Paint().apply { color = Pal.gutterBg }
    private val linePaint = Paint().apply { color = 0x22FFFFFF }

    init { applyPadding() }

    private fun applyPadding() {
        val left = if (showLineNumbers) gutterW + context.dp(10) else context.dp(12)
        setPadding(left, context.dp(12), context.dp(12), context.dp(12))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showLineNumbers) return
        val l = layout ?: return
        val x0 = scrollX.toFloat()
        val top = scrollY.toFloat()
        canvas.drawRect(x0, top, x0 + gutterW, top + height, gutterPaint)
        canvas.drawRect(x0 + gutterW - 1f, top, x0 + gutterW, top + height, linePaint)
        numPaint.textSize = textSize * 0.9f
        val first = l.getLineForVertical(scrollY)
        val last = l.getLineForVertical(scrollY + height)
        for (i in first..last) {
            val y = (l.getLineBaseline(i) + totalPaddingTop).toFloat()
            canvas.drawText((i + 1).toString(), x0 + gutterW - context.dp(8), y, numPaint)
        }
    }
}
