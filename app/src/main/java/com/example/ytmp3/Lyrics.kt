package com.example.ytmp3

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 歌詞: 無料の歌詞データベース LRCLIB (lrclib.net) から取得。
 * 時間付き(LRC)があれば今の行を強調できる。取得結果は端末にキャッシュ。
 */
object Lyrics {
    data class Line(val timeMs: Long, val text: String)
    data class Result(val synced: List<Line>, val plain: String) {
        val isEmpty get() = synced.isEmpty() && plain.isBlank()
    }

    private const val UA = "MusicPlayer/1.0 (personal Android music app)"

    /** 曲の歌詞(見つからなければ空の Result、通信失敗は例外) */
    fun get(context: Context, title: String, artist: String, album: String, durationSec: Long, videoId: String? = null): Result {
        val r = getLrclib(context, title, artist, album, durationSec)
        if (!r.isEmpty) return r
        // 見つからなければ YouTube Music の歌詞(日本の曲に強い)
        return getYtm(context, title, artist, videoId) ?: r
    }

    private fun getYtm(context: Context, title: String, artist: String, videoId: String?): Result? {
        val key = ("ytm|" + (videoId ?: "$title|$artist")).lowercase().hashCode().toString(16)
        val cache = File(File(context.filesDir, "lyrics").apply { mkdirs() }, "$key.txt")
        if (cache.exists()) return cache.readText().takeIf { it.isNotBlank() }?.let { Result(emptyList(), it) }
        val text = runCatching {
            val vid = videoId ?: InnerTube.findSongId(TrackEditor.cleanTitle(title, artist).first, artist)
            vid?.let { InnerTube.ytmLyrics(it) }
        }.getOrElse { return null }.orEmpty()
        runCatching { cache.writeText(text) }
        return text.takeIf { it.isNotBlank() }?.let { Result(emptyList(), it) }
    }

    private fun getLrclib(context: Context, title: String, artist: String, album: String, durationSec: Long): Result {
        val (cleanTitle, foundArtist) = TrackEditor.cleanTitle(title, artist)
        val a = artist.removeSuffix(" - Topic").ifBlank { foundArtist }
        val key = (cleanTitle + "|" + a).lowercase().hashCode().toString(16)
        val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
        val cache = File(dir, "$key.json")
        if (cache.exists()) runCatching { return parse(JSONObject(cache.readText())) }

        val o = fetchExact(cleanTitle, a, album, durationSec) ?: search(cleanTitle, a) ?: JSONObject()
        runCatching { cache.writeText(o.toString()) }
        return parse(o)
    }

    private fun fetchExact(title: String, artist: String, album: String, durationSec: Long): JSONObject? {
        if (artist.isBlank()) return null
        val q = buildString {
            append("track_name=").append(enc(title)).append("&artist_name=").append(enc(artist))
            if (album.isNotBlank()) append("&album_name=").append(enc(album))
            if (durationSec > 0) append("&duration=").append(durationSec)
        }
        return getJson("https://lrclib.net/api/get?$q") as? JSONObject
    }

    private fun search(title: String, artist: String): JSONObject? {
        val url = if (artist.isNotBlank())
            "https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}"
        else "https://lrclib.net/api/search?q=${enc(title)}"
        val arr = getJson(url) as? JSONArray ?: return null
        // 時間付きの歌詞があるものを優先
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        return all.firstOrNull { it.optString("syncedLyrics").isNotBlank() } ?: all.firstOrNull()
    }

    private fun getJson(url: String): Any? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.trim()
            return if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun parse(o: JSONObject): Result {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let(::parseLrc).orEmpty()
        val plain = o.optString("plainLyrics").takeIf { it != "null" }.orEmpty()
        return Result(synced, plain)
    }

    /** "[01:23.45] 歌詞" の形を読む(1 行に複数の時刻があっても OK) */
    private fun parseLrc(lrc: String): List<Line> {
        val tag = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
        val out = mutableListOf<Line>()
        for (raw in lrc.lines()) {
            val tags = tag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim()
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out += Line((min * 60 + sec) * 1000 + ms, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
