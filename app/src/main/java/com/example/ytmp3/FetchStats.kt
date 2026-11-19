package com.example.ytmp3

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 曲・動画の URL を「どの取り方で」「何秒で」取れたかの記録(最近 100 件。端末の中だけに保存)。
 * 問題を送る画面の「取り方の内訳」で見られる。どの方法が効いているかを調べるため
 */
object FetchStats {
    /** 保存しておく件数(グラフは、このうち曲の最近 100 曲で数える) */
    const val MAX = 300
    const val CHART_SONGS = 100

    data class Entry(
        val at: Long,
        /** 曲 / 動画 */
        val kind: String,
        /** 今流す(待たせている) / 先読み / 用意済み(先読みが間に合った) */
        val mode: String,
        /** 取れた方法(直接・NewPipe・yt-dlp)。取れなかった時は「失敗」 */
        val how: String,
        val ms: Long,
        /** ほかの方法が失敗した理由(「NewPipe 失敗: …」など) */
        val fails: List<String>,
    )

    const val NOW = "今流す"
    const val AHEAD = "先読み"
    const val READY = "用意済み"
    const val FAILED = "失敗"

    private var file: java.io.File? = null
    private val list = ArrayDeque<Entry>()
    /** 用意済みを同じ曲で何度も数えないように(読み込みは 10MB ごとに URL を引くため) */
    private val readyAt = HashMap<String, Long>()

    fun init(c: Context) {
        file = java.io.File(c.filesDir, "fetch_stats.json")
        runCatching {
            val a = JSONArray(file!!.readText())
            synchronized(list) {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val f = o.optJSONArray("f")
                    list.addLast(Entry(o.getLong("t"), o.getString("k"), o.getString("m"), o.getString("h"), o.getLong("ms"),
                        if (f == null) emptyList() else List(f.length()) { f.getString(it) }))
                }
            }
        }
    }

    fun add(kind: String, mode: String, how: String, ms: Long, fails: List<String> = emptyList()) {
        synchronized(list) {
            list.addLast(Entry(System.currentTimeMillis(), kind, mode, how, ms, fails))
            while (list.size > MAX) list.removeFirst()
        }
        save()
    }

    /** 再生の番が来た時に、先読みの URL がもう用意できていた */
    fun ready(vid: String) {
        val now = System.currentTimeMillis()
        synchronized(readyAt) {
            val last = readyAt[vid]
            if (last != null && now - last < 30 * 60_000L) return
            readyAt[vid] = now
            if (readyAt.size > 300) readyAt.entries.removeIf { now - it.value > 30 * 60_000L }
        }
        add("曲", READY, READY, 0)
    }

    fun entries(): List<Entry> = synchronized(list) { list.toList() }

    fun clear() {
        synchronized(list) { list.clear() }
        synchronized(readyAt) { readyAt.clear() }
        save()
    }

    private fun save() {
        val f = file ?: return
        val snapshot = entries()
        Thread {
            runCatching {
                val a = JSONArray()
                snapshot.forEach { e ->
                    a.put(JSONObject().put("t", e.at).put("k", e.kind).put("m", e.mode).put("h", e.how).put("ms", e.ms)
                        .put("f", JSONArray(e.fails)))
                }
                synchronized(this) { f.writeText(a.toString()) }
            }
        }.start()
    }

    private fun sec(ms: Double) = String.format("%.1f秒", ms / 1000)
    private fun pct(n: Int, all: Int) = if (all == 0) "" else "${n * 100 / all}%"

    /** 方法ごとの件数・割合・平均と一番遅い時間 */
    private fun breakdown(es: List<Entry>): String = buildString {
        val all = es.size
        es.groupBy { it.how }.entries.sortedByDescending { it.value.size }.forEach { (how, v) ->
            append("  ${how.padEnd(8, '　')} ${v.size}件 (${pct(v.size, all)})")
            if (how != READY && how != FAILED) {
                val ms = v.map { it.ms.toDouble() }
                append(" 平均 ${sec(ms.average())} · 最長 ${sec(ms.max())}")
            }
            append("\n")
        }
    }

    /** 画面に出す内訳 */
    fun report(): String {
        val es = entries()
        if (es.isEmpty()) return "まだ記録がありません(ネットの曲を流すと記録されます)"
        val fmt = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.JAPAN)
        return buildString {
            append("最近 ${es.size} 件(${fmt.format(es.first().at)} 〜 ${fmt.format(es.last().at)})\n")
            val songs = es.filter { it.kind == "曲" }
            val now = songs.filter { it.mode == NOW || it.mode == READY }
            append("\n■ 曲を流した時(${now.size}件)\n")
            append("  用意済み = 先読みが間に合って、待たずに鳴った\n")
            append(breakdown(now))
            val ahead = songs.filter { it.mode == AHEAD }
            if (ahead.isNotEmpty()) { append("\n■ 曲の先読み(${ahead.size}件)\n"); append(breakdown(ahead)) }
            val videos = es.filter { it.kind == "動画" }
            if (videos.isNotEmpty()) { append("\n■ 動画(${videos.size}件)\n"); append(breakdown(videos)) }
            // 方法ごとの失敗の理由(多い順)
            val reasons = es.flatMap { it.fails }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            if (reasons.isNotEmpty()) {
                append("\n■ 取れなかった理由(多い順)\n")
                reasons.take(8).forEach { (r, n) -> append("  ${n}回  $r\n") }
            }
            append("\n■ 新しい順(20 件)\n")
            es.takeLast(20).reversed().forEach { e ->
                append("  ${fmt.format(e.at)} ${e.kind} ${e.mode} → ${e.how}")
                if (e.how != READY) append(" ${sec(e.ms.toDouble())}")
                append("\n")
            }
        }
    }

    /** グラフの 1 本: 方法・取れた曲数・平均の時間(ミリ秒) */
    data class Bar(val how: String, val count: Int, val avgMs: Double)

    /** グラフに並べる方法(いつも同じ順) */
    val METHODS = listOf("直接", "NewPipe", "yt-dlp")

    /**
     * 最近 n 曲で、URL を取れた方法ごとの曲数(先読み・今流す曲の両方。用意済みは先読みの時に数えてあるので除く)。
     * 戻り値: 方法ごとの棒、取れなかった曲数、対象の曲数
     */
    fun songChart(n: Int = CHART_SONGS): Triple<List<Bar>, Int, Int> {
        val songs = entries().filter { it.kind == "曲" && it.mode != READY }.takeLast(n)
        val bars = METHODS.map { m ->
            val v = songs.filter { it.how == m }
            Bar(m, v.size, if (v.isEmpty()) 0.0 else v.map { it.ms.toDouble() }.average())
        }
        return Triple(bars, songs.count { it.how == FAILED }, songs.size)
    }

    /** 問題の報告に入れる短いまとめ(今流した曲のみ) */
    fun summary(): String {
        val now = entries().filter { it.kind == "曲" && (it.mode == NOW || it.mode == READY) }
        if (now.isEmpty()) return "(まだ記録がありません)"
        return "最近 ${now.size} 曲: " + now.groupBy { it.how }.entries.sortedByDescending { it.value.size }.joinToString("、") { (how, v) ->
            if (how == READY || how == FAILED) "$how ${v.size}" else "$how ${v.size}(平均 ${sec(v.map { it.ms.toDouble() }.average())})"
        }
    }
}
