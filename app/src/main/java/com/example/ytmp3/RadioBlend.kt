package com.example.ytmp3

/**
 * ラジオの候補を YouTube Music のラジオと YouTube のミックスで見比べて、信頼の低いものを弾く。
 *  - 高い: 両方に出てきた曲(同じ動画、または題名が重なる)
 *  - 中  : 片方だけだが、そのアーティストがもう片方にもいる
 *  - 低い: 片方だけで、アーティストも重ならない → 弾く
 * 残りが少なすぎる時は弾かずに、主にする一覧をそのまま使う(止まらないように)
 */
object RadioBlend {
    /** 弾いた後にこれだけ残らなければ、主の一覧をそのまま使う */
    const val MIN_KEEP = 8

    /** 題名をくらべる形に(かっこ書き・空白・記号を除く。「」の中は題名なので残す) */
    internal fun normTitle(s: String): String = s.lowercase()
        .replace(Regex("[(（\\[【].*?[)）\\]】]"), "")
        .replace(Regex("official|music ?video|lyric ?video|audio|mv|歌詞|公式"), "")
        .replace(Regex("[\\s\\p{Punct}「」『』・　〜～]"), "")

    /** アーティスト・チャンネル名をくらべる形に */
    internal fun normArtist(s: String): String = Radio.mainArtist(s).lowercase()
        .replace(Regex("\\s*-\\s*topic$"), "")
        .replace(Regex("official|channel|チャンネル|公式|オフィシャル|vevo"), "")
        .replace(Regex("[\\s\\p{Punct}・　]"), "")

    private class Side(list: List<Searcher.Result>) {
        val ids = list.map { it.id }.toSet()
        val titles = list.map { normTitle(it.title) }.filter { it.length >= 2 }
        val artists = list.map { normArtist(it.artist) }.filter { it.length >= 2 }.toSet()
        /** 同じ曲がある(同じ動画、または題名が片方に含まれる) */
        fun sameSong(r: Searcher.Result): Boolean {
            if (r.id in ids) return true
            val t = normTitle(r.title)
            if (t.length < 2) return false
            // 短い題名どうしの部分一致は偶然が多いので、3 文字以上の時だけ含まれるかを見る
            return titles.any { it == t || (minOf(it.length, t.length) >= 3 && (it.contains(t) || t.contains(it))) }
        }
        /** アーティストが重なる(名前が同じ、または題名にアーティスト名が入っている) */
        fun sameArtist(r: Searcher.Result): Boolean {
            val a = normArtist(r.artist)
            if (a.length >= 2 && a in artists) return true
            if (longEnough(a) && titles.any { it.contains(a) }) return true
            val t = normTitle(r.title)
            return artists.any { longEnough(it) && t.contains(it) }
        }
    }

    /** 題名の中から探してよい長さ(英字は 3 文字から、日本語などは 2 文字から。「優里」など) */
    private fun longEnough(a: String) = a.length >= 3 || (a.length == 2 && a.any { it.code > 0x7f })

    /**
     * primary: 主にする一覧(種が曲なら YouTube Music のラジオ、曲でない動画なら YouTube のミックス)
     * 先頭は種の曲(あれば)。弾いた結果が少なすぎれば primary をそのまま返す
     */
    fun blend(primary: List<Searcher.Result>, secondary: List<Searcher.Result>, seedId: String): List<Searcher.Result> {
        if (primary.isEmpty()) return secondary
        if (secondary.isEmpty()) return primary
        val p = Side(primary); val s = Side(secondary)
        val high = mutableListOf<Searcher.Result>()
        val midP = mutableListOf<Searcher.Result>()
        for (r in primary) {
            if (r.id == seedId) continue
            when {
                s.sameSong(r) -> high += r
                s.sameArtist(r) -> midP += r
            }
        }
        // もう片方にだけある曲: 同じ曲は重なるので足さない。アーティストが重なるものだけ
        val midS = secondary.filter { it.id != seedId && !p.sameSong(it) && p.sameArtist(it) }
        val mid = mutableListOf<Searcher.Result>()
        for (i in 0 until maxOf(midP.size, midS.size)) { midP.getOrNull(i)?.let { mid += it }; midS.getOrNull(i)?.let { mid += it } }
        val kept = (high + mid).distinctBy { it.id }
        if (kept.size < MIN_KEEP) return primary
        val seed = (primary + secondary).firstOrNull { it.id == seedId }
        return listOfNotNull(seed) + kept
    }
}
