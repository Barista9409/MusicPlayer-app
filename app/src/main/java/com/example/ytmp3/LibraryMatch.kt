package com.example.ytmp3

/**
 * 「もうライブラリにあるか」を名前で見る(おすすめから省くため)。
 * 動画 ID が違っても(曲版と MV など)、曲名とアーティスト名が同じなら同じ曲として扱う
 */
object LibraryMatch {
    private var forTracks: List<Track>? = null
    private var index = Index(emptyList())

    /** 比べやすい形に: 小文字・記号や空白なし・「 - Topic」なし */
    fun artistKey(s: String) = s.lowercase().trim().removeSuffix(" - topic").filter { it.isLetterOrDigit() }

    /** 曲名: (Official Video) などのかっこ書きや「 - 〇〇」を外す */
    fun titleKey(s: String) = s.lowercase()
        .replace(Regex("[(（\\[【].*?[)）\\]】]"), "")
        .replace(Regex("\\s+[-–—]\\s+.*$"), "")
        .filter { it.isLetterOrDigit() }

    /** 共演者も含めて名前を分ける(「A, B & C」「A feat. B」) */
    fun parts(artist: String) = artist.split(", ", "、", " & ", " ＆ ", " x ", " × ", " feat.", " feat ", " ft.", " with ", "/")
        .map { artistKey(it) }.filter { it.isNotEmpty() }

    /** 曲の一覧から作る索引(ライブラリと関係なくテストできる) */
    class Index(ts: List<Track>) {
        private val artists: Set<String> = ts.flatMap { parts(it.artist) }.toSet()
        private val songs: Set<String> = ts.flatMap { t -> parts(t.artist).map { a -> titleKey(t.title) + "|" + a } }.toSet()

        fun hasArtist(name: String): Boolean {
            val k = artistKey(name)
            return k.isNotEmpty() && k in artists
        }

        fun hasSong(title: String, artist: String): Boolean {
            val t = titleKey(title)
            if (t.isEmpty()) return false
            return parts(artist).any { a -> "$t|$a" in songs }
        }
    }

    @Synchronized
    private fun current(): Index {
        val ts = Repository.tracks.value
        if (ts !== forTracks) { forTracks = ts; index = Index(ts) }
        return index
    }

    /** このアーティストの曲がライブラリにあるか */
    fun hasArtist(name: String): Boolean = current().hasArtist(name)

    /** 同じ曲(曲名とアーティストが同じ)のライブラリの曲(なければ null) */
    fun findSong(title: String, artist: String): Track? {
        val t = titleKey(title)
        if (t.isEmpty()) return null
        val want = parts(artist).toSet()
        return Repository.tracks.value.firstOrNull { tr ->
            titleKey(tr.title) == t && parts(tr.artist).any { it in want } && !Repository.cantPlay(tr.id)
        }
    }

    /** 同じ曲(曲名とアーティストが同じ)がライブラリにあるか */
    fun hasSong(title: String, artist: String): Boolean = current().hasSong(title, artist)
}
