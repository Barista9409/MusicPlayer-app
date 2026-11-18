package com.example.ytmp3

/**
 * おすすめ・ラジオの決まりごと(画面や通信に関係しない部分)。
 * 自動テストで確かめられるように、ここにまとめておく
 */
object RecoRules {
    /** ラジオで似た曲を足してよいか(作り直しの時はいつでも足す) */
    fun canAppend(fromCurrent: Boolean, radioOn: Boolean, repeatOff: Boolean): Boolean =
        fromCurrent || (radioOn && repeatOff)

    /** 足すタイミング: 今の曲の後ろが 1 曲以下になったら */
    fun shouldAppendNow(remaining: Int): Boolean = remaining <= 1

    /** 並び順(シャッフルなし)で、今の曲の後ろに残っている曲数 */
    fun remainingInOrder(count: Int, current: Int): Int =
        if (count <= 0 || current < 0) 0 else maxOf(0, count - 1 - current)

    /** 同じアーティストは max 曲まで(並び順はそのまま) */
    fun <T> capPerArtist(list: List<T>, max: Int, artistOf: (T) -> String): List<T> {
        val n = HashMap<String, Int>()
        return list.filter { x ->
            val a = Radio.mainArtist(artistOf(x)).lowercase()
            val c = n[a] ?: 0
            if (c >= max) false else { n[a] = c + 1; true }
        }
    }
}
