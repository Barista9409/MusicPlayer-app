package com.example.ytmp3

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.URLEncoder

/** YouTube / YouTube Music の検索と試聴用 URL の取得(yt-dlp を使用) */
object Searcher {
    enum class Source { MUSIC, YOUTUBE }
    enum class Section(val label: String, val fragment: String) {
        SONGS("曲", "songs"), ARTISTS("アーティスト", "artists"), ALBUMS("アルバム", "albums"), VIDEOS("動画", "videos"), PLAYLISTS("プレイリスト", "community playlists");
        /** その検索先での呼び名(YouTube ではアーティスト = チャンネル) */
        fun labelFor(src: Source) = if (src == Source.YOUTUBE && this == ARTISTS) "チャンネル" else label
    }
    enum class Kind { SONG, VIDEO, ALBUM, PLAYLIST, ARTIST }
    /** YouTube の並び順(YouTube 側で並べる。アップロード日順などは効かないので入れない) */
    enum class Sort(val label: String) { RELEVANCE("関連度"), VIEWS("再生回数順") }

    data class Result(
        val kind: Kind,
        val id: String,
        val url: String,
        val title: String,
        val artist: String,
        val durationSec: Long,
        val thumb: String?,
        /** 再生回数(分からなければ 0) */
        val views: Long = 0,
        /** 公開・発売のだいたいの日時(ミリ秒。分からなければ 0) */
        val released: Long = 0,
        /** アルバム名(分からなければ空) */
        val album: String = "",
        /** YouTube Music で灰色になっている(Premium 会員限定など。この端末では聴けない) */
        val restricted: Boolean = false,
        /** YouTube Music での種類(MUSIC_VIDEO_TYPE_ATV = 音源・静止画 / OMV・UGC など = 動画)。分からなければ空 */
        val videoType: String = "",
    ) {
        val isList get() = kind == Kind.ALBUM || kind == Kind.PLAYLIST
        val isArtist get() = kind == Kind.ARTIST
        val videoId get() = if (isList || isArtist) null else youtubeVideoId(url) ?: id.takeIf { it.length == 11 }
    }

    private const val SEARCH_PID = "search"

    /** 直近の検索・試聴の経路と時間(画面に表示して原因を分かりやすく) */
    val status = kotlinx.coroutines.flow.MutableStateFlow("")

    private fun sec(t0: Long) = String.format("%.1f秒", (System.currentTimeMillis() - t0) / 1000.0)
    private fun short(e: Throwable) = (e.message ?: e.javaClass.simpleName).lines().first().take(60)

    /** 検索。まず内部 API に直接(速い)、だめなら yt-dlp(2〜5 秒) */
    suspend fun search(query: String, source: Source, section: Section, sort: Sort = Sort.RELEVANCE): InnerTube.Page {
        val t0 = System.currentTimeMillis()
        var why = ""
        runCatching { InnerTube.search(query, source, section, sort) }
            .onSuccess {
                if (it.results.isNotEmpty()) { status.value = "検索 ${sec(t0)}(直接)"; return it }
                why = "結果 0 件"
            }
            .onFailure { why = short(it); android.util.Log.w("Searcher", "InnerTube search failed", it) }
        val t1 = System.currentTimeMillis()
        val r = searchWithYtDlp(query, source, section)
        status.value = "検索 ${sec(t0)}(直接 失敗: $why → yt-dlp ${sec(t1)})"
        return InnerTube.Page(r, null) // yt-dlp の時は続きなし
    }

    /** ラジオの記録用のまとまり(id = RDAMVM + 種の動画 ID) */
    fun radioList(seedVid: String, title: String, artist: String, thumb: String?) =
        Result(Kind.PLAYLIST, "RDAMVM$seedVid", "https://music.youtube.com/watch?v=$seedVid&list=RDAMVM$seedVid",
            title, artist, 0, thumb)

    /** アルバム/プレイリストの曲(試聴用)。内部 API → だめなら yt-dlp */
    suspend fun listTracks(list: Result): List<Result> {
        if (list.id.startsWith("RDAMVM")) return InnerTube.radio(list.id.removePrefix("RDAMVM"))
        runCatching { InnerTube.listTracks(list) }.onSuccess { if (it.isNotEmpty()) return it }
            .onFailure { android.util.Log.w("Searcher", "listTracks failed", it) }
        if (!DownloadManager.ready()) throw IllegalStateException("準備中です")
        val req = YoutubeDLRequest(list.url).apply {
            addOption("--flat-playlist"); addOption("-J"); addOption("--no-warnings")
        }
        val entries = JSONObject(YoutubeDL.getInstance().execute(req, "list-" + System.nanoTime()).out).optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val vid = e.optString("id").takeIf { it.length == 11 } ?: return@mapNotNull null
            Result(Kind.SONG, vid, "https://music.youtube.com/watch?v=$vid", e.optString("title").ifBlank { vid },
                e.optString("channel").ifBlank { list.artist }.removeSuffix(" - Topic"), e.optDouble("duration", 0.0).toLong(),
                if (list.kind == Kind.ALBUM) list.thumb else "https://i.ytimg.com/vi/$vid/hqdefault.jpg")
        }
    }

    /** 検索語がアーティスト名なら、そのアーティストの人気曲(YouTube Music の順位)。違えば null */
    fun topSongs(query: String): InnerTube.TopSongs? =
        runCatching { InnerTube.artistTopSongs(query) }.getOrElse {
            android.util.Log.w("Searcher", "top songs failed", it); null
        }

    /** 続きの結果(下にスクロールした時)。失敗したら空 */
    fun more(token: String, source: Source, section: Section): InnerTube.Page =
        runCatching { InnerTube.more(token, source, section) }.getOrElse {
            android.util.Log.w("Searcher", "more failed", it); InnerTube.Page(emptyList(), null)
        }

    private suspend fun searchWithYtDlp(query: String, source: Source, section: Section): List<Result> {
        // アーティストは yt-dlp では探せない(チャンネルは対象外)
        if (section == Section.ARTISTS) return emptyList()
        // YouTube の yt-dlp 検索は動画だけ
        if (source == Source.YOUTUBE && section != Section.VIDEOS) return emptyList()
        if (!DownloadManager.ready()) throw IllegalStateException("準備中です。少し待ってからお試しください")
        runCatching { YoutubeDL.getInstance().destroyProcessById(SEARCH_PID) }
        val target = when (source) {
            Source.YOUTUBE -> "ytsearch50:$query"
            Source.MUSIC -> "https://music.youtube.com/search?q=" + URLEncoder.encode(query, "UTF-8") +
                "#" + URLEncoder.encode(section.fragment, "UTF-8").replace("+", "%20")
        }
        val req = YoutubeDLRequest(target).apply {
            addOption("--flat-playlist")
            addOption("-J")
            addOption("--no-warnings")
            addOption("--playlist-end", 50)
        }
        val out = YoutubeDL.getInstance().execute(req, SEARCH_PID).out
        val entries = JSONObject(out).optJSONArray("entries") ?: return emptyList()
        val list = mutableListOf<Result>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val id = e.optString("id")
            var url = e.optString("url").ifBlank { e.optString("webpage_url") }
            if (url.isBlank() && id.length == 11) url = "https://www.youtube.com/watch?v=$id"
            if (url.isBlank()) continue
            val lower = url.lowercase()
            val kind = when {
                lower.contains("/browse/mpre") || lower.contains("olak5uy_") -> Kind.ALBUM
                lower.contains("list=") || lower.contains("/playlist") || lower.contains("/browse/") -> Kind.PLAYLIST
                lower.contains("/channel/") || lower.contains("/@") -> continue // アーティスト・チャンネルは対象外
                source == Source.MUSIC && section == Section.SONGS -> Kind.SONG
                source == Source.MUSIC && section == Section.ALBUMS -> Kind.ALBUM
                else -> Kind.VIDEO
            }
            val artist = e.optString("channel").ifBlank { e.optString("uploader") }.ifBlank {
                e.optJSONArray("artists")?.let { a -> List(a.length()) { a.optString(it) }.joinToString(", ") }.orEmpty()
            }.removeSuffix(" - Topic")
            val thumbs = e.optJSONArray("thumbnails")
            val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
                ?: youtubeVideoId(url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            list += Result(kind, id, url, e.optString("title").ifBlank { url }, artist, e.optDouble("duration", 0.0).toLong(), thumb,
                e.optLong("view_count", 0L))
        }
        return list
    }

    // ───────── 試聴(ストリーミング) ─────────

    private val streamScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    /** 動画 ID → 取得中/取得済みの URL(期限付き) */
    private val streams = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String>>()
    /** 同時に動かす yt-dlp は 2 つまで(スマホの負荷を抑える) */
    private val streamSlots = kotlinx.coroutines.sync.Semaphore(2)

    /** 試聴の URL 取得の経路(押した時に画面に出す) */
    @Volatile var lastStream = ""

    /** 先読みで、空き待ち(まだ取り始めていない)の動画 ID */
    private val waitingSlot = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** その曲の URL を今まさに取っている最中か(待っている間に取りやめて、最初からやり直さないように) */
    fun isFetching(vid: String?): Boolean = vid != null && streams[vid]?.isActive == true

    /** 取得済みの URL(期限切れまで使い回す) */
    private val urlCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 試聴用のストリーミング URL。取得中・取得済みなら使い回す(▶ は先読みより優先) */
    suspend fun streamUrl(videoUrl: String): String {
        val vid = youtubeVideoId(videoUrl) ?: return fetchStream(videoUrl, "stream")
        return deferredFor(vid, videoUrl, priority = true).await()
    }

    /** 裏の作業用: 再生のための取得を優先して、空いている時に取る */
    suspend fun streamUrlQuiet(vid: String): String = deferredFor(vid, "https://www.youtube.com/watch?v=$vid", priority = false).await()

    /** 検索結果の上位を先に取得しておく(押した時にすぐ鳴るように) */
    fun prefetch(results: List<Result>, count: Int = 5) {
        // 訪問者 ID も先に用意しておく
        streamScope.launch { runCatching { InnerTube.visitorData() } }
        results.asSequence().filter { !it.isList }.take(count).forEach { r ->
            val vid = r.videoId ?: return@forEach
            deferredFor(vid, r.url, priority = false)
        }
    }

    @Synchronized
    private fun deferredFor(vid: String, url: String, priority: Boolean): kotlinx.coroutines.Deferred<String> {
        urlCache[vid]?.takeIf { notExpired(it) }?.let { return kotlinx.coroutines.CompletableDeferred(it) }
        // 何度試しても再生できない曲(Premium 限定など)は、すぐにあきらめる
        unplayable[vid]?.let { why ->
            return kotlinx.coroutines.CompletableDeferred<String>().apply { completeExceptionally(Unplayable(why)) }
        }
        streams[vid]?.takeIf { it.isActive }?.let { old ->
            // 先読みが空き待ちのまま(まだ取り始めていない)なら、待たずに今すぐ取り始める(再生の番が来た曲を後回しにしない)
            if (priority && vid in waitingSlot) old.cancel() else return old
        }
        val d = streamScope.async {
            val direct = vid !in ytdlpOnly
            val u = if (priority) fetchStream(url, "stream:$vid", direct)
            else {
                waitingSlot.add(vid)
                try {
                    streamSlots.withPermit { waitingSlot.remove(vid); fetchStream(url, "stream:$vid", direct) }
                } finally { waitingSlot.remove(vid) }
            }
            urlCache[vid] = u
            // 取り終えたものは一覧から外す(聴いた曲の数だけ溜まり続けないように)。URL は urlCache に残る
            streams.remove(vid)
            // 期限切れの URL を片付け、多すぎる時は減らす
            if (urlCache.size > 200) {
                urlCache.entries.removeIf { !notExpired(it.value) }
                if (urlCache.size > 200) urlCache.keys.take(urlCache.size - 150).forEach { urlCache.remove(it) }
            }
            u
        }
        streams[vid] = d
        d.invokeOnCompletion { if (streams[vid] === d) streams.remove(vid) }
        return d
    }

    /** URL の expire= から期限を見る(余裕を 2 分みる) */
    private fun notExpired(url: String): Boolean {
        val exp = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: return true
        return exp * 1000 > System.currentTimeMillis() + 120_000
    }

    /** 近道の URL で再生できなかった動画(次からは yt-dlp で取る) */
    private val ytdlpOnly = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 取り直しても再生できない曲(動画 ID → 理由)。Premium 会員限定・非公開・削除済み など */
    private val unplayable = java.util.concurrent.ConcurrentHashMap<String, String>()

    class Unplayable(why: String) : IllegalStateException(why)

    fun isUnplayable(vid: String) = unplayable.containsKey(vid)

    /** 聴けない理由(分からなければ null) */
    fun unplayableReason(vid: String): String? = unplayable[vid]

    /** 一覧で灰色だった曲を覚える(再生の番が来たら、取りにいかずにすぐ飛ばす) */
    fun markRestricted(vid: String) { unplayable.putIfAbsent(vid, RESTRICTED) }

    const val RESTRICTED = "Premium 限定の曲"

    /** エラーの文から「何度やっても無理」な理由を読み取る(違えば null) */
    fun permanentReason(msg: String?): String? {
        val m = msg ?: return null
        return when {
            m.contains("Premium", ignoreCase = true) -> "Music Premium 会員だけが聴ける曲"
            m.contains("Private video", ignoreCase = true) || m.contains("非公開") -> "非公開の動画"
            m.contains("has been removed", ignoreCase = true) || m.contains("削除") -> "削除された動画"
            m.contains("not available in your country", ignoreCase = true) || m.contains("お住まいの国") -> "この国では聴けない曲"
            m.contains("members-only", ignoreCase = true) || m.contains("Join this channel", ignoreCase = true) -> "チャンネルのメンバー限定"
            else -> null
        }
    }

    /** 再生エラーになった回数(動画 ID ごと) */
    private val playFails = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 再生エラーの時に呼ぶ。取り直す価値があれば true。
     * 1 回目: URL を捨てて近道で取り直す(期限・回線の切り替えで断られただけなら、これで速く直る)
     * 2 回目: yt-dlp で取り直す(時間はかかるが確実) / 3 回目以降: false
     */
    fun markDirectFailed(vid: String): Boolean {
        urlCache.remove(vid); streams.remove(vid)
        if (unplayable.containsKey(vid)) return false
        val n = (playFails[vid] ?: 0) + 1
        playFails[vid] = n
        if (n >= 2) ytdlpOnly.add(vid)
        return n <= 2
    }

    /** 回線が変わった時: 取得済みの URL は前の回線(IP)用なので全部捨てる(使うと 403 で断られる) */
    fun forgetUrls() {
        urlCache.clear()
        playFails.clear()
    }

    /** 次の曲などを裏で先に取っておく */
    fun warm(vid: String) { deferredFor(vid, "https://www.youtube.com/watch?v=$vid", priority = false) }

    /** 再生サービスから: 仮の URL を本物に(読み込みスレッドで待つ) */
    fun resolveBlocking(vid: String): String = kotlinx.coroutines.runBlocking {
        deferredFor(vid, "https://www.youtube.com/watch?v=$vid", priority = true).await()
    }

    /** 近道の URL が再生できなかった時: yt-dlp で取り直す(キャッシュも捨てる) */
    suspend fun streamUrlViaYtDlp(videoUrl: String): String {
        youtubeVideoId(videoUrl)?.let { vid -> urlCache.remove(vid); streams.remove(vid) }
        return fetchStream(videoUrl, "stream-fallback", direct = false)
    }

    private suspend fun fetchStream(videoUrl: String, pid: String, direct: Boolean = true): String {
        // 近道: 内部 API に直接(0.5 秒前後)
        val t0 = System.currentTimeMillis()
        var why = ""
        if (direct) youtubeVideoId(videoUrl)?.let { vid ->
            runCatching { InnerTube.audioUrl(vid) }
                .onSuccess { lastStream = "再生URL ${sec(t0)}(直接: ${InnerTube.lastClient})"; return it }
                .onFailure { why = short(it); android.util.Log.w("Searcher", "InnerTube player failed", it) }
            permanentReason(why)?.let { r -> unplayable[vid] = r; lastStream = "再生URL(直接: $r)"; throw Unplayable(r) }
        }
        lastStream = if (direct) "再生URL(直接 失敗: $why → yt-dlp)" else "再生URL(yt-dlp)"
        if (!DownloadManager.ready()) throw IllegalStateException("準備中です")
        var last: Exception? = null
        // android_vr は上の直接取得で試し済みなので、yt-dlp は既定 → tv の順
        val attempts = listOf(null, "youtube:player_client=tv")
        for (args in attempts) {
            try {
                val req = YoutubeDLRequest(videoUrl).apply {
                    addOption("--no-playlist")
                    addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                    addOption("-g")
                    addOption("--no-warnings")
                    addOption("--no-check-formats")
                    // 暗号解読の結果などを保存して 2 回目以降を速く
                    DownloadManager.cacheDir()?.let { addOption("--cache-dir", it.absolutePath) }
                    if (args != null) addOption("--extractor-args", args)
                }
                val out = YoutubeDL.getInstance().execute(req, pid).out
                out.lines().firstOrNull { it.startsWith("http") }?.let {
                    lastStream += " ${sec(t0)}"
                    return it.trim()
                }
            } catch (e: YoutubeDL.CanceledException) {
                throw e
            } catch (e: Exception) {
                // Premium 限定などは、別の方法で試しても同じなのですぐ終える
                permanentReason(e.message)?.let { r ->
                    youtubeVideoId(videoUrl)?.let { unplayable[it] = r }
                    throw Unplayable(r)
                }
                last = e
            }
        }
        throw last ?: IllegalStateException("再生できませんでした")
    }

    /** 動画プレビューの URL(720p まで)。近道 → yt-dlp */
    suspend fun videoSources(videoUrl: String): InnerTube.VideoSources {
        val t0 = System.currentTimeMillis()
        var why = ""
        youtubeVideoId(videoUrl)?.let { vid ->
            runCatching { InnerTube.videoSources(vid) }
                .onSuccess { status.value = "動画 ${sec(t0)}(直接: ${InnerTube.lastClient} · ${it.label})"; return it }
                .onFailure { why = short(it) }
        }
        if (!DownloadManager.ready()) throw IllegalStateException("準備中です")
        val req = YoutubeDLRequest(videoUrl).apply {
            addOption("--no-playlist")
            addOption("-f", "bv*[height<=720][ext=mp4]+ba[ext=m4a]/bv*[height<=720]+ba/b[height<=720]/b")
            addOption("-g")
            addOption("--no-warnings")
            DownloadManager.cacheDir()?.let { addOption("--cache-dir", it.absolutePath) }
        }
        val lines = YoutubeDL.getInstance().execute(req, "video-" + System.nanoTime()).out.lines().filter { it.startsWith("http") }
        status.value = "動画 ${sec(t0)}(直接 失敗: $why → yt-dlp)"
        return when {
            lines.size >= 2 -> InnerTube.VideoSources(lines[0].trim(), lines[1].trim(), null, "720p")
            lines.size == 1 -> InnerTube.VideoSources(null, null, lines[0].trim(), "")
            else -> throw IllegalStateException("動画の URL を取得できませんでした")
        }
    }

    fun cancel() {
        runCatching { YoutubeDL.getInstance().destroyProcessById(SEARCH_PID) }
    }
}
