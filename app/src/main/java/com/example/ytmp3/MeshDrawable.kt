package com.example.ytmp3

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * メッシュグラデーション(オーロラ風)の背景。色の丸をぼかして重ねる。
 * 画面の大きさに合わせてその場で描くので、どの機種でもゆがまない。
 */
class MeshDrawable(private val mesh: Skin.Mesh) : Drawable() {
    /** 360×780 の画面での (x, y, 半径) */
    private val spots = listOf(
        floatArrayOf(60f, 120f, 170f), floatArrayOf(310f, 300f, 190f), floatArrayOf(80f, 520f, 180f),
        floatArrayOf(300f, 720f, 170f), floatArrayOf(200f, 420f, 120f),
    )
    private val paints = List(spots.size) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val centers = Array(spots.size) { FloatArray(3) }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        if (b.isEmpty) return
        val sx = b.width() / 360f
        val sy = b.height() / 780f
        val s = (sx + sy) / 2f
        spots.forEachIndexed { i, sp ->
            val c = mesh.colors[i % mesh.colors.size]
            val cx = b.left + sp[0] * sx
            val cy = b.top + sp[1] * sy
            // ぼかした丸の代わりに、中心から外へ透明になる円で描く(ぼかし幅 ≒ 110)
            val r = (sp[2] + 110f) * s
            val solid = ((sp[2] - 70f).coerceAtLeast(0f) * s / r)
            val mid = (sp[2] * s / r).coerceIn(solid + 0.01f, 0.99f)
            paints[i].shader = RadialGradient(cx, cy, r,
                intArrayOf(c, c, (c and 0x00FFFFFF) or (0x80 shl 24), c and 0x00FFFFFF),
                floatArrayOf(0f, solid, mid, 1f), Shader.TileMode.CLAMP)
            centers[i][0] = cx; centers[i][1] = cy; centers[i][2] = r
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(mesh.bg)
        if (bounds.isEmpty) return
        for (i in spots.indices) canvas.drawCircle(centers[i][0], centers[i][1], centers[i][2], paints[i])
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        fun c(s: String) = Color.parseColor(s)
    }
}
