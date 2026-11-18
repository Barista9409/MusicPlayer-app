package com.example.ytmp3

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「No Good」: おすすめに出さない曲・アーティスト。
 * ホームのおすすめの曲・おすすめのアーティストで「No Good」にしたものは、もう表示しない。
 * 設定 → ライブラリ →「No Good にしたもの」で見て、解除できる
 */
object NoGood {
    data class Entry(val key: String, val name: String, val sub: String)

    /** 曲(動画 ID → 曲名・アーティスト) */
    val songs = MutableStateFlow<List<Entry>>(emptyList())
    /** アーティスト(チャンネル ID → 名前) */
    val artists = MutableStateFlow<List<Entry>>(emptyList())
    private var file: File? = null

    fun init(c: Context) {
        val f = File(c.filesDir, "no_good.json")
        file = f
        runCatching {
            val o = JSONObject(f.readText())
            songs.value = read(o.optJSONArray("songs"))
            artists.value = read(o.optJSONArray("artists"))
        }
    }

    private fun read(a: JSONArray?): List<Entry> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
        .map { Entry(it.optString("key"), it.optString("name"), it.optString("sub")) }

    private fun save() {
        fun arr(l: List<Entry>) = JSONArray().apply { l.forEach { put(JSONObject().put("key", it.key).put("name", it.name).put("sub", it.sub)) } }
        runCatching { file?.writeText(JSONObject().put("songs", arr(songs.value)).put("artists", arr(artists.value)).toString()) }
    }

    fun isSong(vid: String?) = vid != null && songs.value.any { it.key == vid }
    /** アーティストは ID でも名前でも(曲のアーティスト名と照らすため) */
    fun isArtist(idOrName: String?): Boolean {
        val k = idOrName?.trim()?.lowercase() ?: return false
        return artists.value.any { it.key.lowercase() == k || it.name.trim().lowercase() == k }
    }

    fun addSong(r: Searcher.Result) {
        val vid = r.videoId ?: return
        if (isSong(vid)) return
        songs.value = listOf(Entry(vid, r.title, r.artist)) + songs.value
        save()
    }

    fun addArtist(id: String, name: String) {
        if (artists.value.any { it.key == id }) return
        artists.value = listOf(Entry(id, name, "")) + artists.value
        save()
    }

    fun removeSongs(keys: Set<String>) { songs.value = songs.value.filterNot { it.key in keys }; save() }
    fun removeArtists(keys: Set<String>) { artists.value = artists.value.filterNot { it.key in keys }; save() }
}
