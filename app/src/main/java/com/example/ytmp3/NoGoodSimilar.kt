package com.example.ytmp3

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * No Good にしたものに「似たもの」(あなたのミックス・ラジオで出にくくする。外しはしない)。
 *  - No Good のアーティスト → YouTube Music のアーティストページの「ファンにおすすめ」(似ているアーティスト)
 *  - No Good の曲 → その曲の似た曲(ラジオ)の上位 15 曲
 * 調べた結果は覚えておき、No Good が増えた分だけ調べる(1 件につき問い合わせ 1 回。裏で)
 */
object NoGoodSimilar {
    private var file: java.io.File? = null
    /** No Good の鍵("a:" + アーティストの ID / "s:" + 動画 ID) → 似ているもの(アーティスト名(小文字) か 曲の鍵) */
    private val cache = HashMap<String, List<String>>()
    @Volatile private var running = false
    /** 似たアーティスト(小文字)・似た曲の鍵(すぐ引けるように、まとめたもの) */
    @Volatile private var artistSet: Set<String> = emptySet()
    @Volatile private var songSet: Set<String> = emptySet()

    fun init(c: Context) {
        file = java.io.File(c.filesDir, "nogood_similar.json")
        runCatching {
            val o = JSONObject(file!!.readText())
            synchronized(cache) {
                o.keys().forEach { k -> o.optJSONArray(k)?.let { a -> cache[k] = List(a.length()) { a.optString(it) } } }
            }
        }
        rebuild()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val o = JSONObject()
            synchronized(cache) { cache.forEach { (k, v) -> o.put(k, JSONArray(v)) } }
            f.writeText(o.toString())
        }
    }

    /** 今の No Good にある分だけで、引くための集まりを作り直す(解除した No Good の分は使わない) */
    private fun rebuild() {
        val aKeys = NoGood.artists.value.map { "a:" + it.key }.toSet()
        val sKeys = NoGood.songs.value.map { "s:" + it.key }.toSet()
        synchronized(cache) {
            artistSet = cache.filterKeys { it in aKeys }.values.flatten().toSet()
            songSet = cache.filterKeys { it in sKeys }.values.flatten().toSet()
        }
    }

    /** No Good のアーティストに似ている人か */
    fun similarArtist(artist: String): Boolean {
        val a = Radio.mainArtist(artist).lowercase().trim()
        return a.isNotEmpty() && a in artistSet
    }

    /** No Good の曲に似た曲か */
    fun similarSong(keys: List<String>): Boolean = keys.any { it in songSet }

    /**
     * まだ調べていない No Good を、裏で調べる(通信できる時だけ。1 秒に 1 件ほど)。
     * ミックスを作る時・ラジオを足す時に呼ぶ。結果はその次から効く
     */
    fun refresh() {
        rebuild()
        if (running || !Net.streamAllowed()) return
        val todo = NoGood.artists.value.map { "a:" + it.key to it } + NoGood.songs.value.map { "s:" + it.key to it }
        val missing = synchronized(cache) { todo.filter { it.first !in cache } }
        if (missing.isEmpty()) return
        running = true
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                for ((key, e) in missing) {
                    val found = runCatching { if (key.startsWith("a:")) relatedArtists(e) else similarSongs(e) }.getOrNull() ?: continue
                    synchronized(cache) { cache[key] = found }
                    rebuild()
                    save()
                    Thread.sleep(1000)
                }
            } finally { running = false }
        }, "nogood-similar").start()
    }

    /** 似ているアーティスト(小文字の名前) */
    private fun relatedArtists(e: NoGood.Entry): List<String> {
        // 鍵がチャンネル ID でなければ(名前だけの登録)、名前から探す
        val id = e.key.takeIf { it.startsWith("UC") } ?: InnerTube.findArtist(e.name)?.second ?: return emptyList()
        return InnerTube.artistPage(e.name, id).second.map { Radio.mainArtist(it.name).lowercase().trim() }
            .filter { it.isNotEmpty() && it != Radio.mainArtist(e.name).lowercase().trim() }
    }

    /** 似た曲の鍵(ラジオの上位 15 曲。No Good の曲そのものは除く) */
    private fun similarSongs(e: NoGood.Entry): List<String> =
        InnerTube.radio(e.key).filter { it.videoId != e.key }.take(15)
            .flatMap { SkipLog.keysOf(it.videoId, it.title, it.artist) }
}
