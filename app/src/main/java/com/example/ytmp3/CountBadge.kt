package com.example.ytmp3

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** アイコンの右上に重ねる小さな数字のバッジ */
class CountBadge(private val text: String, bg: Int, fg: Int, textSize: Float) : Drawable() {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fg; this.textSize = textSize; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = RectF(b)
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, bgPaint)
        val y = r.centerY() - (fgPaint.descent() + fgPaint.ascent()) / 2
        canvas.drawText(text, r.centerX(), y, fgPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
