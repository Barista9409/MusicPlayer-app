package com.example.ytmp3

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * YouTube の「ちゃんとしたアプリか」の確認(PO トークン)を、スマホの中の見えないブラウザで作る。
 * NewPipe(https://github.com/TeamNewPipe/NewPipe, GPL-3.0)の PoTokenWebView / PoTokenProviderImpl /
 * JavaScriptUtil を、このアプリ用に書き直したもの(RxJava の代わりに Future を使う)。
 *
 * 流れ: YouTube から確認の問題(BotGuard)をもらう → 見えないブラウザで解く → 答えを送って「合格証」をもらう →
 * 合格証から、訪問者 ID 用(再生のデータ用)と動画ごとの合言葉を作る。合格証は数時間使い回す
 */
object PoToken : PoTokenProvider {
    private lateinit var app: Context
    fun init(c: Context) { app = c.applicationContext }

    private val lock = Any()
    private var generator: Generator? = null
    private var visitorData: String? = null
    private var streamingPot: String? = null
    /** 見えないブラウザが使えない端末(古い・壊れている)では、もう試さない */
    @Volatile private var webViewBroken = false
    /** 作れなかった時は、しばらく試さない(毎回待たないように) */
    @Volatile private var failedAt = 0L
    private const val RETRY_AFTER_MS = 5 * 60_000L
    @Volatile private var reported = false

    /** 確認の合言葉を作れる状態か(作れた合格証があり、期限内) */
    fun ready(): Boolean = synchronized(lock) { generator?.isExpired() == false }

    /** 起動後、裏で先に用意しておく(最初の曲で待たないように) */
    fun warmUp() {
        if (!::app.isInitialized || webViewBroken) return
        Thread({ runCatching { getWebClientPoToken("dQw4w9WgXcQ") } }, "potoken-warm").start()
    }

    /** NewPipe から呼ばれる(動画ごと)。作れなければ null(合言葉なしで試す) */
    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!::app.isInitialized || webViewBroken) return null
        if (System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null // 画面の処理を止めないように
        return try {
            val t0 = System.currentTimeMillis()
            get(videoId, forceRecreate = false).also {
                if (!reported) {
                    reported = true
                    ErrorLog.add("確認の合言葉", "作れました(${(System.currentTimeMillis() - t0) / 100 / 10.0}秒)")
                }
            }
        } catch (e: BadWebView) {
            webViewBroken = true
            ErrorLog.add("確認の合言葉", "この端末のブラウザ部品では作れません", e)
            null
        } catch (e: Throwable) {
            failedAt = System.currentTimeMillis()
            ErrorLog.add("確認の合言葉", "作れませんでした(5 分後にまた試します)", e)
            null
        }
    }

    private fun get(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val (gen, vd, spot, recreated) = synchronized(lock) {
            val recreate = generator == null || forceRecreate || generator!!.isExpired()
            if (recreate) {
                val info = InnertubeClientRequestInfo.ofWebClient()
                info.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
                visitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                    info,
                    NewPipe.getPreferredLocalization(),
                    NewPipe.getPreferredContentCountry(),
                    YoutubeParsingHelper.getYouTubeHeaders(),
                    YoutubeParsingHelper.YOUTUBEI_V1_URL,
                    null,
                    false
                )
                generator?.close()
                generator = null
                val g = Generator.create(app)
                generator = g
                // 再生のデータ用の合言葉は、ほかより先に 1 回だけ作る決まり
                streamingPot = g.generate(visitorData!!)
            }
            Quad(generator!!, visitorData!!, streamingPot!!, recreate)
        }
        val playerPot = try {
            gen.generate(videoId)
        } catch (e: Throwable) {
            if (recreated) throw e
            // 裏に回っている間にブラウザの中身が消えたなど: 作り直してもう一度
            return get(videoId, forceRecreate = true)
        }
        return PoTokenResult(vd, playerPot, spot)
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    class BadWebView(message: String) : Exception(message)
    class PoTokenError(message: String) : Exception(message)

    private fun jsError(e: String): Exception = if (e.contains("SyntaxError")) BadWebView(e) else PoTokenError(e)

    /** 見えないブラウザ 1 つ分(合格証を持っている) */
    private class Generator private constructor(private val ctx: Context) {
        private val main = Handler(Looper.getMainLooper())
        private val ready = CompletableFuture<Generator>()
        private val waiting = ConcurrentHashMap<String, CompletableFuture<String>>()
        private var webView: WebView? = null
        @Volatile private var expiresAt = 0L

        fun isExpired() = System.currentTimeMillis() >= expiresAt

        @SuppressLint("SetJavaScriptEnabled")
        private fun start() {
            val wv = WebView(ctx)
            webView = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.safeBrowsingEnabled = false
            wv.settings.userAgentString = UA
            wv.settings.blockNetworkLoads = true // ブラウザ自体は通信しない(通信はアプリが代わりに行う)
            wv.addJavascriptInterface(Bridge(), JS)
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.message().contains("Uncaught")) {
                        val e = BadWebView("\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})")
                        fail(e)
                        waiting.keys.toList().forEach { waiting.remove(it)?.completeExceptionally(e) }
                    }
                    return super.onConsoleMessage(m)
                }
            }
            val html = ctx.assets.open("po_token.html").bufferedReader().use { it.readText() }
            wv.loadDataWithBaseURL("https://www.youtube.com",
                html.replaceFirst("</script>", "\n$JS.downloadAndRunBotguard()</script>"), "text/html", "utf-8", null)
        }

        private fun fail(e: Throwable) {
            if (ready.completeExceptionally(e)) main.post { close() }
        }

        private fun js(code: String, done: (String?) -> Unit = {}) {
            main.post { webView?.evaluateJavascript(code) { done(it) } ?: fail(PoTokenError("ブラウザが閉じられた")) }
        }

        /** 見えないブラウザから呼ばれる窓口 */
        private inner class Bridge {
            @JavascriptInterface
            fun downloadAndRunBotguard() {
                background {
                    val challenge = parseChallengeData(post(CREATE_URL, "[ \"$REQUEST_KEY\" ]"))
                    js("""try {
                        data = $challenge
                        runBotGuard(data).then(function (result) {
                            this.webPoSignalOutput = result.webPoSignalOutput
                            $JS.onRunBotguardResult(result.botguardResponse)
                        }, function (error) {
                            $JS.onJsInitializationError(error + "\n" + error.stack)
                        })
                    } catch (error) {
                        $JS.onJsInitializationError(error + "\n" + error.stack)
                    }""")
                }
            }

            @JavascriptInterface
            fun onJsInitializationError(error: String) = fail(jsError(error))

            @JavascriptInterface
            fun onRunBotguardResult(botguardResponse: String) {
                background {
                    val (integrityToken, seconds) = parseIntegrityTokenData(
                        post(GENERATE_IT_URL, "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]"))
                    // 期限は 10 分の余裕をみる
                    expiresAt = System.currentTimeMillis() + (seconds - 600) * 1000
                    js("this.integrityToken = $integrityToken") { ready.complete(this@Generator) }
                }
            }

            @JavascriptInterface
            fun onObtainPoTokenError(identifier: String, error: String) {
                waiting.remove(identifier)?.completeExceptionally(jsError(error))
            }

            @JavascriptInterface
            fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
                val f = waiting.remove(identifier) ?: return
                runCatching { u8ToBase64(poTokenU8) }.onSuccess { f.complete(it) }.onFailure { f.completeExceptionally(it) }
            }
        }

        private fun background(block: () -> Unit) {
            Thread({ try { block() } catch (e: Throwable) { fail(e) } }, "potoken").start()
        }

        /** 合言葉を 1 つ作る(待つ。画面の処理からは呼ばない) */
        fun generate(identifier: String): String {
            val f = CompletableFuture<String>()
            waiting.putIfAbsent(identifier, f)?.let { return it.get(TOKEN_TIMEOUT_S, TimeUnit.SECONDS) }
            val u8 = stringToU8(identifier)
            js("""try {
                    identifier = "$identifier"
                    u8Identifier = $u8
                    poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                    poTokenU8String = ""
                    for (i = 0; i < poTokenU8.length; i++) {
                        if (i != 0) poTokenU8String += ","
                        poTokenU8String += poTokenU8[i]
                    }
                    $JS.onObtainPoTokenResult(identifier, poTokenU8String)
                } catch (error) {
                    $JS.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                }""")
            try {
                return f.get(TOKEN_TIMEOUT_S, TimeUnit.SECONDS)
            } finally {
                waiting.remove(identifier, f)
            }
        }

        fun close() {
            main.post {
                val wv = webView ?: return@post
                webView = null
                runCatching {
                    wv.loadUrl("about:blank")
                    wv.onPause()
                    wv.removeAllViews()
                    wv.destroy()
                }
            }
        }

        companion object {
            fun create(ctx: Context): Generator {
                val g = Generator(ctx)
                g.main.post { try { g.start() } catch (e: Throwable) { g.fail(if (e is BadWebView) e else BadWebView(e.toString())) } }
                try {
                    return g.ready.get(INIT_TIMEOUT_S, TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.ExecutionException) {
                    g.close()
                    throw e.cause ?: e
                } catch (e: Exception) {
                    g.close()
                    throw e
                }
            }
        }
    }

    // ───────── 通信と変換(NewPipe の JavaScriptUtil と同じ) ─────────

    // BotGuard が使う公開の API キー(NewPipe と同じ)
    private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
    private const val JS = "PoTokenWebView"
    private const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
    private const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
    private const val INIT_TIMEOUT_S = 25L
    private const val TOKEN_TIMEOUT_S = 10L

    private fun post(url: String, data: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 10000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Content-Type", "application/json+protobuf")
            c.setRequestProperty("x-goog-api-key", GOOGLE_API_KEY)
            c.setRequestProperty("x-user-agent", "grpc-web-javascript/0.1")
            c.outputStream.use { it.write(data.toByteArray()) }
            val code = c.responseCode
            if (code != 200) throw PoTokenError("返事が $code")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun JSONArray.str(i: Int): String? = opt(i) as? String
    private fun JSONArray.firstString(i: Int): String? =
        optJSONArray(i)?.let { a -> (0 until a.length()).map { a.opt(it) }.firstOrNull { it is String } as String? }

    /** 確認の問題を、見えないブラウザに渡せる形に */
    private fun parseChallengeData(raw: String): String {
        val scrambled = JSONArray(raw)
        val cd = if (scrambled.length() > 1 && scrambled.opt(1) is String) JSONArray(descramble(scrambled.getString(1)))
        else scrambled.getJSONArray(0)
        val o = JSONObject()
            .put("messageId", cd.str(0))
            .put("interpreterJavascript", JSONObject()
                .put("privateDoNotAccessOrElseSafeScriptWrappedValue", cd.firstString(1))
                .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", cd.firstString(2)))
            .put("interpreterHash", cd.str(3))
            .put("program", cd.str(4))
            .put("globalName", cd.str(5))
            .put("clientExperimentsStateBlob", cd.str(7))
        return o.toString()
    }

    /** 合格証(JavaScript の Uint8Array の形)と、その有効な秒数 */
    private fun parseIntegrityTokenData(raw: String): Pair<String, Long> {
        val a = JSONArray(raw)
        return newUint8Array(base64ToBytes(a.getString(0))) to a.getLong(1)
    }

    private fun stringToU8(identifier: String) = newUint8Array(identifier.toByteArray())

    /** "97,98,99" の形を、合言葉の base64 に */
    private fun u8ToBase64(poToken: String): String {
        val bytes = poToken.split(",").map { it.trim().toInt().toByte() }.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            .replace("+", "-").replace("/", "_")
    }

    private fun descramble(s: String): String =
        base64ToBytes(s).map { (it + 97).toByte() }.toByteArray().toString(Charsets.UTF_8)

    private fun newUint8Array(b: ByteArray) = "new Uint8Array([" + b.joinToString(",") { (it.toInt() and 0xff).toString() } + "])"

    private fun base64ToBytes(s: String): ByteArray {
        val m = s.replace('-', '+').replace('_', '/').replace('.', '=')
        return android.util.Base64.decode(m, android.util.Base64.DEFAULT) ?: throw PoTokenError("base64 を読めません")
    }
}
