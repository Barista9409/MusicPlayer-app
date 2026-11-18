package com.example.ytmp3

/**
 * あなたのミックスの組み分け: 聴き方の傾向でアーティストを 3 つのまとまりに分ける。
 *
 * 手がかり(ジャンルの情報は無いので、聴いた記録から読み取る)
 *  - 一緒に聴かれる: 30 分以内に続けて聴いたアーティストどうしは、同じ「気分」でまとまりやすい
 *  - 同じプレイリストに入っている
 *  - 聴く時間帯(朝・昼・夕方・夜)が似ている
 * つながりの強い人どうしを順にまとめていき(平均でまとめる階層クラスタリング)、3 つにする。
 * 記録が少ない時は null(呼ぶ側で今までの分け方にする)
 */
object MixClusters {
    data class Cluster(val artists: List<String>, val title: String)

    private val bins = listOf("朝", "昼", "夕方", "夜")
    private fun binOf(time: Long): Int {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = time }
        return when (c.get(java.util.Calendar.HOUR_OF_DAY)) { in 5..10 -> 0; in 11..16 -> 1; in 17..21 -> 2; else -> 3 }
    }

    /** 同じ記録なら作り直さない(ホームを開くたびに計算しないように) */
    private var cacheKey = ""
    private var cache: List<Cluster>? = null

    fun build(ranked: List<String>, tracks: List<Track>): List<Cluster>? {
        val now = System.currentTimeMillis()
        val events = PlayLog.playsSince(now - 60L * 86_400_000L).sortedBy { it.second }
        val key = "${events.size}:${events.lastOrNull()?.second}:${ranked.take(30).hashCode()}:${Repository.playlists.value.hashCode()}"
        synchronized(this) { if (key == cacheKey) return cache }
        val result = compute(ranked, tracks, events)
        synchronized(this) { cacheKey = key; cache = result }
        return result
    }

    private fun compute(ranked: List<String>, tracks: List<Track>, events: List<Pair<Long, Long>>): List<Cluster>? {
        val byId = tracks.associateBy { it.id }
        val artistOf = { id: Long -> byId[id]?.artist?.takeIf { it.isNotBlank() }?.let { Radio.mainArtist(it) } }
        // 候補: よく聴く 40 人(このあと、直近 60 日に 2 回以上聴いた人だけを 24 人まで分ける)
        val people = ranked.take(40)
        if (events.size < 30 || people.size < 6) return null
        val idx = people.withIndex().associate { it.value to it.index }
        val n = people.size
        val w = Array(n) { DoubleArray(n) }
        val plays = IntArray(n)
        val hours = Array(n) { DoubleArray(4) }
        // 一緒に聴かれる(30 分以内・その後 8 曲まで)
        val ev = events.mapNotNull { (id, t) -> artistOf(id)?.let { a -> idx[a]?.let { it to t } } }
        ev.forEachIndexed { i, (a, t) ->
            plays[a]++; hours[a][binOf(t)] += 1.0
            for (j in i + 1 until minOf(ev.size, i + 9)) {
                val (b, tb) = ev[j]
                if (tb - t > 30 * 60_000L) break
                if (a != b) { w[a][b] += 1.0; w[b][a] += 1.0 }
            }
        }
        if (ev.size < 30) return null
        // 同じプレイリストに入っている
        Repository.playlists.value.forEach { pl ->
            val inPl = pl.trackIds.mapNotNull { artistOf(it)?.let { a -> idx[a] } }.distinct()
            for (x in inPl) for (y in inPl) if (x != y) w[x][y] += 0.5
        }
        // つながりの強さ(聴いた回数の多い人ほど数が増えるので、回数で割ってならす)＋時間帯の似かた
        fun cosine(x: DoubleArray, y: DoubleArray): Double {
            val d = x.indices.sumOf { x[it] * y[it] }
            val nx = kotlin.math.sqrt(x.sumOf { it * it }); val ny = kotlin.math.sqrt(y.sumOf { it * it })
            return if (nx == 0.0 || ny == 0.0) 0.0 else d / (nx * ny)
        }
        val sim = Array(n) { a -> DoubleArray(n) { b ->
            if (a == b) 0.0 else w[a][b] / kotlin.math.sqrt((plays[a] + 1.0) * (plays[b] + 1.0)) + 0.3 * cosine(hours[a], hours[b])
        } }
        // 階層クラスタリング(平均): 一番似ているまとまりどうしを、3 つになるまでくっつける
        // ほとんど聴いていない人は、どことも結び付かず 1 人だけのまとまりになるので外す
        val groups = (0 until n).filter { plays[it] >= 2 }.sortedByDescending { plays[it] }.take(24)
            .map { mutableListOf(it) }.toMutableList()
        if (groups.size < 6) return null
        while (groups.size > 3) {
            var best = -1.0; var bi = 0; var bj = 1
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val s = groups[i].sumOf { a -> groups[j].sumOf { b -> sim[a][b] } } / (groups[i].size * groups[j].size)
                // 1 つのまとまりが大きくなりすぎないよう、大きいまとまりどうしは少しくっつきにくく
                val adj = s / kotlin.math.sqrt((groups[i].size * groups[j].size).toDouble())
                if (adj > best) { best = adj; bi = i; bj = j }
            }
            groups[bi].addAll(groups[bj]); groups.removeAt(bj)
        }
        // 聴いた回数の多いまとまりから順に。中はよく聴く順
        val sorted = groups.map { g -> g.sortedByDescending { plays[it] } }.sortedByDescending { g -> g.sumOf { plays[it] } }
        if (sorted.any { it.isEmpty() }) return null
        // 名前: 中心のアーティスト(時間帯では分けない。曲調で分けられるまでのつなぎ)
        return sorted.map { g -> Cluster(g.map { people[it] }, people[g.first()]) }
    }
}
