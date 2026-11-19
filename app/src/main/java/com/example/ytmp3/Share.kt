package com.example.ytmp3

/**
 * アプリを使う人どうしで、曲・アルバム・プレイリストを共有するリンク。
 *
 * 中身(曲の ID とプレイリスト名)はリンクの ?p= に入れる(以前は # の後ろだったが、LINE などで # の後ろが消えて開けないことがあった)。
 * 形: https://barista9409.github.io/MusicPlayer-releases/s/?openExternalBrowser=1&p=1p.<名前(base64url)>.<動画 ID 11 文字 × 曲数>
 *   1 = 形式の版 / p = プレイリスト, a = アルバム, s = 曲
 * 共有用ページの「アプリで開く」からは musicplayer://share/<同じ中身> で届く
 */
object Share {
    const val PAGE = "https://barista9409.github.io/MusicPlayer-releases/s/"
    /** これより多い曲はリンクが長くなるので、ファイルで送るかを選ぶ */
    const val LINK_MAX = 30

    enum class Kind(val code: Char, val label: String) {
        PLAYLIST('p', "プレイリスト"), ALBUM('a', "アルバム"), SONG('s', "曲");
        companion object { fun of(c: Char) = entries.firstOrNull { it.code == c } }
    }

    data class Payload(val kind: Kind, val name: String, val ids: List<String>)

    private val ID = Regex("[A-Za-z0-9_-]{11}")
    // 前の形(# の後ろ)・今の形(?p=)・アプリで開く(musicplayer://share/)のどれでも読む
    private val FIND = Regex("(?:MusicPlayer-releases/s/?(?:\\?[^#\\s]*)?#|MusicPlayer-releases/s/?\\?(?:[^#\\s]*&)?p=|musicplayer://share/)(1[pas]\\.[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]*)")

    fun encode(p: Payload): String {
        val name = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(p.name.toByteArray(Charsets.UTF_8))
        return "1${p.kind.code}.$name.${p.ids.filter { ID.matches(it) }.joinToString("")}"
    }

    /**
     * 共有リンク。?openExternalBrowser=1 は LINE の決まりで、LINE の中ではなくふつうのブラウザで開かせる
     * (LINE の中のブラウザからはアプリを開けないため)。ほかのアプリでは何もしない
     */
    fun link(p: Payload) = PAGE + "?openExternalBrowser=1&p=" + encode(p)

    /** 文字の中に共有リンクがあるか */
    fun isShare(text: String) = FIND.containsMatchIn(text)

    /** 文字の中の共有リンクを読む(壊れていれば null) */
    fun decode(text: String): Payload? {
        val body = FIND.find(text)?.groupValues?.get(1) ?: return null
        val parts = body.split(".")
        if (parts.size != 3) return null
        val kind = Kind.of(parts[0][1]) ?: return null
        val name = runCatching { String(java.util.Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8) }.getOrNull() ?: return null
        val raw = parts[2]
        if (raw.length % 11 != 0) return null
        val ids = raw.chunked(11).filter { ID.matches(it) }.distinct()
        if (ids.isEmpty()) return null
        return Payload(kind, name, ids)
    }

    /** LINE などに送る文 */
    fun message(p: Payload): String {
        val what = if (p.kind == Kind.SONG && p.ids.size == 1) "曲「${p.name}」"
            else "${p.kind.label}「${p.name}」(${p.ids.size} 曲)"
        return "🎵 $what を共有します\n${link(p)}\n\nMusicPlayer で開くと、そのままライブラリに追加できます"
    }

    /** 大きいリストをファイルで送る時の中身(読めるように曲名つき。アプリはリンクの行を読む) */
    fun fileText(p: Payload, titles: List<String>): String = buildString {
        append("MusicPlayer の${p.kind.label}「${p.name}」(${p.ids.size} 曲)\n")
        append("このファイルを MusicPlayer で開くか、下のリンクを MusicPlayer の検索の 📋 に貼ると追加できます\n\n")
        append(link(p)).append("\n\n")
        p.ids.forEachIndexed { i, id ->
            append("${i + 1}. ${titles.getOrNull(i).orEmpty()}  https://music.youtube.com/watch?v=$id\n")
        }
    }
}
