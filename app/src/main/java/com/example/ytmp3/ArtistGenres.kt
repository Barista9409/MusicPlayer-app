package com.example.ytmp3

import android.content.Context
import org.json.JSONObject

/**
 * アーティストのジャンル(あなたのミックスを「ジャンルで分ける」時に使う)。
 *  1. 無料の音楽データベース MusicBrainz のタグから(送るのはアーティスト名だけ。結果は 30 日覚えておく)
 *  2. 分からない時は、名前・曲名の文字から(ハングル → K-POP、かな → J-POP、英語だけ → 洋楽ポップ)
 * MusicBrainz の決まりで、問い合わせは 1 秒に 1 件まで(裏で少しずつ調べる)
 */
object ArtistGenres {
    private lateinit var app: Context
    private var file: java.io.File? = null
    /** アーティスト(小文字) → (ジャンル, 調べた時刻)。ジャンルが空 = MusicBrainz に載っていなかった */
    private val cache = HashMap<String, Pair<String, Long>>()
    /** 調べ終わった・調べている数(設定・ホームの表示用) */
    val progress = kotlinx.coroutines.flow.MutableStateFlow(0 to 0)
    /** 調べた結果が増えた(ホームの描き直し用) */
    val changed = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private const val KEEP_MS = 30L * 86_400_000L
    @Volatile private var running = false

    fun init(c: Context) {
        app = c.applicationContext
        file = java.io.File(app.filesDir, "artist_genres.json")
        runCatching {
            val o = JSONObject(file!!.readText())
            synchronized(cache) { o.keys().forEach { k -> o.optJSONArray(k)?.let { a -> cache[k] = a.optString(0) to a.optLong(1) } } }
        }
    }

    private fun save() {
        val o = JSONObject()
        synchronized(cache) { cache.forEach { (k, v) -> o.put(k, org.json.JSONArray().put(v.first).put(v.second)) } }
        runCatching { file?.writeText(o.toString()) }
    }

    /** ジャンル(通信しない。調べてあればその結果、無ければ文字から) */
    fun of(artist: String, titles: List<String> = emptyList()): String {
        val key = Radio.mainArtist(artist).lowercase().trim()
        synchronized(cache) { cache[key]?.first?.takeIf { it.isNotEmpty() }?.let { return it } }
        return guess(artist, titles)
    }

    /** 調べてあるジャンル(無ければ null。ミックスの似た曲をジャンルで絞る時に使う) */
    fun known(artist: String): String? {
        val key = Radio.mainArtist(artist).lowercase().trim()
        return synchronized(cache) { cache[key]?.first?.takeIf { it.isNotEmpty() } }
    }

    /**
     * ジャンルのミックスに入れてよいか。調べてあればそのジャンルと同じ時だけ。
     * 調べていない人は、文字ではっきり分かる時(K-POP と J-POP の取り違え)だけ外す
     */
    fun fits(artist: String, title: String, genre: String): Boolean {
        known(artist)?.let { return it == genre }
        if (genre != "K-POP" && genre != "J-POP") return true
        val g = guess(artist, listOf(title))
        return !(g == "K-POP" || g == "J-POP") || g == genre
    }

    /** 名前・曲名の文字から(調べられない時の代わり) */
    internal fun guess(artist: String, titles: List<String>): String {
        val text = artist + " " + titles.joinToString(" ")
        val hangul = text.count { it in '가'..'힣' || it in 'ᄀ'..'ᇿ' || it in '㄰'..'㆏' }
        val kana = text.count { it in '぀'..'ヿ' }
        val kanji = text.count { it in '一'..'鿿' }
        return when {
            hangul > 0 && hangul >= kana -> "K-POP"
            kana > 0 || kanji > 0 -> "J-POP"
            else -> "洋楽ポップ"
        }
    }

    /**
     * MusicBrainz のタグからジャンルを決める。数の多いタグから順に、当てはまるものを使う。
     * 当てはまるタグが無ければ null(呼ぶ側で文字から決める)
     */
    internal fun fromTags(tags: List<Pair<String, Int>>, artist: String, titles: List<String>): String? {
        for ((name, _) in tags.sortedByDescending { it.second }) {
            val t = name.lowercase()
            val g = when {
                "vocaloid" in t -> "ボカロ"
                "k-pop" in t || "kpop" in t || "korean" in t -> "K-POP"
                "anime" in t || "game" in t || "soundtrack" in t || "seiyuu" in t -> "アニメ・ゲーム"
                "lo-fi" in t || "lofi" in t || "bgm" in t || "ambient" in t || "new age" in t || "healing" in t || "meditation" in t || "chillhop" in t -> "ローファイ・BGM"
                "jazz" in t || "bossa" in t -> "ジャズ"
                "classical" in t || "orchestra" in t || "baroque" in t -> "クラシック"
                "hip hop" in t || "hip-hop" in t || "rap" == t || "trap" in t || "r&b" in t || "rnb" in t || "soul" in t -> "ヒップホップ・R&B"
                "edm" in t || "electronic" in t || "house" in t || "techno" in t || "dance" in t || "trance" in t || "dubstep" in t -> "ダンス・EDM"
                "rock" in t || "metal" in t || "punk" in t || "emo" == t -> "ロック"
                "j-pop" in t || "jpop" in t || "japanese" in t || "city pop" in t || "idol" in t -> "J-POP"
                "pop" in t -> if (guess(artist, titles) == "洋楽ポップ") "洋楽ポップ" else guess(artist, titles)
                else -> null
            }
            if (g != null) return g
        }
        return null
    }

    /**
     * よく聴くアーティストのうち、まだ調べていない(または 30 日たった)人を、裏で 1 秒に 1 人ずつ調べる。
     * 通信を使えない時はしない
     */
    fun lookup(artists: List<String>) {
        if (!::app.isInitialized || running || !Net.streamAllowed()) return
        val now = System.currentTimeMillis()
        val todo = artists.map { Radio.mainArtist(it) }.distinctBy { it.lowercase() }.filter { a ->
            val k = a.lowercase().trim()
            k.isNotEmpty() && synchronized(cache) { cache[k]?.let { now - it.second > KEEP_MS } ?: true }
        }.take(40)
        if (todo.isEmpty()) return
        running = true
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                progress.value = 0 to todo.size
                todo.forEachIndexed { i, a ->
                    val tags = runCatching { fetchTags(a) }.getOrNull()
                    if (tags != null) {
                        val titles = Repository.tracks.value.filter { Radio.mainArtist(it.artist).equals(a, true) }.take(5).map { it.title }
                        val g = fromTags(tags, a, titles).orEmpty()
                        synchronized(cache) { cache[a.lowercase().trim()] = g to System.currentTimeMillis() }
                    }
                    progress.value = (i + 1) to todo.size
                    if ((i + 1) % 5 == 0) { save(); changed.value = System.currentTimeMillis() }
                    Thread.sleep(1100) // MusicBrainz の決まり(1 秒に 1 件まで)
                }
                save(); changed.value = System.currentTimeMillis()
            } finally {
                progress.value = 0 to 0
                running = false
            }
        }, "genres").start()
    }

    /** MusicBrainz でアーティストを探し、いちばん近い人のタグ(名前, 数)。見つからなければ空 */
    private fun fetchTags(artist: String): List<Pair<String, Int>> {
        val q = java.net.URLEncoder.encode("artist:\"${artist.replace("\"", "")}\"", "UTF-8")
        val c = java.net.URL("https://musicbrainz.org/ws/2/artist/?query=$q&fmt=json&limit=3").openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            // MusicBrainz は名乗りを求める(アプリの名前だけ。個人の情報は送らない)
            c.setRequestProperty("User-Agent", "MusicPlayer-Android/1.0")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val list = o.optJSONArray("artists") ?: return emptyList()
            for (i in 0 until list.length()) {
                val a = list.optJSONObject(i) ?: continue
                if (a.optInt("score") < 90) continue
                val tags = a.optJSONArray("tags") ?: continue
                return (0 until tags.length()).mapNotNull { j -> tags.optJSONObject(j)?.let { it.optString("name") to it.optInt("count") } }
            }
            return emptyList()
        } finally { c.disconnect() }
    }
}
