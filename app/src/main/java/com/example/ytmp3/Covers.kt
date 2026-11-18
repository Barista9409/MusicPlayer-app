package com.example.ytmp3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.widget.ImageView
import coil.dispose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** プレイリストのジャケット: 違うアルバムの 4 枚を 2×2 に並べる(足りなければ 1 枚) */
object Covers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cache = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun pathsFor(tracks: List<Track>): List<String> =
        tracks.filter { it.thumbPath != null }.distinctBy { it.album.ifBlank { it.thumbPath!! } }.map { it.thumbPath!! }.take(8)

    fun bind(view: ImageView, tracks: List<Track>, sizePx: Int) {
        val paths = pathsFor(tracks)
        val key = paths.joinToString("|") + "@" + sizePx
        view.tag = key
        if (paths.isEmpty()) { Thumbs.bind(view, null); return }
        cache.get(key)?.let { view.dispose(); view.setImageBitmap(it); return }
        if (paths.size < 4) { Thumbs.bind(view, paths.first()); return }
        scope.launch {
            // 同じ絵(アルバムの曲をオンラインで追加した時など、ファイルは別でも中身が同じ)なら 1 枚だけ
            val distinct = withContext(Dispatchers.IO) { distinctImages(tracks) }
            if (view.tag != key) return@launch
            if (distinct.size < 4) { Thumbs.bind(view, distinct.firstOrNull() ?: paths.first()); return@launch }
            val bmp = withContext(Dispatchers.IO) { runCatching { mosaic(distinct.take(4), sizePx) }.getOrNull() } ?: return@launch
            cache.put(key, bmp)
            if (view.tag == key) { view.dispose(); view.setImageBitmap(bmp) }
        }
    }

    /**
     * 画像の場所(端末)か URL(ネット)を 4 つ並べて 1 枚に(あなたのミックスの、今の中身のジャケット)。
     * 4 つそろわなければ 1 枚目だけ
     */
    fun bindSources(view: ImageView, sources: List<String>, sizePx: Int) {
        val key = "src:" + sources.joinToString("|") + "@" + sizePx
        view.tag = key
        cache.get(key)?.let { view.dispose(); view.setImageBitmap(it); return }
        val first = sources.firstOrNull()
        val showFirst = { if (first == null || !first.startsWith("http")) Thumbs.bind(view, first) else Thumbs.bindUrl(view, first) }
        if (sources.size < 4) { showFirst(); return }
        showFirst()
        val ctx = view.context.applicationContext
        scope.launch {
            val bmps = withContext(Dispatchers.IO) {
                sources.take(4).map { s ->
                    runCatching {
                        if (s.startsWith("http")) {
                            val req = coil.request.ImageRequest.Builder(ctx).data(s).size(sizePx / 2).allowHardware(false).build()
                            (coil.Coil.imageLoader(ctx).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        } else BitmapFactory.decodeFile(s, BitmapFactory.Options().apply { inSampleSize = 2 })
                    }.getOrNull()
                }
            }
            if (view.tag != key || bmps.any { it == null }) return@launch
            val bmp = withContext(Dispatchers.Default) { runCatching { mosaicOf(bmps.filterNotNull(), sizePx) }.getOrNull() } ?: return@launch
            cache.put(key, bmp)
            if (view.tag == key) { view.dispose(); view.setImageBitmap(bmp) }
        }
    }

    private fun mosaicOf(bitmaps: List<Bitmap>, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val half = size / 2
        bitmaps.take(4).forEachIndexed { i, b ->
            val side = minOf(b.width, b.height)
            val src = Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
            val x = (i % 2) * half; val y = (i / 2) * half
            c.drawBitmap(b, src, Rect(x, y, x + half, y + half), null)
        }
        return out
    }

    /** 見た目のちがうジャケットだけ(小さく縮めた明るさの並びで比べる。最大 4 枚) */
    private fun distinctImages(tracks: List<Track>): List<String> {
        val out = mutableListOf<Pair<String, Long>>()
        for (p in tracks.mapNotNull { it.thumbPath }.distinct()) {
            if (out.size >= 4) break
            val h = fingerprint(p) ?: continue
            if (out.none { java.lang.Long.bitCount(it.second xor h) <= 6 }) out += p to h
        }
        return out.map { it.first }
    }

    private val prints = HashMap<String, Long>()

    /** 8×8 に縮めて、平均より明るいかどうかの 64 ビット */
    private fun fingerprint(path: String): Long? = synchronized(prints) { prints[path] } ?: runCatching {
        val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return null
        val small = Bitmap.createScaledBitmap(b, 8, 8, true)
        val px = IntArray(64).also { small.getPixels(it, 0, 8, 0, 0, 8, 8) }
        val lum = px.map { (it shr 16 and 0xff) * 3 + (it shr 8 and 0xff) * 6 + (it and 0xff) }
        val avg = lum.average()
        var h = 0L
        lum.forEachIndexed { i, v -> if (v > avg) h = h or (1L shl i) }
        h
    }.getOrNull()?.also { synchronized(prints) { prints[path] = it } }

    private fun mosaic(paths: List<String>, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val half = size / 2
        paths.forEachIndexed { i, p ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val b = BitmapFactory.decodeFile(p, opts) ?: return@forEachIndexed
            val side = minOf(b.width, b.height)
            val src = Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
            val x = (i % 2) * half; val y = (i / 2) * half
            c.drawBitmap(b, src, Rect(x, y, x + half, y + half), null)
        }
        return out
    }
}
