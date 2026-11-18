package com.example.ytmp3

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** アイコンを囲む進み具合の輪(0〜1) */
class ProgressRing(private val fraction: Float, track: Int, color: Int, private val stroke: Float) : Drawable() {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; this.color = track }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; this.color = color; strokeCap = Paint.Cap.ROUND
    }

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds).apply { inset(stroke / 2, stroke / 2) }
        canvas.drawArc(r, 0f, 360f, false, trackPaint)
        canvas.drawArc(r, -90f, 360f * fraction.coerceIn(0.02f, 1f), false, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
