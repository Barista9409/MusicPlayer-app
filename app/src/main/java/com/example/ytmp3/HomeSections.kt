package com.example.ytmp3

import android.content.SharedPreferences
import org.json.JSONArray

/** ホームの欄の並びと表示(「ホームを編集」で変えられる) */
object HomeSections {
    /** key → 名前 */
    val labels = linkedMapOf(
        "quick" to "クイックアクセス",
        "recent" to "最近再生したもの",
        "top" to "よく聴く曲",
        "added" to "最近追加した曲",
        "liked" to "お気に入り",
        "mix" to "あなたのミックス",
        "stats" to "最近の記録",
        "discover" to "おすすめの曲",
        "chart" to "いま人気の曲",
        "new" to "ニューリリース",
        "related" to "おすすめのアーティスト",
        "buttons" to "すぐ再生(全曲シャッフルなど)",
        "library" to "ライブラリ",
    )

    /** 最初の並び(「元に戻す」もこれ) */
    val defaultOrder = listOf(
        "buttons", "quick", "related", "discover", "chart", "new", "recent",
        "top", "added", "liked", "mix", "stats", "library",
    ).filter { it in labels } + labels.keys.filter { it !in setOf(
        "buttons", "quick", "related", "discover", "chart", "new", "recent", "top", "added", "liked", "mix", "stats", "library") }

    /** 最初に隠しておく欄 */
    val defaultHidden = setOf("buttons", "top", "added", "mix", "library")

    fun order(p: SharedPreferences): List<String> {
        val saved = p.getString("home_order", null)?.let { j ->
            runCatching { JSONArray(j).let { a -> List(a.length()) { a.getString(it) } } }.getOrNull()
        } ?: return defaultOrder
        // 新しく増えた欄は、最初の並びでのすぐ前の欄の後ろに入れる(無ければ後ろに)
        val out = saved.filter { it in labels }.toMutableList()
        defaultOrder.forEachIndexed { i, key ->
            if (key in out) return@forEachIndexed
            val before = defaultOrder.take(i).lastOrNull { it in out }
            if (before == null) out.add(0, key) else out.add(out.indexOf(before) + 1, key)
        }
        return out
    }

    fun hidden(p: SharedPreferences): Set<String> =
        p.getString("home_hidden", null)?.let { j ->
            runCatching { JSONArray(j).let { a -> List(a.length()) { a.getString(it) }.toSet() } }.getOrNull()
        } ?: defaultHidden

    fun save(p: SharedPreferences, order: List<String>, hidden: Set<String>) {
        p.edit().putString("home_order", JSONArray(order).toString())
            .putString("home_hidden", JSONArray(hidden.toList()).toString()).apply()
    }

    fun reset(p: SharedPreferences) = p.edit().remove("home_order").remove("home_hidden").apply()
}
