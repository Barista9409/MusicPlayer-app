package com.example.ytmp3

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 一度聴いた試聴曲を一時保存(初めは最大 500MB。設定で変更可。古いものから消える)。
 * 同じ曲をもう一度聴く時は通信せずにすぐ流れる。曲ごと(動画 ID)に保存するので URL が変わっても使える。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object StreamCache {
    private var cache: SimpleCache? = null
    private var evictor: Evictor? = null

    /** 上限(バイト。0 以下 = 無制限)。設定 → ライブラリ → ストレージ で変えられて、すぐ効く */
    private fun limitBytes(c: Context): Long = StorageSheet.cacheLimitMb(c).toLong() * 1024 * 1024

    @Synchronized
    fun get(c: Context): SimpleCache = cache ?: run {
        val app = c.applicationContext
        val ev = Evictor { limitBytes(app) }
        evictor = ev
        SimpleCache(File(app.cacheDir, "stream_cache"), ev, StandaloneDatabaseProvider(app))
    }.also { cache = it }

    /** 上限を下げた時: 古いものから消して上限内に */
    fun trim(c: Context) {
        val cc = get(c)
        Thread { runCatching { synchronized(cc) { evictor?.evict(cc, 0) } } }.start()
    }

    /** 古いもの(最後に使った時が古い順)から消す。上限は毎回読み直す */
    private class Evictor(val maxBytes: () -> Long) : androidx.media3.datasource.cache.CacheEvictor {
        private val spans = java.util.TreeSet<androidx.media3.datasource.cache.CacheSpan> { a, b ->
            val d = a.lastTouchTimestamp - b.lastTouchTimestamp
            if (d == 0L) a.compareTo(b) else if (d < 0) -1 else 1
        }
        private var current = 0L
        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() {}
        override fun onStartFile(cache: androidx.media3.datasource.cache.Cache, key: String, position: Long, length: Long) {
            if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length)
        }
        override fun onSpanAdded(cache: androidx.media3.datasource.cache.Cache, span: androidx.media3.datasource.cache.CacheSpan) {
            spans.add(span); current += span.length; evict(cache, 0)
        }
        override fun onSpanRemoved(cache: androidx.media3.datasource.cache.Cache, span: androidx.media3.datasource.cache.CacheSpan) {
            if (spans.remove(span)) current -= span.length
        }
        override fun onSpanTouched(cache: androidx.media3.datasource.cache.Cache, oldSpan: androidx.media3.datasource.cache.CacheSpan,
                                   newSpan: androidx.media3.datasource.cache.CacheSpan) {
            onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan)
        }
        fun evict(cache: androidx.media3.datasource.cache.Cache, required: Long) {
            val max = maxBytes()
            if (max <= 0) return
            while (current + required > max && spans.isNotEmpty()) {
                runCatching { cache.removeSpan(spans.first()) }.onFailure { spans.pollFirst() }
            }
        }
    }

    /** 試聴(ytstream://v/<ID>)は ID ごとに保存。それ以外は URL ごと */
    fun factory(c: Context, upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(c))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec ->
                if (spec.uri.scheme == PlayerConnection.STREAM_SCHEME) "yt:" + (spec.uri.lastPathSegment ?: spec.uri.toString())
                else spec.key ?: spec.uri.toString()
            }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** この曲(動画 ID)の大部分が一時保存済みか(オフラインでも聴けそうか) */
    fun isCached(c: Context, vid: String?): Boolean {
        if (vid == null) return false
        return runCatching {
            val cc = get(c)
            val key = "yt:" + (VideoAlias.get(vid) ?: vid)
            val len = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cc.getContentMetadata(key))
            len > 0 && cc.getCachedBytes(key, 0, len) >= len * 9 / 10
        }.getOrDefault(false)
    }

    /** この曲の頭から、途切れずに一時保存されているバイト数(通信しない) */
    fun headBytes(c: Context, vid: String): Long = runCatching {
        get(c).getCachedLength("yt:" + (VideoAlias.get(vid) ?: vid), 0, 2_000_000L).coerceAtLeast(0L)
    }.getOrDefault(0L)

    /**
     * 一時保存から、この曲の頭の部分(途切れずに残っている分を最大 max バイト)を dest に書き出す(曲調の分析用)。
     * 書き出したバイト数。残っていなければ 0(通信はしない)
     */
    fun copyHead(c: Context, vid: String, max: Int, dest: File): Int = runCatching {
        val cc = get(c)
        val key = "yt:" + (VideoAlias.get(vid) ?: vid)
        val have = cc.getCachedLength(key, 0, max.toLong())
        if (have <= 0) return 0
        // 上流なし = 一時保存だけを読む
        val ds = CacheDataSource(cc, null)
        try {
            ds.open(androidx.media3.datasource.DataSpec.Builder()
                .setUri(android.net.Uri.parse("${PlayerConnection.STREAM_SCHEME}://v/$vid")).setKey(key)
                .setPosition(0).setLength(have).build())
            var total = 0
            dest.outputStream().use { out ->
                val buf = ByteArray(32 * 1024)
                while (total < have) {
                    val n = ds.read(buf, 0, minOf(buf.size.toLong(), have - total).toInt()); if (n < 0) break
                    out.write(buf, 0, n); total += n
                }
            }
            total
        } finally { runCatching { ds.close() } }
    }.getOrDefault(0)

    fun sizeMb(c: Context): Long = runCatching { get(c).cacheSpace / (1024 * 1024) }.getOrDefault(0)

    fun clear(c: Context) {
        val cc = get(c)
        runCatching { cc.keys.toList().forEach { cc.removeResource(it) } }
    }
}
