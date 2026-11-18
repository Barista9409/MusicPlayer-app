package com.example.ytmp3

/** ファイル名・フォルダ名を安全にする */
object FileNames {
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val spaces = Regex("\\s+")
    /** 文字・数字・空白とよく使う記号以外(絵文字など)を外す */
    private val nonSimple = Regex("[^\\p{L}\\p{N} ._()\\[\\]{}!#&+,;=@'~-]")

    /**
     * 使えない文字を "_" に置き換え、UTF-8 で maxBytes 以内に切り詰める。
     * Android のファイル名は 255 バイトまで。絵文字の途中で切らない。
     */
    fun safe(s: String, maxBytes: Int): String {
        val cleaned = s.replace(illegal, "_").replace(spaces, " ").trim().trim('.', ' ')
        return truncateUtf8(cleaned, maxBytes).trim().trim('.', ' ')
    }

    /** 保存に失敗したとき用: 絵文字や記号を外した名前 */
    fun simple(s: String, maxBytes: Int): String =
        safe(s.replace(nonSimple, "").replace(spaces, " "), maxBytes)

    fun truncateUtf8(s: String, maxBytes: Int): String {
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + n > maxBytes) break
            sb.appendCodePoint(cp)
            bytes += n
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
