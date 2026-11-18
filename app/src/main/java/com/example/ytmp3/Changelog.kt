package com.example.ytmp3

import android.content.Context

/** 更新内容(assets/changelog.txt の「## 版」ごとの箇条書き) */
object Changelog {
    /** 版 → 変更点(新しい順) */
    fun all(c: Context): List<Pair<String, List<String>>> = runCatching {
        val out = mutableListOf<Pair<String, MutableList<String>>>()
        c.assets.open("changelog.txt").bufferedReader().useLines { lines ->
            lines.forEach { l ->
                when {
                    l.startsWith("## ") -> out += l.removePrefix("## ").trim() to mutableListOf()
                    l.startsWith("- ") && out.isNotEmpty() -> out.last().second += l.removePrefix("- ").trim()
                }
            }
        }
        out.map { it.first to it.second.toList() }
    }.getOrDefault(emptyList())

    /** 前回見た版より新しい分の変更点(初回インストールでは出さない) */
    fun unseen(c: Context): List<Pair<String, List<String>>> {
        val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val now = AppUpdater.currentName(c)
        val last = p.getString("changelog_seen", null)
        if (last != null && last != now) p.edit().putString("changelog_from", last).apply()
        p.edit().putString("changelog_seen", now).apply()
        if (last == null || last == now) return emptyList()
        val list = all(c)
        val idx = list.indexOfFirst { it.first == last }
        return if (idx < 0) list.take(1) else list.take(idx)
    }

    /**
     * ある版の変更点(お知らせ・通知から開いた時)。今の版なら、更新前の版から後の分をまとめて
     */
    fun forVersion(c: Context, version: String): List<Pair<String, List<String>>> {
        val list = all(c)
        val idx = list.indexOfFirst { it.first == version }
        if (idx < 0) return list.take(1)
        val from = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("changelog_from", null)
        val end = if (version == AppUpdater.currentName(c)) list.indexOfFirst { it.first == from }.takeIf { it > idx } else null
        return list.subList(idx, end ?: idx + 1)
    }

    fun format(items: List<Pair<String, List<String>>>): String =
        items.joinToString("\n\n") { (v, lines) -> "v$v\n" + lines.joinToString("\n") { "・$it" } }
}
