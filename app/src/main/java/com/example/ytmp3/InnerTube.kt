package com.example.ytmp3

import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube の内部 API(InnerTube)に直接問い合わせる近道。
 * Python(yt-dlp)を起動しないので 1 回の通信で済み、とても速い。
 * 仕様変更で失敗した時は呼び出し側が yt-dlp に切り替える。
 */
object InnerTube {
    /**
     * 名乗り方(クライアントのバージョン等)。古いと YouTube に断られるので、
     * 自動更新される yt-dlp の中身(yt_dlp/extractor/youtube/_base.py)から読み取る。
     */
    data class Clients(
        val vrVersion: String = "1.65.10",
        val vrUserAgent: String = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        val vrDeviceMake: String = "Oculus",
        val vrDeviceModel: String = "Quest 3",
        val vrSdk: Int = 32,
        val vrOsVersion: String = "12L",
        val vrClientNameId: String = "28",
        val webRemixVersion: String = "1.20250310.01.00",
        val webVersion: String = "2.20250312.04.00",
        val source: String = "既定値",
    )

    @Volatile private var clients: Clients? = null

    val VR_USER_AGENT: String get() = clients().vrUserAgent

    /** yt-dlp の更新に合わせて読み直す */
    fun clients(): Clients {
        clients?.let { return it }
        val c = runCatching { loadFromYtDlp() }.getOrNull() ?: Clients()
        clients = c
        return c
    }

    fun reloadClients() {
        clients = null
        streamClients = null
        brokenClients.clear()
    }

    private lateinit var appCtx: android.content.Context
    fun init(context: android.content.Context) { appCtx = context.applicationContext }

    private fun loadFromYtDlp(): Clients? {
        if (!::appCtx.isInitialized) return null
        val f = java.io.File(appCtx.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
        if (!f.exists()) return null
        val prefs = appCtx.getSharedPreferences("innertube", android.content.Context.MODE_PRIVATE)
        val stamp = "${f.length()}:${f.lastModified()}"
        // 前回と同じファイルなら保存しておいた値を使う
        if (prefs.getString("stamp", null) == stamp) {
            prefs.getString("json", null)?.let { j -> return fromJson(JSONObject(j)) }
        }
        val src = readZipEntry(f, "yt_dlp/extractor/youtube/_base.py") ?: return null
        fun block(name: String): String? {
            val i = src.indexOf("'$name': {")
            if (i < 0) return null
            val j = src.indexOf("\n    },", i).takeIf { it > 0 } ?: (i + 1500).coerceAtMost(src.length)
            return src.substring(i, j)
        }
        fun field(b: String?, key: String) = b?.let { Regex("'$key':\\s*'([^']+)'").find(it)?.groupValues?.get(1) }
        fun num(b: String?, key: String) = b?.let { Regex("'$key':\\s*(\\d+)").find(it)?.groupValues?.get(1) }
        val vr = block("android_vr") ?: return null
        val def = Clients()
        val c = Clients(
            vrVersion = field(vr, "clientVersion") ?: def.vrVersion,
            vrUserAgent = field(vr, "userAgent") ?: def.vrUserAgent,
            vrDeviceMake = field(vr, "deviceMake") ?: def.vrDeviceMake,
            vrDeviceModel = field(vr, "deviceModel") ?: def.vrDeviceModel,
            vrSdk = num(vr, "androidSdkVersion")?.toIntOrNull() ?: def.vrSdk,
            vrOsVersion = field(vr, "osVersion") ?: def.vrOsVersion,
            vrClientNameId = num(vr, "INNERTUBE_CONTEXT_CLIENT_NAME") ?: def.vrClientNameId,
            webRemixVersion = field(block("web_remix"), "clientVersion") ?: def.webRemixVersion,
            webVersion = field(block("web"), "clientVersion") ?: def.webVersion,
            source = "yt-dlp",
        )
        prefs.edit().putString("stamp", stamp).putString("json", toJson(c).toString()).apply()
        return c
    }

    /** 先頭に #!python 行が付いた zip から 1 ファイル読む */
    private fun readZipEntry(f: java.io.File, name: String): String? {
        val bytes = f.readBytes()
        var start = 0
        while (start < bytes.size - 4) {
            if (bytes[start] == 'P'.code.toByte() && bytes[start + 1] == 'K'.code.toByte() &&
                bytes[start + 2] == 3.toByte() && bytes[start + 3] == 4.toByte()) break
            start++
        }
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes, start, bytes.size - start)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == name) return z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return null
    }

    private fun toJson(c: Clients) = JSONObject()
        .put("vrVersion", c.vrVersion).put("vrUserAgent", c.vrUserAgent).put("vrDeviceMake", c.vrDeviceMake)
        .put("vrDeviceModel", c.vrDeviceModel).put("vrSdk", c.vrSdk).put("vrOsVersion", c.vrOsVersion)
        .put("vrClientNameId", c.vrClientNameId).put("webRemixVersion", c.webRemixVersion).put("webVersion", c.webVersion)

    private fun fromJson(o: JSONObject) = Clients(
        o.getString("vrVersion"), o.getString("vrUserAgent"), o.getString("vrDeviceMake"), o.getString("vrDeviceModel"),
        o.getInt("vrSdk"), o.getString("vrOsVersion"), o.getString("vrClientNameId"),
        o.getString("webRemixVersion"), o.getString("webVersion"), "yt-dlp",
    )
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ───────── 訪問者 ID(これが無いと「bot ではないことを確認」で断られる) ─────────

    @Volatile private var visitor: String? = null

    private fun prefs() = appCtx.getSharedPreferences("innertube", android.content.Context.MODE_PRIVATE)

    /** 保存してある訪問者 ID(7 日以内)か、新しく取得したもの */
    fun visitorData(forceNew: Boolean = false): String? {
        if (!forceNew) {
            visitor?.let { return it }
            if (::appCtx.isInitialized) {
                val p = prefs()
                val v = p.getString("visitor", null)
                if (v != null && System.currentTimeMillis() - p.getLong("visitorAt", 0) < 7 * 86_400_000L) {
                    visitor = v; return v
                }
            }
        }
        val v = runCatching { fetchVisitorData() }.getOrNull() ?: return visitor
        saveVisitor(v)
        return v
    }

    private fun saveVisitor(v: String) {
        visitor = v
        if (::appCtx.isInitialized) prefs().edit().putString("visitor", v).putLong("visitorAt", System.currentTimeMillis()).apply()
    }

    /** 検索などの応答に入っている訪問者 ID を覚えておく */
    private fun remember(res: JSONObject) {
        res.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.let { v ->
            if (visitor == null) saveVisitor(v)
        }
    }

    private fun fetchVisitorData(): String? {
        // 1) 専用 API
        runCatching {
            val body = JSONObject().put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB").put("clientVersion", clients().webVersion).put("hl", "ja").put("gl", "JP")))
            val res = post("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", body,
                mapOf("User-Agent" to WEB_UA, "Origin" to "https://www.youtube.com"))
            res.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // 2) トップページに埋め込まれている値
        val c = URL("https://www.youtube.com/?hl=ja").openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 4000; c.readTimeout = 6000
            c.setRequestProperty("User-Agent", WEB_UA)
            val html = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        } finally {
            c.disconnect()
        }
    }

    // ───────── 試聴用の音声 URL ─────────

    // ───────── 再生用クライアント(yt-dlp の標準に合わせる) ─────────

    /** 試聴の URL を取る方法(yt-dlp の client 定義をそのまま写したもの) */
    data class StreamClient(val key: String, val nameId: String, val client: JSONObject) {
        val userAgent get() = client.optString("userAgent")
        val version get() = client.optString("clientVersion")
        val clientName get() = client.optString("clientName")
    }

    /** yt-dlp 2025.11 時点で暗号解読も PO トークンも要らない方法(読み取りに失敗した時の既定) */
    private val defaultStreamClients = listOf(
        StreamClient("android_sdkless", "3", JSONObject()
            .put("clientName", "ANDROID").put("clientVersion", "20.10.38")
            .put("userAgent", "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip")
            .put("osName", "Android").put("osVersion", "11")),
    )

    @Volatile private var streamClients: List<StreamClient>? = null
    /** この起動中に 403 などで使えなかった方法 */
    private val brokenClients = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 断られた方法を使わない時間(YouTube 側が一時的に断ることもあるので、時間がたったらまた試す) */
    private const val BROKEN_MS = 10 * 60_000L
    private fun isBroken(key: String): Boolean {
        val t = brokenClients[key] ?: return false
        if (System.currentTimeMillis() - t < BROKEN_MS) return true
        brokenClients.remove(key)
        return false
    }

    fun streamClients(): List<StreamClient> {
        streamClients?.let { return it }
        val list = runCatching { loadStreamClients() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: defaultStreamClients
        streamClients = list
        return list
    }

    /**
     * yt-dlp の _DEFAULT_JSLESS_CLIENTS(暗号解読なしで使う標準の方法)の順に、
     * REQUIRE_JS_PLAYER が False で、HTTPS の PO トークンが必須でないものを選ぶ。
     */
    private fun loadStreamClients(): List<StreamClient>? {
        if (!::appCtx.isInitialized) return null
        val f = java.io.File(appCtx.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
        if (!f.exists()) return null
        val stamp = "${f.length()}:${f.lastModified()}"
        val p = prefs()
        if (p.getString("streamStamp", null) == stamp) {
            p.getString("streamClients", null)?.let { j ->
                val a = JSONArray(j)
                return List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    StreamClient(o.getString("key"), o.getString("nameId"), o.getJSONObject("client"))
                }
            }
        }
        val files = readZipEntries(f, setOf("yt_dlp/extractor/youtube/_base.py", "yt_dlp/extractor/youtube/_video.py"))
        val base = files["yt_dlp/extractor/youtube/_base.py"] ?: return null
        val video = files["yt_dlp/extractor/youtube/_video.py"].orEmpty()

        fun tuple(name: String) = Regex("$name\\s*=\\s*\\(([^)]*)\\)").find(video)?.groupValues?.get(1)
            ?.let { Regex("'([a-z_]+)'").findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()
        val order = (tuple("_DEFAULT_JSLESS_CLIENTS") + tuple("_DEFAULT_CLIENTS") +
            listOf("android_sdkless", "android_vr", "ios")).distinct()

        val out = mutableListOf<StreamClient>()
        for (key in order) {
            val i = base.indexOf("    '$key': {")
            if (i < 0) continue
            val j = base.indexOf("\n    },", i).takeIf { it > 0 } ?: continue
            val b = base.substring(i, j)
            // 暗号解読(JS)が要る方法は使えない
            if (!Regex("'REQUIRE_JS_PLAYER':\\s*False").containsMatchIn(b)) continue
            // HTTPS の PO トークンが必須なら使えない
            if (b.contains("PO_TOKEN_POLICIES")) continue
            val https = b.indexOf("StreamingProtocol.HTTPS")
            if (https >= 0 && Regex("required=True").find(b, https)?.let { it.range.first < b.indexOf(")", https) } == true) continue
            if (Regex("'REQUIRE_AUTH':\\s*True").containsMatchIn(b)) continue
            // client の中身をそのまま写す
            val ci = b.indexOf("'client': {").takeIf { it >= 0 } ?: continue
            val ce = b.indexOf("}", ci)
            val cb = b.substring(ci, ce)
            val client = JSONObject()
            Regex("'(\\w+)':\\s*'([^']*)'").findAll(cb).forEach { client.put(it.groupValues[1], it.groupValues[2]) }
            Regex("'(\\w+)':\\s*(\\d+)\\s*,").findAll(cb).forEach { client.put(it.groupValues[1], it.groupValues[2].toInt()) }
            if (!client.has("clientName") || !client.has("clientVersion")) continue
            val nameId = Regex("'INNERTUBE_CONTEXT_CLIENT_NAME':\\s*(\\d+)").find(b)?.groupValues?.get(1) ?: continue
            out += StreamClient(key, nameId, client)
        }
        if (out.isEmpty()) return null
        val arr = JSONArray()
        out.forEach { arr.put(JSONObject().put("key", it.key).put("nameId", it.nameId).put("client", it.client)) }
        p.edit().putString("streamStamp", stamp).putString("streamClients", arr.toString()).apply()
        return out
    }

    private fun readZipEntries(f: java.io.File, names: Set<String>): Map<String, String> {
        val bytes = f.readBytes()
        var start = 0
        while (start < bytes.size - 4) {
            if (bytes[start] == 'P'.code.toByte() && bytes[start + 1] == 'K'.code.toByte() &&
                bytes[start + 2] == 3.toByte() && bytes[start + 3] == 4.toByte()) break
            start++
        }
        val out = HashMap<String, String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes, start, bytes.size - start)).use { z ->
            while (out.size < names.size) {
                val e = z.nextEntry ?: break
                if (e.name in names) out[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    /** URL の c= から、再生時に名乗る User-Agent を決める */
    fun userAgentForStream(url: String): String? {
        val c = Regex("[?&]c=([A-Z_]+)").find(url)?.groupValues?.get(1) ?: return null
        streamClients().firstOrNull { it.clientName == c }?.let { return it.userAgent.ifBlank { null } }
        return when (c) {
            "ANDROID_VR" -> clients().vrUserAgent
            "WEB", "WEB_SAFARI", "MWEB", "WEB_REMIX", "WEB_EMBEDDED_PLAYER", "TVHTML5" -> WEB_UA
            else -> null
        }
    }

    // ───────── 試聴用の音声 URL ─────────

    /** 最後に成功した方法(画面表示用) */
    @Volatile var lastClient = ""

    /**
     * 再生用の音声 URL(m4a 優先)。yt-dlp の標準の方法を順に試し、
     * 実際に読めるか(403 にならないか)を確かめてから返す。
     */
    fun audioUrl(videoId: String): String {
        var last: Exception? = null
        val skipped = mutableListOf<StreamClient>()
        for (sc in streamClients()) {
            if (isBroken(sc.key)) { skipped += sc; continue }
            try {
                return tryClient(videoId, sc)
            } catch (e: Exception) {
                last = e
            }
        }
        // 使える速い方法が無い時は、遅い方法(yt-dlp)に回る前に、しばらく使わないことにした方法をもう一度だけ試す
        // (一時的に断られただけのことが多く、yt-dlp は 10 秒以上かかるため)
        for (sc in skipped) {
            try {
                val url = tryClient(videoId, sc)
                brokenClients.remove(sc.key)
                return url
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("使える方法がありません")
    }

    /** 1 つの方法で URL を取り、読めるか確かめる */
    private fun tryClient(videoId: String, sc: StreamClient): String {
        val url = try {
            audioUrlOnce(videoId, sc, visitorData())
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("LOGIN_REQUIRED") == true) audioUrlOnce(videoId, sc, visitorData(forceNew = true))
            else throw e
        }
        var why = probeProblem(url, sc.userAgent)
        // つながらなかっただけ(通信が一時的に遅い)なら、もう一度だけ確かめる。
        // それでもつながらない時は、断られたわけではないので、そのまま使う(しばらく使わない扱いにはしない)
        if (why == PROBE_NO_CONNECTION) {
            why = probeProblem(url, sc.userAgent)
            if (why == PROBE_NO_CONNECTION) { lastClient = sc.key; return url }
        }
        if (why != null) {
            brokenClients[sc.key] = System.currentTimeMillis() // はっきり断られた時だけ、しばらくこの方法は使わない
            ErrorLog.add("近道", "速い方法「${sc.key}」が断られた($why)。10 分ほど別の方法で取ります")
            throw IllegalStateException("${sc.key}: $why")
        }
        lastClient = sc.key
        return url
    }

    /** 確かめた時に、つながらなかった(断られたのではない) */
    private val PROBE_NO_CONNECTION = "先頭 つながらない"

    /**
     * 読めるか試す。問題なければ null、断られたら理由。
     * 最初の 2 バイトに加えて、ファイルの中ほども 2 バイト読む(最初だけ読ませて、その先を断ることがあるため)。
     * 中ほどは「はっきり断られた(403)」時だけだめとする(通信が遅いだけの時に使えなくしないように)
     */
    private fun probeProblem(url: String, ua: String): String? {
        val head = probeCode(url, ua, 0)
        if (head !in 200..299) return "先頭 ${codeText(head)}"
        val len = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (len < 2_000_000) return null
        val mid = probeCode(url, ua, len / 2)
        return if (mid == 403) "先頭 $head / 中ほど 403" else null
    }

    private fun probe(url: String, ua: String): Boolean = probeProblem(url, ua) == null

    private fun codeText(c: Int) = if (c < 0) "つながらない" else c.toString()

    /** HTTP の結果(つながらない時は -1) */
    private fun probeCode(url: String, ua: String, from: Long): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 3000; c.readTimeout = 3000
            if (ua.isNotBlank()) c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Range", "bytes=$from-${from + 1}")
            c.responseCode
        } catch (e: Exception) {
            -1
        } finally {
            c.disconnect()
        }
    }

    private fun audioUrlOnce(videoId: String, sc: StreamClient, visitorData: String?): String {
        val res = playerResponse(videoId, sc, visitorData)
        val formats = res.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
            ?: throw IllegalStateException("音声が見つかりません")
        return bestAudio(formats) ?: throw IllegalStateException("音声が見つかりません(暗号化された URL のみ)")
    }

    private fun bestAudio(formats: JSONArray): String? {
        val audio = (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
            .filter { it.optString("mimeType").startsWith("audio/") && it.optString("url").isNotBlank() }
        val best = audio.filter { it.optString("mimeType").startsWith("audio/mp4") }.maxByOrNull { it.optLong("bitrate") }
            ?: audio.maxByOrNull { it.optLong("bitrate") }
        return best?.getString("url")
    }

    // ───────── 動画プレビュー ─────────

    /** 動画の URL。video + audio を合わせて流す。無ければ音声付きの progressive(360p) */
    data class VideoSources(val video: String?, val audio: String?, val progressive: String?, val label: String)

    fun videoSources(videoId: String, maxHeight: Int = 720): VideoSources {
        var last: Exception? = null
        for (sc in streamClients()) {
            if (isBroken(sc.key)) continue
            try {
                val res = try {
                    playerResponse(videoId, sc, visitorData())
                } catch (e: IllegalStateException) {
                    if (e.message?.startsWith("LOGIN_REQUIRED") == true) playerResponse(videoId, sc, visitorData(forceNew = true))
                    else throw e
                }
                val sd = res.optJSONObject("streamingData") ?: throw IllegalStateException("映像が見つかりません")
                val adaptive = sd.optJSONArray("adaptiveFormats") ?: JSONArray()
                val videos = (0 until adaptive.length()).mapNotNull { adaptive.optJSONObject(it) }
                    .filter { it.optString("mimeType").startsWith("video/") && it.optString("url").isNotBlank() && it.optInt("height") <= maxHeight }
                // 互換性の高い H.264(avc1)を優先、同じ画質ならビットレートの高い方
                val video = videos.sortedWith(compareByDescending<JSONObject> { it.optInt("height") }
                    .thenByDescending { it.optString("mimeType").contains("avc1") }
                    .thenByDescending { it.optLong("bitrate") }).firstOrNull()
                val audio = bestAudio(adaptive)
                val prog = sd.optJSONArray("formats")?.let { f ->
                    (0 until f.length()).mapNotNull { f.optJSONObject(it) }
                        .filter { it.optString("url").isNotBlank() }.maxByOrNull { it.optInt("height") }?.optString("url")
                }
                val videoOk = video != null && audio != null && probe(video.getString("url"), sc.userAgent)
                if (!videoOk && (prog == null || !probe(prog, sc.userAgent))) {
                    throw IllegalStateException("${sc.key}: 動画サーバーが 403")
                }
                lastClient = sc.key
                return if (videoOk) VideoSources(video!!.getString("url"), audio, prog, "${video.optInt("height")}p")
                else VideoSources(null, null, prog, "360p")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("使える方法がありません")
    }

    private fun playerResponse(videoId: String, sc: StreamClient, visitorData: String?): JSONObject {
        val client = JSONObject(sc.client.toString()).put("hl", "ja").put("gl", "JP")
        client.remove("userAgent")
        if (visitorData != null) client.put("visitorData", visitorData)
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val res = post(
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body,
            buildMap {
                if (sc.userAgent.isNotBlank()) put("User-Agent", sc.userAgent)
                put("X-YouTube-Client-Name", sc.nameId)
                put("X-YouTube-Client-Version", sc.version)
                if (visitorData != null) put("X-Goog-Visitor-Id", visitorData)
            }
        )
        val status = res.optJSONObject("playabilityStatus")?.optString("status")
        if (status != "OK") {
            val reason = res.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
            throw IllegalStateException("$status $reason".trim())
        }
        return res
    }

    // ───────── 検索 ─────────

    /** 検索結果 1 ページ分と、続きを読むための鍵(無ければ null) */
    data class Page(val results: List<Searcher.Result>, val token: String?)

    fun search(query: String, source: Searcher.Source, section: Searcher.Section, sort: Searcher.Sort = Searcher.Sort.RELEVANCE): Page =
        when (source) {
            Searcher.Source.MUSIC -> searchMusic(query, section, null)
            Searcher.Source.YOUTUBE -> searchYouTube(query, null, sort, section)
        }

    /** 続きの結果(下にスクロールした時) */
    fun more(token: String, source: Searcher.Source, section: Searcher.Section): Page =
        when (source) {
            Searcher.Source.MUSIC -> searchMusic(null, section, token)
            Searcher.Source.YOUTUBE -> searchYouTube(null, token, section = section)
        }

    /** 応答の中から「続き」の鍵を探す(古い形式と新しい形式の両方) */
    private fun findContinuation(res: JSONObject): String? {
        val next = mutableListOf<JSONObject>()
        collect(res, "nextContinuationData", next)
        next.firstNotNullOfOrNull { it.optString("continuation").takeIf { t -> t.isNotBlank() } }?.let { return it }
        val cmds = mutableListOf<JSONObject>()
        collect(res, "continuationCommand", cmds)
        return cmds.firstNotNullOfOrNull { it.optString("token").takeIf { t -> t.isNotBlank() } }
    }

    private fun searchMusic(query: String?, section: Searcher.Section, token: String?): Page {
        // 種類ごとの絞り込み(YouTube Music の検索フィルタ)
        val params = when (section) {
            Searcher.Section.SONGS -> "EgWKAQIIAWoMEA4QChADEAQQCRAF"
            Searcher.Section.ARTISTS -> "EgWKAQIgAWoMEA4QChADEAQQCRAF"
            Searcher.Section.ALBUMS -> "EgWKAQIYAWoMEA4QChADEAQQCRAF"
            Searcher.Section.VIDEOS -> "EgWKAQIQAWoMEA4QChADEAQQCRAF"
            Searcher.Section.PLAYLISTS -> "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU="
        }
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", clients().webRemixVersion)
                .put("hl", "ja").put("gl", "JP")))
        if (token == null) body.put("query", query).put("params", params) else body.put("continuation", token)
        // 古い形式の続き読み込みは URL にも鍵を付ける
        val url = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false" +
            (token?.let { val t = java.net.URLEncoder.encode(it, "UTF-8"); "&ctoken=$t&continuation=$t&type=next" } ?: "")
        val res = post(
            url, body,
            mapOf("User-Agent" to WEB_UA, "Origin" to "https://music.youtube.com", "Referer" to "https://music.youtube.com/")
        )
        remember(res)
        val items = mutableListOf<JSONObject>()
        collect(res, "musicResponsiveListItemRenderer", items)
        return Page(items.mapNotNull { parseMusicItem(it, section) }.distinctBy { it.url }, findContinuation(res))
    }

    private fun parseMusicItem(o: JSONObject, section: Searcher.Section): Searcher.Result? {
        val cols = o.optJSONArray("flexColumns") ?: return null
        fun colRuns(i: Int): JSONArray? = cols.optJSONObject(i)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
        val titleRuns = colRuns(0) ?: return null
        val title = runsText(titleRuns).ifBlank { return null }
        // 2 列目以降(アーティスト・アルバム・再生回数・時間)をまとめて読む
        val fixed = o.optJSONArray("fixedColumns")
        val info = ((1 until cols.length()).mapNotNull { colRuns(it)?.let { r -> runsText(r) } } +
            (0 until (fixed?.length() ?: 0)).mapNotNull {
                textOf(fixed?.optJSONObject(it)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text"))
            }).filter { it.isNotBlank() }.joinToString(" • ")

        // 曲・動画: videoId / アルバム・プレイリスト: browseId
        val videoId = findString(o, "videoId")
        val browseId = o.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
            ?: titleRuns.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")

        val parts = info.split(" • ", " · ").map { it.trim() }.filter { it.isNotEmpty() }
        val typeWords = setOf("曲", "動画", "アルバム", "シングル", "EP", "プレイリスト", "アーティスト", "Song", "Video", "Album", "Single", "Playlist", "Artist")
        val time = parts.lastOrNull { Regex("^\\d{1,2}(:\\d{2}){1,2}$").matches(it) }
        val viewPart = parts.firstOrNull { isViewText(it) }
        val artist = parts.firstOrNull { it !in typeWords && it != time && it != viewPart }.orEmpty()

        val (kind, url, id) = when {
            section == Searcher.Section.ARTISTS && browseId != null && browseId.startsWith("UC") ->
                Triple(Searcher.Kind.ARTIST, "https://music.youtube.com/channel/$browseId", browseId)
            section == Searcher.Section.ARTISTS -> return null
            section == Searcher.Section.ALBUMS && browseId != null ->
                Triple(Searcher.Kind.ALBUM, "https://music.youtube.com/browse/$browseId", browseId)
            section == Searcher.Section.PLAYLISTS && browseId != null ->
                Triple(Searcher.Kind.PLAYLIST, "https://music.youtube.com/playlist?list=${browseId.removePrefix("VL")}", browseId)
            videoId != null && videoId.length == 11 ->
                Triple(if (section == Searcher.Section.VIDEOS) Searcher.Kind.VIDEO else Searcher.Kind.SONG,
                    "https://music.youtube.com/watch?v=$videoId", videoId)
            else -> return null
        }
        val thumbs = o.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")
            ?.replace(Regex("=w\\d+-h\\d+"), "=w300-h300")
            ?: if (kind == Searcher.Kind.SONG || kind == Searcher.Kind.VIDEO) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else null
        // アルバムなどの「2023」(発売年)
        val year = parts.firstOrNull { Regex("^(19|20)\\d{2}$").matches(it) }?.toIntOrNull()
        val released = year?.let { java.util.GregorianCalendar(it, 0, 1).timeInMillis } ?: 0L
        // YouTube Music で灰色の曲(Premium 会員限定など)。印が無ければ普通の曲として扱う
        val restricted = (kind == Searcher.Kind.SONG || kind == Searcher.Kind.VIDEO) &&
            o.optString("musicItemRendererDisplayPolicy") == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
        if (restricted) Searcher.markRestricted(id)
        return Searcher.Result(kind, id, url, title, artist, parseDuration(time), thumb, viewPart?.let { parseCount(it) } ?: 0L, released,
            restricted = restricted)
    }

    // ───────── アルバム・プレイリストの曲 ─────────

    /** アルバム/プレイリストの曲一覧(YouTube Music のページから。順番どおり) */
    fun listTracks(list: Searcher.Result): List<Searcher.Result> {
        val browseId = when {
            list.id.startsWith("MPRE") || list.id.startsWith("VL") -> list.id
            else -> Regex("[?&]list=([^&]+)").find(list.url)?.groupValues?.get(1)?.let { "VL$it" } ?: list.id
        }
        val page = musicPost("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", musicBody().put("browseId", browseId))
        val rows = mutableListOf<JSONObject>()
        collect(page, "musicResponsiveListItemRenderer", rows)
        // 100 曲を超えるプレイリストは、続きを順に読む(最大 5000 曲)
        // 続きの鍵は曲の一覧(プレイリストの棚)の中のものだけ使う(「おすすめ」など別の棚の続きを混ぜない)
        val shelves = mutableListOf<JSONObject>()
        collect(page, "musicPlaylistShelfRenderer", shelves)
        var token = shelves.firstNotNullOfOrNull { findContinuation(it) }
            ?: if (shelves.isEmpty()) findContinuation(page) else null
        val seen = HashSet<String>()
        var pages = 0
        while (token != null && seen.add(token) && pages < 50) {
            pages++
            val more = runCatching {
                musicPost("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", musicBody().put("continuation", token))
            }.getOrNull() ?: break
            val before = rows.size
            collect(more, "musicResponsiveListItemRenderer", rows)
            if (rows.size == before) break
            token = findContinuation(more)
        }
        return rows.mapNotNull { parseMusicItem(it, Searcher.Section.SONGS) }
            .map { t ->
                t.copy(
                    artist = t.artist.takeUnless { it.isBlank() || isViewText(it) } ?: list.artist,
                    // アルバムの曲にはジャケットが付いていないのでアルバムのものを使う
                    thumb = if (list.kind == Searcher.Kind.ALBUM) list.thumb ?: t.thumb else t.thumb,
                )
            }
            .distinctBy { it.id }
    }

    // ───────── ラジオ(似た曲) ─────────

    /**
     * 似た曲の一覧(先頭はこの曲)。YouTube Music のラジオと YouTube のミックスを同時に取って見比べ、
     * 片方にしか出てこず、アーティストも重ならない(信頼の低い)ものを弾く(RadioBlend)。
     * 種が YouTube Music の「曲」でない動画(瞑想・BGM・作業用など)なら YouTube のミックスを主にする
     * (YouTube Music は似た曲を探せず、人気の曲を返してくるため)
     */
    fun radio(videoId: String): List<Searcher.Result> = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val ytm = async { runCatching { musicRadio(videoId) }.getOrNull() }
        val yt = async { runCatching { youtubeMix("RD$videoId", videoId) }.getOrDefault(emptyList()) }
        val (list, isSong) = ytm.await() ?: (emptyList<Searcher.Result>() to null)
        val mix = yt.await()
        when {
            list.isEmpty() && mix.isEmpty() -> emptyList()
            isSong == false && mix.size >= 5 -> RadioBlend.blend(mix, list, videoId)
            list.isEmpty() -> mix
            else -> RadioBlend.blend(list, mix, videoId)
        }
    }

    /**
     * 似た曲の一覧を、取ってくる場所を選んで(あなたのミックス用)。
     * 両方 = radio(見比べ) / YouTube Music = そのラジオだけ / YouTube = YouTube のミックスだけ(ミュージックビデオを前に)
     */
    fun radioFrom(videoId: String, music: Boolean, youtube: Boolean): List<Searcher.Result> = when {
        music && youtube -> radio(videoId)
        music -> runCatching { musicRadio(videoId).first }.getOrDefault(emptyList())
        else -> preferMusicVideos(runCatching { youtubeMix("RD$videoId", videoId) }.getOrDefault(emptyList()))
    }

    private val mvTitle = Regex("(?i)\\bM/?V\\b|music ?video|official video|公式|ミュージックビデオ")

    /**
     * 動画だけの「似た曲」(ミックスの「ミュージックビデオ」だけの時): YouTube のミックスと YouTube Music のラジオを
     * 同時に取って交互に並べ、動画だけを残す(見比べて絞ると動画が減るので、ここでは絞らない)
     */
    fun videoRadio(videoId: String): List<Searcher.Result> = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val yt = async { runCatching { youtubeMix("RD$videoId", videoId) }.getOrDefault(emptyList()) }
        val ytm = async { runCatching { musicRadio(videoId).first }.getOrDefault(emptyList()) }
        val a = yt.await(); val b = ytm.await()
        (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }
            .filter { isVideo(it) }.distinctBy { it.id }
    }

    /**
     * 動画か(音源・静止画ではないか)。YouTube Music の種類が分かればそれで(ATV = 音源)、
     * 分からなければ「- Topic」のチャンネル(音源を自動で上げているもの)でないか
     */
    fun isVideo(r: Searcher.Result): Boolean =
        if (r.videoType.isNotEmpty()) r.videoType != "MUSIC_VIDEO_TYPE_ATV" else !r.artist.endsWith(" - Topic")

    /** ミュージックビデオらしいものを前に、音源だけの動画(「- Topic」のチャンネル)を後ろに(元の順はなるべく保つ) */
    fun preferMusicVideos(list: List<Searcher.Result>): List<Searcher.Result> =
        list.withIndex().sortedBy { (i, r) ->
            val rank = when {
                mvTitle.containsMatchIn(r.title) -> 0
                r.artist.endsWith(" - Topic") -> 2
                else -> 1
            }
            rank * 10_000 + i
        }.map { it.value }

    /** YouTube Music のラジオと、種が「曲」かどうか(分からなければ null) */
    private fun musicRadio(videoId: String): Pair<List<Searcher.Result>, Boolean?> {
        val res = musicPost("https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
            musicBody().put("videoId", videoId).put("playlistId", "RDAMVM$videoId")
                .put("isAudioOnly", true).put("enablePersistentPlaylistPanel", true))
        val items = mutableListOf<JSONObject>()
        collect(res, "playlistPanelVideoRenderer", items)
        val list = items.mapNotNull { panelItem(it) }.distinctBy { it.id }
        val seed = items.firstOrNull { it.optString("videoId") == videoId }
        val type = seed?.let { findString(it.optJSONObject("navigationEndpoint"), "musicVideoType") }.orEmpty()
        val isSong = if (type.isEmpty()) null
            else type == "MUSIC_VIDEO_TYPE_ATV" || type == "MUSIC_VIDEO_TYPE_OMV" || type == "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC"
        return list to isSong
    }

    /**
     * YouTube の「ミックス」(list=RD…)の中身を、YouTube にログインせずにそのまま読む。
     * 好みに合わせた並びにはならないが、YouTube のミックスと同じ作り方の一覧(MV・ライブなども入る)。読めなければ空
     */
    fun youtubeMix(listId: String, seedVid: String): List<Searcher.Result> {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", clients().webVersion)
                .put("hl", "ja").put("gl", "JP")))
            .put("videoId", seedVid)
            .put("playlistId", listId)
        val res = post(
            "https://www.youtube.com/youtubei/v1/next?prettyPrint=false", body,
            mapOf("User-Agent" to WEB_UA, "Origin" to "https://www.youtube.com")
        )
        remember(res)
        val items = mutableListOf<JSONObject>()
        collect(res, "playlistPanelVideoRenderer", items)
        return items.mapNotNull { panelItem(it) }.distinctBy { it.id }
    }

    /** 再生リストの 1 行(playlistPanelVideoRenderer)を曲に */
    private fun panelItem(v: JSONObject): Searcher.Result? {
        val id = v.optString("videoId").takeIf { it.length == 11 } ?: return null
        val title = textOf(v.optJSONObject("title")) ?: return null
        val by = (textOf(v.optJSONObject("longBylineText")) ?: textOf(v.optJSONObject("shortBylineText"))).orEmpty()
        val artist = by.split(" • ", " · ").firstOrNull()?.trim().orEmpty()
        val thumbs = v.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
            ?.replace(Regex("=w\\d+-h\\d+"), "=w300-h300")
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        return Searcher.Result(Searcher.Kind.SONG, id, "https://music.youtube.com/watch?v=$id", title, artist,
            parseDuration(textOf(v.optJSONObject("lengthText"))), thumb, album = albumOf(v.optJSONObject("longBylineText")),
            videoType = findString(v.optJSONObject("navigationEndpoint"), "musicVideoType").orEmpty())
    }

    /** 「アーティスト • アルバム • 年」のうち、アルバムのページへのリンクになっている部分 */
    private fun albumOf(byline: JSONObject?): String {
        val runs = byline?.optJSONArray("runs") ?: return ""
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            if (findString(r.optJSONObject("navigationEndpoint"), "pageType") == "MUSIC_PAGE_TYPE_ALBUM")
                return r.optString("text").trim()
        }
        return ""
    }

    /** 動画 ID から曲名・アーティスト・長さ・ジャケット(共有されたリストを読む時) */
    fun songInfo(videoId: String): Searcher.Result? {
        val res = musicPost("https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
            musicBody().put("videoId", videoId).put("enablePersistentPlaylistPanel", true))
        val items = mutableListOf<JSONObject>()
        collect(res, "playlistPanelVideoRenderer", items)
        return items.firstOrNull { it.optString("videoId") == videoId }?.let { panelItem(it) }
    }

    // ───────── 動画(MV)を探す ─────────

    /**
     * この曲の「映像のある動画」の ID。
     * YouTube Music の曲版(ジャケットの静止画だけ)なら、対になる MV を返す。MV が無ければ null。
     * もともと動画(MV・ライブ・普通の動画)ならその ID のまま。調べられない時もその ID のまま
     */
    fun musicVideoFor(videoId: String): String? = musicVideoLookup(videoId).first

    /** (動画の ID または null, 確かな答えか)。通信に失敗した時などは (videoId, false) */
    fun musicVideoLookup(videoId: String): Pair<String?, Boolean> {
        val res = runCatching {
            musicPost("https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
                musicBody().put("videoId", videoId).put("enablePersistentPlaylistPanel", true))
        }.getOrNull() ?: return videoId to false
        val items = mutableListOf<JSONObject>()
        collect(res, "playlistPanelVideoRenderer", items)
        val me = items.firstOrNull { it.optString("videoId") == videoId } ?: return videoId to false
        val type = findString(me.optJSONObject("navigationEndpoint"), "musicVideoType").orEmpty()
        if (type != "MUSIC_VIDEO_TYPE_ATV") return videoId to true
        // 曲版: 対になる MV(counterpart)を探す
        val wraps = mutableListOf<JSONObject>()
        collect(res, "playlistPanelVideoWrapperRenderer", wraps)
        for (w in wraps) {
            val primary = w.optJSONObject("primaryRenderer")?.optJSONObject("playlistPanelVideoRenderer")
            val cps = w.optJSONArray("counterpart") ?: continue
            val ids = mutableListOf(primary?.optString("videoId").orEmpty())
            for (i in 0 until cps.length()) {
                cps.optJSONObject(i)?.optJSONObject("counterpartRenderer")?.optJSONObject("playlistPanelVideoRenderer")
                    ?.optString("videoId")?.let { ids += it }
            }
            if (videoId in ids) return ids.firstOrNull { it.length == 11 && it != videoId } to true
        }
        return null to true
    }

    // ───────── 新作 ─────────

    data class Release(val result: Searcher.Result, val year: Int)

    /** アーティスト名 → (正式な名前, チャンネル ID)。名前がぴったり合う時だけ */
    fun findArtist(query: String): Pair<String, String>? {
        val res = musicPost("https://music.youtube.com/youtubei/v1/search?prettyPrint=false",
            musicBody().put("query", query).put("params", "EgWKAQIgAWoMEA4QChADEAQQCRAF"))
        val items = mutableListOf<JSONObject>()
        collect(res, "musicResponsiveListItemRenderer", items)
        val want = normName(query)
        return items.take(3).firstNotNullOfOrNull { o ->
            val runs = o.optJSONArray("flexColumns")?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
                ?: return@firstNotNullOfOrNull null
            val name = runsText(runs)
            val id = o.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
            if (id != null && id.startsWith("UC") && normName(name) == want) name to id else null
        }
    }

    data class ArtistRef(val name: String, val id: String, val thumb: String?)

    /** アーティストページの「ファンにおすすめ」(似ているアーティスト) */
    private fun relatedFrom(page: JSONObject): List<ArtistRef> {
        val shelves = mutableListOf<JSONObject>()
        collect(page, "musicCarouselShelfRenderer", shelves)
        val out = mutableListOf<ArtistRef>()
        for (sh in shelves) {
            val items = mutableListOf<JSONObject>()
            collect(sh, "musicTwoRowItemRenderer", items)
            for (o in items) {
                val id = findString(o.optJSONObject("navigationEndpoint") ?: o, "browseId")?.takeIf { it.startsWith("UC") } ?: continue
                val name = textOf(o.optJSONObject("title")) ?: continue
                val thumbs = o.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.replace(Regex("=w\\d+-h\\d+"), "=w400-h400")
                out += ArtistRef(name, id, thumb)
            }
        }
        return out.distinctBy { it.id }
    }

    /** アーティストページ: (アルバム・シングル, 似ているアーティスト) */
    fun artistPage(artistName: String, channelId: String): Pair<List<Release>, List<ArtistRef>> {
        val page = musicPost("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", musicBody().put("browseId", channelId))
        return releasesFrom(page, artistName) to relatedFrom(page)
    }

    /** アーティストページの「アルバム」「シングル」(新しい順とは限らない) */
    fun artistReleases(artistName: String, channelId: String): List<Release> {
        val page = musicPost("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", musicBody().put("browseId", channelId))
        return releasesFrom(page, artistName)
    }

    private fun releasesFrom(page: JSONObject, artistName: String): List<Release> {
        val shelves = mutableListOf<JSONObject>()
        collect(page, "musicCarouselShelfRenderer", shelves)
        val out = mutableListOf<Release>()
        for (sh in shelves) {
            val head = textOf(sh.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")?.optJSONObject("title")).orEmpty()
            val isAlbum = listOf("アルバム", "シングル", "Album", "Single", "EP").any { head.contains(it, ignoreCase = true) }
            if (!isAlbum) continue
            val items = mutableListOf<JSONObject>()
            collect(sh, "musicTwoRowItemRenderer", items)
            for (it in items) {
                val title = textOf(it.optJSONObject("title")) ?: continue
                val sub = textOf(it.optJSONObject("subtitle")).orEmpty()
                val year = Regex("(19|20)\\d{2}").find(sub)?.value?.toIntOrNull() ?: 0
                val id = findString(it.optJSONObject("navigationEndpoint") ?: it, "browseId")?.takeIf { b -> b.startsWith("MPRE") } ?: continue
                val thumbs = it.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.replace(Regex("=w\\d+-h\\d+"), "=w400-h400")
                out += Release(Searcher.Result(Searcher.Kind.ALBUM, id, "https://music.youtube.com/browse/$id", title, artistName, 0, thumb), year)
            }
        }
        return out.distinctBy { it.result.id }
    }

    // ───────── ランキング・ムード ─────────

    /** ページの中のプレイリスト(タイル)を集める */
    private fun playlistsIn(page: JSONObject): List<Searcher.Result> {
        val items = mutableListOf<JSONObject>()
        collect(page, "musicTwoRowItemRenderer", items)
        return items.mapNotNull { o ->
            val id = findString(o.optJSONObject("navigationEndpoint") ?: o, "browseId")?.takeIf { it.startsWith("VL") }
                ?: return@mapNotNull null
            val title = textOf(o.optJSONObject("title")) ?: return@mapNotNull null
            val sub = textOf(o.optJSONObject("subtitle")).orEmpty()
            val thumbs = o.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")
            Searcher.Result(Searcher.Kind.PLAYLIST, id, "https://music.youtube.com/playlist?list=${id.removePrefix("VL")}",
                title, sub, 0, thumb)
        }.distinctBy { it.id }
    }

    /** YouTube Music のランキング(日本) */
    fun charts(): List<Searcher.Result> {
        val page = musicPost("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",
            musicBody().put("browseId", "FEmusic_charts")
                .put("formData", JSONObject().put("selectedValues", JSONArray().put("JP"))))
        return playlistsIn(page)
    }

    data class Mood(val title: String, val browseId: String, val params: String)

    /** ムード・ジャンルの一覧(「チル」「作業用」「J-POP」など) */
    fun moods(): List<Mood> {
        val page = musicPost("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",
            musicBody().put("browseId", "FEmusic_moods_and_genres"))
        val btns = mutableListOf<JSONObject>()
        collect(page, "musicNavigationButtonRenderer", btns)
        return btns.mapNotNull { b ->
            val title = textOf(b.optJSONObject("buttonText")) ?: return@mapNotNull null
            val ep = b.optJSONObject("clickCommand")?.optJSONObject("browseEndpoint") ?: return@mapNotNull null
            Mood(title, ep.optString("browseId"), ep.optString("params"))
        }.distinctBy { it.title }
    }

    /**
     * 同じ曲の別の動画 ID(年齢確認が要る MV などで再生・ダウンロードできない時の代わり)。
     * 曲名とアーティストで探し直して、元と違う ID を返す。見つからなければ null
     */
    fun alternativeFor(vid: String, title: String? = null, artist: String? = null): String? {
        var t = title.orEmpty(); var a = artist.orEmpty()
        if (t.isBlank()) runCatching { radio(vid).firstOrNull { it.id == vid } }.getOrNull()?.let { t = it.title; a = it.artist }
        if (t.isBlank()) return null
        val q = listOf(t, a).filter { it.isNotBlank() }.joinToString(" ")
        // 全角・半角をそろえ、記号と空白を除いて比べる(「f．a．i．t．h」と「f.a.i.t.h」を同じに)
        val norm = { s: String ->
            java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }
        }
        val want = norm(t)
        if (want.isEmpty()) return null
        var searched = false
        var lastError: Exception? = null
        for (sec in listOf(Searcher.Section.SONGS, Searcher.Section.VIDEOS)) {
            val list = try { searchMusic(q, sec, null).results.also { searched = true } } catch (e: Exception) { lastError = e; emptyList() }
            val hit = list
                .firstOrNull { r ->
                    val id = r.videoId ?: return@firstOrNull false
                    // 同じ曲、灰色(Premium 限定など)、すでに聴けないと分かっているものは除く
                    id != vid && !r.restricted && !Searcher.isUnplayable(id) &&
                        norm(r.title).let { it.isNotEmpty() && (it.contains(want) || want.contains(it)) }
                }
            if (hit != null) return hit.videoId
        }
        // 検索そのものができなかった(通信エラーなど)時は「見つからない」と区別する(呼ぶ側で覚えないように)
        if (!searched) throw lastError ?: IllegalStateException("検索できませんでした")
        return null
    }

    /** 入力中の検索候補(YouTube Music) */
    fun suggestions(input: String): List<String> {
        val res = musicPost("https://music.youtube.com/youtubei/v1/music/get_search_suggestions?prettyPrint=false",
            musicBody().put("input", input))
        val items = mutableListOf<JSONObject>()
        collect(res, "searchSuggestionRenderer", items)
        return items.mapNotNull { o ->
            o.optJSONObject("suggestion")?.optJSONArray("runs")?.let { runsText(it) }?.takeIf { it.isNotBlank() }
        }.distinct().take(8)
    }

    /** ムード・ジャンルのプレイリスト */
    fun moodPlaylists(m: Mood): List<Searcher.Result> {
        val body = musicBody().put("browseId", m.browseId)
        if (m.params.isNotBlank()) body.put("params", m.params)
        return playlistsIn(musicPost("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", body))
    }

    /** YouTube Music の歌詞(時間なし)。無ければ null */
    fun ytmLyrics(videoId: String): String? {
        val next = musicPost("https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
            musicBody().put("videoId", videoId).put("isAudioOnly", true))
        val eps = mutableListOf<JSONObject>()
        collect(next, "browseEndpoint", eps)
        val lyricsId = eps.map { it.optString("browseId") }.firstOrNull { it.startsWith("MPLY") } ?: return null
        val page = musicPost("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", musicBody().put("browseId", lyricsId))
        val shelves = mutableListOf<JSONObject>()
        collect(page, "musicDescriptionShelfRenderer", shelves)
        return shelves.firstNotNullOfOrNull { textOf(it.optJSONObject("description"))?.takeIf { t -> t.isNotBlank() } }
    }

    /** 曲名とアーティストから YouTube Music の動画 ID を探す(ラジオの種用) */
    fun findSongId(title: String, artist: String): String? {
        val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (q.isBlank()) return null
        return searchMusic(q, Searcher.Section.SONGS, null).results.firstOrNull()?.videoId
    }

    // ───────── アーティストの人気曲 ─────────

    data class TopSongs(val artist: String, val songs: List<Searcher.Result>)

    /** 検索語とアーティスト名が一致したら、アーティストページの「人気曲」を返す */
    fun artistTopSongs(query: String): TopSongs? {
        // アーティストだけに絞った検索(YouTube Music の検索フィルタ)
        val res = musicPost("https://music.youtube.com/youtubei/v1/search?prettyPrint=false",
            musicBody().put("query", query).put("params", "EgWKAQIgAWoMEA4QChADEAQQCRAF"))
        val items = mutableListOf<JSONObject>()
        collect(res, "musicResponsiveListItemRenderer", items)
        val want = normName(query)
        if (want.isEmpty()) return null
        val hit = items.take(3).firstNotNullOfOrNull { o ->
            val runs = o.optJSONArray("flexColumns")?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
                ?: return@firstNotNullOfOrNull null
            val name = runsText(runs)
            val id = o.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
            if (id != null && id.startsWith("UC") && normName(name) == want) name to id else null
        } ?: return null
        val page = musicPost("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",
            musicBody().put("browseId", hit.second))
        // 最初の曲の棚が「人気曲」
        val shelves = mutableListOf<JSONObject>()
        collect(page, "musicShelfRenderer", shelves)
        val shelf = shelves.firstOrNull() ?: return null
        val rows = mutableListOf<JSONObject>()
        collect(shelf, "musicResponsiveListItemRenderer", rows)
        val songs = rows.mapNotNull { parseMusicItem(it, Searcher.Section.SONGS) }
            .map { if (it.artist.isBlank() || isViewText(it.artist)) it.copy(artist = hit.first) else it }
            .distinctBy { it.id }
        return if (songs.isEmpty()) null else TopSongs(hit.first, songs)
    }

    /**
     * チャンネル・アーティストのリンク(/channel/UC… や @名前)から、YouTube Music の人気曲。
     * @名前 などは YouTube に聞いて UC… に直す
     */
    fun channelTopSongs(url: String): TopSongs? {
        val id = Regex("/channel/(UC[A-Za-z0-9_-]{10,})").find(url)?.groupValues?.get(1)
            ?: runCatching {
                val res = musicPost("https://music.youtube.com/youtubei/v1/navigation/resolve_url?prettyPrint=false",
                    musicBody().put("url", url.replace("music.youtube.com", "www.youtube.com")))
                findString(res, "browseId")?.takeIf { it.startsWith("UC") }
            }.getOrNull() ?: return null
        val page = musicPost("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", musicBody().put("browseId", id))
        val heads = mutableListOf<JSONObject>()
        collect(page, "musicImmersiveHeaderRenderer", heads)
        collect(page, "musicVisualHeaderRenderer", heads)
        val name = heads.firstNotNullOfOrNull { textOf(it.optJSONObject("title")) }.orEmpty().ifBlank { "アーティスト" }
        val shelves = mutableListOf<JSONObject>()
        collect(page, "musicShelfRenderer", shelves)
        val shelf = shelves.firstOrNull() ?: return null
        val rows = mutableListOf<JSONObject>()
        collect(shelf, "musicResponsiveListItemRenderer", rows)
        val songs = rows.mapNotNull { parseMusicItem(it, Searcher.Section.SONGS) }
            .map { if (it.artist.isBlank() || isViewText(it.artist)) it.copy(artist = name) else it }
            .distinctBy { it.id }
        return if (songs.isEmpty()) null else TopSongs(name, songs)
    }

    private fun normName(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun musicBody() = JSONObject()
        .put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", clients().webRemixVersion)
            .put("hl", "ja").put("gl", "JP")))

    private fun musicPost(url: String, body: JSONObject): JSONObject = post(
        url, body,
        mapOf("User-Agent" to WEB_UA, "Origin" to "https://music.youtube.com", "Referer" to "https://music.youtube.com/")
    ).also { remember(it) }

    // ───────── 再生回数 ─────────

    private fun isViewText(t: String): Boolean {
        val l = t.lowercase()
        if (t.startsWith("再生回数") && Regex("[0-9]").containsMatchIn(t)) return true
        return Regex("^[0-9]").containsMatchIn(t) &&
            (t.contains("回再生") || t.contains("回視聴") || l.endsWith("views") || l.endsWith("plays") || l.endsWith("view") || l.endsWith("play"))
    }

    /** 「3 日前」「2 年前」「5 hours ago」→ 何ミリ秒前か(だいたい) */
    fun parseAgo(t: String): Long? {
        val m = Regex("([0-9]+)\\s*(秒|分|時間|日|週間|か月|ヶ月|カ月|年|second|minute|hour|day|week|month|year)").find(t.lowercase()) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val unit = when (m.groupValues[2]) {
            "秒", "second" -> 1000L
            "分", "minute" -> 60_000L
            "時間", "hour" -> 3_600_000L
            "日", "day" -> 86_400_000L
            "週間", "week" -> 7 * 86_400_000L
            "か月", "ヶ月", "カ月", "month" -> 30 * 86_400_000L
            else -> 365 * 86_400_000L
        }
        return n * unit
    }

    /** 「1.2億回再生」「345万 回視聴」「1,234,567 回視聴」「1.2M views」→ 数 */
    fun parseCount(t: String): Long? {
        val m = Regex("([0-9][0-9,.]*)\\s*(億|万|千|[KkMmBb])?").find(t) ?: return null
        val unit = m.groupValues[2]
        val raw = m.groupValues[1]
        // 単位なしの「1,234」はカンマ区切り、単位付きの「1,2」は小数点の国もあるので点に
        val num = (if (unit.isEmpty()) raw.replace(",", "") else raw.replace(",", ".")).toDoubleOrNull() ?: return null
        val mul = when (unit) {
            "億" -> 1e8; "万" -> 1e4; "千" -> 1e3
            "K", "k" -> 1e3; "M", "m" -> 1e6; "B", "b" -> 1e9
            else -> 1.0
        }
        return (num * mul).toLong().takeIf { it > 0 }
    }

    private fun textOf(o: JSONObject?): String? {
        o ?: return null
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        return o.optJSONArray("runs")?.let { runsText(it) }?.takeIf { it.isNotBlank() }
    }

    private fun searchYouTube(
        query: String?, token: String?, sort: Searcher.Sort = Searcher.Sort.RELEVANCE,
        section: Searcher.Section = Searcher.Section.VIDEOS,
    ): Page {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", clients().webVersion)
                .put("hl", "ja").put("gl", "JP")))
        // 種類の絞り込み(YouTube の検索フィルタ)。動画の再生回数順は並べ替え指定 CAM= を足したもの
        val params = when (section) {
            Searcher.Section.ARTISTS -> "EgIQAg=="
            Searcher.Section.PLAYLISTS -> "EgIQAw=="
            else -> if (sort == Searcher.Sort.VIEWS) "CAMSAhAB" else "EgIQAQ=="
        }
        if (token == null) body.put("query", query).put("params", params)
        else body.put("continuation", token)
        val res = post(
            "https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body,
            mapOf("User-Agent" to WEB_UA, "Origin" to "https://www.youtube.com")
        )
        remember(res)
        val list = when (section) {
            Searcher.Section.ARTISTS -> ytChannels(res)
            Searcher.Section.PLAYLISTS -> ytPlaylists(res)
            else -> ytVideos(res)
        }
        return Page(list.distinctBy { it.id }, findContinuation(res))
    }

    private fun ytVideos(res: JSONObject): List<Searcher.Result> {
        val items = mutableListOf<JSONObject>()
        collect(res, "videoRenderer", items)
        return items.mapNotNull { v ->
            val id = v.optString("videoId").takeIf { it.length == 11 } ?: return@mapNotNull null
            val title = v.optJSONObject("title")?.optJSONArray("runs")?.let { runsText(it) }.orEmpty().ifBlank { return@mapNotNull null }
            val artist = (v.optJSONObject("ownerText") ?: v.optJSONObject("longBylineText"))
                ?.optJSONArray("runs")?.let { runsText(it) }.orEmpty()
            val len = v.optJSONObject("lengthText")?.optString("simpleText")
            val views = textOf(v.optJSONObject("viewCountText"))?.let { parseCount(it) }
                ?: textOf(v.optJSONObject("shortViewCountText"))?.let { parseCount(it) } ?: 0L
            val ago = textOf(v.optJSONObject("publishedTimeText"))?.let { parseAgo(it) }
            Searcher.Result(Searcher.Kind.VIDEO, id, "https://www.youtube.com/watch?v=$id", title,
                artist.removeSuffix(" - Topic"), parseDuration(len), "https://i.ytimg.com/vi/$id/hqdefault.jpg", views,
                ago?.let { System.currentTimeMillis() - it } ?: 0L)
        }
    }

    /** YouTube のチャンネル(丸いカードで出す) */
    private fun ytChannels(res: JSONObject): List<Searcher.Result> {
        val items = mutableListOf<JSONObject>()
        collect(res, "channelRenderer", items)
        return items.mapNotNull { c ->
            val id = c.optString("channelId").takeIf { it.startsWith("UC") } ?: return@mapNotNull null
            val title = textOf(c.optJSONObject("title")).orEmpty().ifBlank { return@mapNotNull null }
            // 新しい形式では subscriberCountText に @ハンドル、videoCountText に登録者数が入る
            val info = listOfNotNull(textOf(c.optJSONObject("subscriberCountText")), textOf(c.optJSONObject("videoCountText")))
                .filter { it.isNotBlank() && !it.startsWith("@") }.joinToString(" • ")
            val thumbs = c.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("//")) "https:$it" else it }
                ?.replace(Regex("=s\\d+-"), "=s300-")
            Searcher.Result(Searcher.Kind.ARTIST, id, "https://www.youtube.com/channel/$id", title, info, 0, thumb)
        }
    }

    /** YouTube のプレイリスト(古い形式 playlistRenderer と新しい形式 lockupViewModel の両方) */
    private fun ytPlaylists(res: JSONObject): List<Searcher.Result> {
        val out = mutableListOf<Searcher.Result>()
        val old = mutableListOf<JSONObject>()
        collect(res, "playlistRenderer", old)
        old.forEach { p ->
            val id = p.optString("playlistId").ifBlank { return@forEach }
            val title = textOf(p.optJSONObject("title")).orEmpty().ifBlank { return@forEach }
            val by = (p.optJSONObject("shortBylineText") ?: p.optJSONObject("longBylineText"))
                ?.optJSONArray("runs")?.let { runsText(it) }.orEmpty()
            val count = p.optString("videoCount").takeIf { it.isNotBlank() }?.let { "${it} 本" }
            val thumb = findString(p.optJSONArray("thumbnails") ?: p.optJSONObject("thumbnail"), "url")
            out += Searcher.Result(Searcher.Kind.PLAYLIST, "VL$id", "https://www.youtube.com/playlist?list=$id", title,
                listOfNotNull(by.takeIf { it.isNotBlank() }, count).joinToString(" • "), 0, thumb)
        }
        val lockups = mutableListOf<JSONObject>()
        collect(res, "lockupViewModel", lockups)
        lockups.forEach { l ->
            if (!l.optString("contentType").contains("PLAYLIST") && !l.optString("contentType").contains("ALBUM")) return@forEach
            val id = l.optString("contentId").ifBlank { return@forEach }
            val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            val title = meta?.optJSONObject("title")?.optString("content").orEmpty().ifBlank { return@forEach }
            // 「作った人」「動画の数」などの小さな文字
            val texts = mutableListOf<String>()
            fun walk(n: Any?) {
                when (n) {
                    is JSONObject -> { n.optJSONObject("text")?.optString("content")?.takeIf { it.isNotBlank() }?.let { texts += it }; n.keys().forEach { walk(n.opt(it)) } }
                    is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
                }
            }
            walk(meta?.optJSONObject("metadata"))
            val badge = mutableListOf<String>()
            fun badges(n: Any?) {
                when (n) {
                    is JSONObject -> { n.optJSONObject("thumbnailBadgeViewModel")?.optString("text")?.takeIf { it.isNotBlank() }?.let { badge += it }; n.keys().forEach { badges(n.opt(it)) } }
                    is JSONArray -> for (i in 0 until n.length()) badges(n.opt(i))
                }
            }
            badges(l.optJSONObject("contentImage"))
            val info = (texts.filter { !it.contains("再生リストの全体") && !it.contains("全体を見る") && !it.contains("View full") } + badge)
                .distinct().take(2).joinToString(" • ")
            val thumb = findString(l.optJSONObject("contentImage"), "url")
            out += Searcher.Result(Searcher.Kind.PLAYLIST, if (id.startsWith("VL")) id else "VL$id",
                "https://www.youtube.com/playlist?list=${id.removePrefix("VL")}", title, info, 0, thumb)
        }
        return out
    }

    // ───────── helpers ─────────

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            // 失敗した時に長く待たない(その後 yt-dlp に切り替える)
            c.connectTimeout = 4000
            c.readTimeout = 6000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept-Language", "ja")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) {
                val err = runCatching { c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
                val msg = runCatching { JSONObject(err).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException("HTTP ${c.responseCode} $msg".trim())
            }
            return JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            c.disconnect()
        }
    }

    /** JSON の中から key の付いたオブジェクトを全部集める(構造が多少変わっても動くように) */
    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { out += it }
                node.keys().forEach { k -> if (k != key) collect(node.opt(k), key, out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun findString(node: Any?, key: String): String? {
        when (node) {
            is JSONObject -> {
                node.optString(key).takeIf { it.isNotEmpty() && node.opt(key) is String }?.let { return it }
                node.keys().forEach { k -> findString(node.opt(k), key)?.let { return it } }
            }
            is JSONArray -> for (i in 0 until node.length()) findString(node.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun runsText(runs: JSONArray) = (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }

    private fun parseDuration(t: String?): Long {
        if (t.isNullOrBlank()) return 0
        return t.split(":").mapNotNull { it.trim().toLongOrNull() }.fold(0L) { acc, n -> acc * 60 + n }
    }
}
