package com.example.ytmp3

import android.content.Context
import org.json.JSONArray

/**
 * あなたのミックスで流れた曲の記録(ネットの曲も含む。途中で飛ばした曲も「流れた」とする)。
 * 次にミックスを作る時、最近流れた曲を外すのに使う(同じ曲ばかり出てこないように)。
 *  - どの種類でも、直近 100 曲に流れた曲
 *  - 似た曲は 3 日・新曲は 1 日・よく聴く曲は 12 時間以内に流れた曲
 * 3 つのミックスで共通。7 日・500 曲より古いものは捨てる
 */
object MixHistory {
    private var file: java.io.File? = null
    /** (曲の鍵, 流れた時刻) の並び(古い順) */
    private val entries = ArrayList<Pair<List<String>, Long>>()

    const val LAST_COUNT = 100
    const val SIMILAR_MS = 3 * 86_400_000L
    const val NEW_MS = 86_400_000L
    const val FAV_MS = 12 * 3_600_000L
    /** 「新曲をたくさん」の時の新曲は、直近 30 曲に流れたものだけ外す(1 日の決まりはかけない) */
    const val NEW_RECENT_WHEN_MORE = 30
    private const val KEEP_MS = 7 * 86_400_000L
    private const val KEEP_COUNT = 500

    fun init(c: Context) {
        file = java.io.File(c.filesDir, "mix_history.json")
        runCatching {
            val a = JSONArray(file!!.readText())
            synchronized(entries) {
                for (i in 0 until a.length()) {
                    val o = a.optJSONArray(i) ?: continue
                    val keys = o.optJSONArray(0) ?: continue
                    entries += List(keys.length()) { keys.optString(it) } to o.optLong(1)
                }
            }
        }
    }

    /** ミックスの曲が流れ始めた */
    fun played(keys: List<String>, now: Long = System.currentTimeMillis()) {
        if (keys.isEmpty()) return
        synchronized(entries) {
            entries += keys to now
            entries.removeAll { now - it.second > KEEP_MS }
            while (entries.size > KEEP_COUNT) entries.removeAt(0)
        }
        save()
    }

    private fun save() {
        val f = file ?: return // 保存先がまだ無い(起動前・テスト)
        runCatching {
            val a = JSONArray()
            synchronized(entries) { entries.forEach { (k, t) -> a.put(JSONArray().put(JSONArray(k)).put(t)) } }
            f.writeText(a.toString())
        }
    }

    /** 今の記録の写し(ミックスを作る時に 1 回だけ取る) */
    fun snapshot(): Snapshot = synchronized(entries) { Snapshot(entries.toList()) }

    class Snapshot internal constructor(private val list: List<Pair<List<String>, Long>>) {
        private val lastKeys: Set<String> = list.takeLast(LAST_COUNT).flatMap { it.first }.toSet()
        /** 鍵 → 最後に流れた時刻 */
        private val lastTime: Map<String, Long> = HashMap<String, Long>().apply {
            list.forEach { (keys, t) -> keys.forEach { k -> if ((this[k] ?: 0L) < t) this[k] = t } }
        }

        /** 直近 n 曲の中に流れたか */
        fun inLast(keys: List<String>, n: Int): Boolean {
            val recent = list.takeLast(n)
            return recent.any { (k, _) -> k.any { it in keys } }
        }

        /** 最後に流れた時刻(流れていなければ 0) */
        fun lastPlayed(keys: List<String>): Long = keys.maxOfOrNull { lastTime[it] ?: 0L } ?: 0L

        /** 外すか: 直近 100 曲に入っている、または window(ミリ秒)以内に流れた */
        fun blocked(keys: List<String>, windowMs: Long, now: Long = System.currentTimeMillis()): Boolean =
            keys.any { it in lastKeys } || lastPlayed(keys).let { it > 0 && now - it < windowMs }
    }
}
