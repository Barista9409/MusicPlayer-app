package com.example.ytmp3

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spotify のリンクから曲名・アーティストを読み取る(再生はしない)。
 * ログインや API キーは使わず、ブログなどに貼るための「埋め込み用ページ」の情報を読む
 */
object Spotify {
    enum class Type { TRACK, ALBUM, PLAYLIST, ARTIST }

    data class Link(val type: Type, val id: String)
    data class Song(val title: String, val artist: String, val durationMs: Long)
    data class Collection(val type: Type, val name: String, val owner: String, val songs: List<Song>)

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val LINK = Regex("open\\.spotify\\.com/(?:intl-[A-Za-z-]+/)?(?:embed/)?(track|album|playlist|artist)/([A-Za-z0-9]+)")
    private val URI = Regex("spotify:(track|album|playlist|artist):([A-Za-z0-9]+)")
    private val SHORT = Regex("https?://(?:spotify\\.link|spotify\\.app\\.link)/\\S+")

    /** 文字の中に Spotify のリンクがあるか(短縮リンクも) */
    fun isSpotify(text: String) = LINK.containsMatchIn(text) || URI.containsMatchIn(text) || SHORT.containsMatchIn(text)

    /** 文字の中から Spotify のリンクを取り出す */
    fun find(text: String): String? =
        Regex("https?://\\S*spotify\\S*").find(text)?.value ?: URI.find(text)?.value

    /** リンクの種類と ID(短縮リンクは開いて本当の行き先を見る。通信するので IO で呼ぶ) */
    fun parse(text: String): Link? {
        fun direct(s: String): Link? =
            (LINK.find(s) ?: URI.find(s))?.let { m -> Link(Type.valueOf(m.groupValues[1].uppercase()), m.groupValues[2]) }
        direct(text)?.let { return it }
        val short = SHORT.find(text)?.value ?: return null
        return runCatching {
            val c = URL(short).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            try {
                direct(c.url.toString()) ?: direct(c.getHeaderField("Location").orEmpty())
                    ?: direct(c.inputStream.bufferedReader().use { it.readText() })
            } finally { c.disconnect() }
        }.getOrNull()
    }

    /** 埋め込み用ページから中身を読む(通信するので IO で呼ぶ) */
    fun load(link: Link): Collection {
        val kind = link.type.name.lowercase()
        val html = get("https://open.spotify.com/embed/$kind/${link.id}")
        val data = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.groupValues?.get(1)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val entity = data?.let { findEntity(it) }
        if (entity != null) {
            val name = entity.optString("name").ifBlank { entity.optString("title") }
            val owner = entity.optString("subtitle").ifBlank { artistsOf(entity) }
            val list = entity.optJSONArray("trackList")
            val songs = if (list != null && list.length() > 0) {
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { t ->
                    val title = t.optString("title").ifBlank { t.optString("name") }
                    if (title.isBlank()) null
                    else Song(title, t.optString("subtitle").ifBlank { artistsOf(t) }.cleanArtists(), t.optLong("duration"))
                }
            } else if (link.type == Type.TRACK && name.isNotBlank()) {
                listOf(Song(name, owner.cleanArtists(), entity.optLong("duration")))
            } else emptyList()
            if (songs.isNotEmpty()) return Collection(link.type, name, owner, songs)
        }
        // 予備: 普通のページの見出し(曲 1 つだけ。「曲名」と「アーティスト · アルバム · 曲 · 年」)
        if (link.type == Type.TRACK) {
            val page = get("https://open.spotify.com/track/${link.id}")
            val title = meta(page, "og:title")
            val desc = meta(page, "og:description")
            if (!title.isNullOrBlank()) {
                val artist = desc?.split(" · ")?.firstOrNull().orEmpty()
                return Collection(Type.TRACK, title, artist, listOf(Song(title, artist, 0)))
            }
        }
        error("Spotify のページから曲を読み取れませんでした")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "ja,en;q=0.8")
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        try {
            if (c.responseCode !in 200..299) error("Spotify に接続できませんでした(${c.responseCode})")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun meta(html: String, prop: String): String? =
        Regex("<meta[^>]+property=\"$prop\"[^>]+content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)?.let { unescape(it) }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /** 曲の一覧(trackList)か、曲そのもの(artists)を持つ所を探す */
    private fun findEntity(o: Any?): JSONObject? {
        when (o) {
            is JSONObject -> {
                o.optJSONObject("entity")?.let { return it }
                if (o.has("trackList")) return o
                val it = o.keys()
                while (it.hasNext()) findEntity(o.opt(it.next()))?.let { e -> return e }
            }
            is JSONArray -> for (i in 0 until o.length()) findEntity(o.opt(i))?.let { return it }
        }
        return null
    }

    private fun artistsOf(o: JSONObject): String {
        val a = o.optJSONArray("artists") ?: return ""
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }.joinToString(", ")
    }

    /** 「A, B」などの区切りを「A, B」にそろえる */
    private fun String.cleanArtists() = replace(' ', ' ').split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    // ───────── YouTube Music / YouTube で同じ曲を探す ─────────

    enum class Match { OK, CHECK, NONE }
    data class Found(val best: Searcher.Result?, val candidates: List<Searcher.Result>, val match: Match)

    /** 1 曲ぶん: まず YouTube Music の「曲」、合わなければ YouTube の動画からも探す(通信するので IO で呼ぶ) */
    fun findOnYouTube(s: Song): Found {
        val q = listOf(s.title, s.artist.split(", ").firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
        val music = runCatching { InnerTube.search(q, Searcher.Source.MUSIC, Searcher.Section.SONGS).results }.getOrDefault(emptyList())
            .filter { it.videoId != null }.take(5)
        var scored = music.map { it to score(s, it) + 5 }
        if ((scored.maxOfOrNull { it.second } ?: 0) < 80) {
            val yt = runCatching { InnerTube.search(q, Searcher.Source.YOUTUBE, Searcher.Section.VIDEOS).results }.getOrDefault(emptyList())
                .filter { it.videoId != null }.take(5)
            scored = scored + yt.map { it to score(s, it) }
        }
        val sorted = scored.sortedByDescending { it.second }.distinctBy { it.first.videoId }
        val top = sorted.firstOrNull()
        val m = when {
            top == null -> Match.NONE
            top.second >= 80 -> Match.OK
            top.second >= 40 -> Match.CHECK
            else -> Match.NONE
        }
        return Found(top?.first?.takeIf { m != Match.NONE }, sorted.map { it.first }, m)
    }

    /** 似ている度合い: 曲名(最大 50)+ アーティスト(最大 30)+ 長さ(-30〜20) */
    fun score(s: Song, r: Searcher.Result): Int {
        var p = 0
        val want = LibraryMatch.titleKey(s.title)
        val got = LibraryMatch.titleKey(r.title)
        p += when {
            want.isEmpty() || got.isEmpty() -> 0
            want == got -> 50
            got.contains(want) || want.contains(got) -> 30
            else -> 0
        }
        val wantArtists = LibraryMatch.parts(s.artist).toSet()
        val gotArtists = LibraryMatch.parts(r.artist).toSet()
        p += when {
            wantArtists.isEmpty() -> 0
            wantArtists.any { it in gotArtists } -> 30
            // YouTube の動画は「アーティスト - 曲名」の形でチャンネル名が違うことがある
            wantArtists.any { a -> LibraryMatch.artistKey(r.title).contains(a) } -> 20
            else -> 0
        }
        if (s.durationMs > 0 && r.durationSec > 0) {
            val d = kotlin.math.abs(s.durationMs / 1000 - r.durationSec)
            p += when {
                d <= 3 -> 20
                d <= 8 -> 10
                d <= 30 -> 0
                else -> -30
            }
        }
        return p
    }
}
