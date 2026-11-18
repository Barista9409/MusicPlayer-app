package com.example.ytmp3

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/**
 * 実際に聴いた時間(再生中だった時間)を日ごとに記録する。
 * 「聴いた記録」の時間はこれで出す(曲の長さ × 回数だと、長い曲を少し聴いて止めた時も全部数えてしまうため)
 */
object ListenTime {
    private var file: File? = null
    /** 日(端末の時刻での通し番号) → ミリ秒 */
    private val days = HashMap<Long, Long>()
    /** 記録を始めた時刻(これより前は分からないので推定) */
    @Volatile var since: Long = 0L
        private set
    private var dirty = false
    private var lastSave = 0L

    fun init(c: Context) {
        val f = File(c.filesDir, "listen_time.json")
        file = f
        runCatching {
            val o = JSONObject(f.readText())
            since = o.optLong("since")
            val d = o.optJSONObject("days")
            d?.keys()?.forEach { k -> days[k.toLong()] = d.optLong(k) }
        }
        if (since == 0L) { since = System.currentTimeMillis(); dirty = true; save() }
    }

    /** 端末の時刻での日の通し番号 */
    fun dayOf(time: Long): Long = Math.floorDiv(time + TimeZone.getDefault().getOffset(time), 86_400_000L)

    /** 再生中に聴いた分を足す(再生サービスから 2 秒ごと) */
    @Synchronized
    fun add(ms: Long, now: Long = System.currentTimeMillis()) {
        if (ms <= 0 || ms > 60_000) return  // 止まっていた間の大きな差は数えない
        val d = dayOf(now)
        days[d] = (days[d] ?: 0L) + ms
        dirty = true
        if (now - lastSave > 30_000) save()
    }

    /** from〜to の日に聴いた時間(日単位で数える) */
    @Synchronized
    fun sum(from: Long, to: Long = System.currentTimeMillis()): Long {
        val a = dayOf(from); val b = dayOf(to)
        return days.entries.sumOf { (d, ms) -> if (d in a..b) ms else 0L }
    }

    @Synchronized
    fun save() {
        if (!dirty) return
        val f = file ?: return
        // 400 日より前は消す
        val cut = dayOf(System.currentTimeMillis()) - 400
        days.keys.filter { it < cut }.forEach { days.remove(it) }
        val d = JSONObject()
        days.forEach { (k, v) -> d.put(k.toString(), v) }
        runCatching { f.writeText(JSONObject().put("since", since).put("days", d).toString()) }
        dirty = false
        lastSave = System.currentTimeMillis()
    }
}
