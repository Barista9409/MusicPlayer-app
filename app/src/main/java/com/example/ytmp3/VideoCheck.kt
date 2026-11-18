package com.example.ytmp3

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 曲に動画(MV)があるかの記録。「動画を保存」を、動画がある曲だけ押せるようにするため。
 * 動画 ID → 映像のある動画の ID(無ければ "")。調べたことのない曲は「分からない」
 */
object VideoCheck {
    private val map = HashMap<String, String>()
    private var file: File? = null

    fun init(c: Context) {
        val f = File(c.filesDir, "video_check.json")
        file = f
        runCatching {
            val o = JSONObject(f.readText())
            synchronized(map) { o.keys().forEach { k -> map[k] = o.optString(k) } }
        }
    }

    /** true = 動画あり / false = 動画なし(静止画だけ) / null = まだ分からない */
    fun known(vid: String?): Boolean? {
        vid ?: return null
        val v = synchronized(map) { map[vid] } ?: return null
        return v.isNotEmpty()
    }

    fun put(vid: String, mv: String?) {
        synchronized(map) {
            map[vid] = mv.orEmpty()
            // 多すぎたら古い方から(並びは保証されないが、だいたいで十分)
            if (map.size > 3000) map.keys.take(map.size - 2500).forEach { map.remove(it) }
            runCatching { file?.writeText(JSONObject(map as Map<*, *>).toString()) }
        }
    }

    /** 調べる(結果は覚えておく)。動画があれば true */
    suspend fun check(vid: String): Boolean? {
        known(vid)?.let { return it }
        val (mv, sure) = withContext(Dispatchers.IO) { runCatching { InnerTube.musicVideoLookup(vid) }.getOrDefault(vid to false) }
        if (!sure) return null
        put(vid, mv)
        return mv != null
    }
}
