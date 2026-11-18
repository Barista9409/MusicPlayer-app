package com.example.ytmp3

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 同じ曲の別の動画 ID の対応(元の ID → 代わりに使った ID)。
 * 年齢確認が要る MV などを別の音源でダウンロード・再生した時に、元の ID からもライブラリの曲を見つけられるように。
 */
object VideoAlias {
    private var file: File? = null
    private val map = HashMap<String, String>()

    fun init(c: Context) {
        file = File(c.filesDir, "video_alias.json")
        runCatching {
            val o = JSONObject(file!!.readText())
            o.keys().forEach { k -> map[k] = o.getString(k) }
        }
    }

    @Synchronized
    fun put(orig: String, alt: String) {
        if (orig == alt) return
        map[orig] = alt
        runCatching { file?.writeText(JSONObject(map as Map<*, *>).toString()) }
    }

    @Synchronized
    fun get(orig: String?): String? = orig?.let { map[it] }

    /** この動画 ID の曲がライブラリにあればその曲(別の音源で保存したものも含む) */
    fun trackFor(vid: String?, includeMissing: Boolean = false): Track? {
        if (vid == null) return null
        // 索引から引く(ライブラリ全体を毎回探さない)
        return listOfNotNull(vid, get(vid)).asSequence().flatMap { Repository.tracksByVideo(it).asSequence() }
            .firstOrNull { includeMissing || !Repository.cantPlay(it.id) }
    }

    /** ライブラリにある動画 ID(別の音源で保存した曲は元の ID も含む) */
    fun ownedIds(tracks: List<Track> = Repository.tracks.value): Set<String> {
        val have = tracks.mapNotNull { youtubeVideoId(it.sourceUrl) }.toMutableSet()
        synchronized(this) { map.forEach { (orig, alt) -> if (alt in have) have += orig } }
        return have
    }
}
