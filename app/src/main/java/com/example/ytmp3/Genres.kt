package com.example.ytmp3

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「さがす」の見つけるに並べるジャンル(編集で追加・削除・並び替え)。
 * YouTube Music に同じジャンルがあればそれを開き、無ければその言葉でプレイリストを探す。
 */
object Genres {
    data class Genre(val title: String, val query: String, val match: List<String> = emptyList())

    /** 最初の並び(Spotify の「ジャンルとムード」を参考に、日本でよく聴かれる順) */
    val defaults = listOf(
        Genre("J-Pop", "J-Pop", listOf("J-Pop", "J-POP")),
        Genre("アニメ", "アニソン", listOf("アニメ")),
        Genre("ボカロ", "ボカロ", listOf("ボーカロイド", "ボカロ", "VOCALOID")),
        Genre("K-Pop", "K-Pop", listOf("K-Pop", "K-POP")),
        Genre("洋楽ポップ", "洋楽 ポップ", listOf("ポップ", "Pop")),
        Genre("ヒップホップ", "ヒップホップ", listOf("ヒップホップ", "Hip")),
        Genre("ロック", "ロック", listOf("ロック", "Rock")),
        Genre("ダンス/エレクトロ", "EDM", listOf("ダンス", "エレクトロ")),
        Genre("R&B", "R&B", listOf("R&B")),
        Genre("インディー", "インディー", listOf("インディー", "Indie")),
        Genre("シティポップ", "シティポップ"),
        Genre("ジャズ", "ジャズ", listOf("ジャズ", "Jazz")),
        Genre("クラシック", "クラシック", listOf("クラシック", "Classical")),
        Genre("チル", "チル", listOf("リラックス", "チル", "Chill")),
        Genre("集中", "作業用 集中", listOf("集中", "フォーカス", "Focus")),
        Genre("ワークアウト", "ワークアウト", listOf("ワークアウト", "Workout")),
        Genre("元気が出る", "元気が出る", listOf("エナジー", "気分爽快", "元気")),
        Genre("ドライブ", "ドライブ", listOf("ドライブ", "通勤")),
        Genre("パーティー", "パーティー", listOf("パーティー", "Party")),
        Genre("恋愛", "ラブソング", listOf("ロマンス", "恋")),
        Genre("睡眠", "睡眠 ヒーリング", listOf("睡眠", "スリープ", "Sleep")),
        Genre("懐かしの 90年代", "90年代 J-POP"),
        Genre("演歌・歌謡曲", "演歌 歌謡曲", listOf("演歌", "歌謡")),
        Genre("ゲーム", "ゲーム BGM", listOf("ゲーム", "Gaming")),
    )

    private const val KEY = "genres_v2"

    fun load(p: SharedPreferences): List<Genre> {
        val j = p.getString(KEY, null) ?: return defaults
        return runCatching {
            val a = JSONArray(j)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                val m = o.optJSONArray("m")
                Genre(o.getString("t"), o.optString("q").ifBlank { o.getString("t") },
                    if (m == null) emptyList() else List(m.length()) { k -> m.getString(k) })
            }
        }.getOrDefault(defaults)
    }

    fun save(p: SharedPreferences, list: List<Genre>) {
        val a = JSONArray()
        list.forEach { g -> a.put(JSONObject().put("t", g.title).put("q", g.query).put("m", JSONArray(g.match))) }
        p.edit().putString(KEY, a.toString()).apply()
    }

    fun reset(p: SharedPreferences) = p.edit().remove(KEY).apply()

    /** YouTube Music の同じジャンル(あれば) */
    fun moodFor(g: Genre, moods: List<InnerTube.Mood>): InnerTube.Mood? =
        moods.firstOrNull { it.title == g.title }
            ?: moods.firstOrNull { m -> g.match.any { k -> m.title.contains(k, ignoreCase = true) } }

    /** ジャンルのプレイリスト(YouTube Music のジャンル → 無ければ言葉で検索) */
    fun playlists(g: Genre, moods: List<InnerTube.Mood>): List<Searcher.Result> {
        moodFor(g, moods)?.let { m -> runCatching { InnerTube.moodPlaylists(m) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it } }
        return InnerTube.search(g.query, Searcher.Source.MUSIC, Searcher.Section.PLAYLISTS, Searcher.Sort.RELEVANCE).results
    }
}
