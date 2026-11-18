package com.example.ytmp3

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 2 つ目の取り方: NewPipe Extractor(yt-dlp とは別のチームが YouTube の変化に対応している部品)。
 * アプリの中でそのまま動くので、別のプログラムを立ち上げる yt-dlp より速い。
 * 版はアプリに組み込み(新しい版は、毎日の自動チェックでアプリの更新として届く)
 */
object NewPipeSource {
    /** 組み込んでいる版(設定に出す) */
    val version: String get() = BuildConfig.NEWPIPE_VERSION

    /** YouTube にはっきり断られた時は、しばらく使わない(断られ続けている時に、むだに待たないように) */
    private const val BROKEN_MS = 10 * 60_000L
    @Volatile private var brokenAt = 0L
    fun isBroken(): Boolean = System.currentTimeMillis() - brokenAt < BROKEN_MS
    private fun markBroken(why: String) {
        brokenAt = System.currentTimeMillis()
        ErrorLog.add("NewPipe", "NewPipe が断られた($why)。10 分ほど別の方法で取ります")
    }

    /** NewPipe の読み込み時に名乗る名前(NewPipe と同じくパソコンの Firefox) */
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    @Volatile private var ready = false
    @Synchronized private fun ensureInit() {
        if (ready) return
        NewPipe.init(Dl, Localization("ja", "JP"), ContentCountry("JP"))
        ready = true
    }

    /** NewPipe が問い合わせに使う通信(Android 標準の仕組みで) */
    private object Dl : Downloader() {
        override fun execute(request: Request): Response {
            val c = URL(request.url()).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000; c.readTimeout = 10000
                c.instanceFollowRedirects = true
                c.requestMethod = request.httpMethod()
                var hasUa = false
                request.headers().forEach { (k, vs) ->
                    if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
                    vs.forEachIndexed { i, v -> if (i == 0) c.setRequestProperty(k, v) else c.addRequestProperty(k, v) }
                }
                if (!hasUa) c.setRequestProperty("User-Agent", UA)
                request.dataToSend()?.let { body ->
                    c.doOutput = true
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                val raw = if (code >= 400) c.errorStream else c.inputStream
                val stream = if (raw != null && c.contentEncoding.equals("gzip", ignoreCase = true)) java.util.zip.GZIPInputStream(raw) else raw
                val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
                val headers = HashMap<String, List<String>>()
                c.headerFields.forEach { (k, v) -> if (k != null) headers[k] = v }
                return Response(code, c.responseMessage, headers, body, c.url.toString())
            } finally {
                c.disconnect()
            }
        }
    }

    private fun info(vid: String): StreamInfo {
        ensureInit()
        try {
            return StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$vid")
        } catch (e: ReCaptchaException) {
            markBroken("確認を求められた")
            throw e
        } catch (e: Exception) {
            // 何度やっても聴けない曲(Premium 限定など)は、ほかの方法でも同じなのですぐ終える
            permanentReason(e)?.let { throw Searcher.Unplayable(it) }
            throw e
        }
    }

    /** 例外の種類から「何度やっても無理」な理由を読む(違えば null) */
    private fun permanentReason(e: Throwable): String? = when (e::class.java.simpleName) {
        "YoutubeMusicPremiumContentException" -> "Music Premium 会員だけが聴ける曲"
        "PrivateContentException" -> "非公開の動画"
        "GeographicRestrictionException" -> "この国では聴けない曲"
        "PaidContentException" -> "チャンネルのメンバー限定"
        else -> null
    }

    /** 元の音声(吹き替え・解説音声でないもの)の中で、m4a のいちばん良い音 */
    private fun bestAudio(info: StreamInfo): AudioStream? {
        val all = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val original = all.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }.ifEmpty { all }
        return original.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: original.maxByOrNull { it.averageBitrate }
    }

    /** 実際に読めるか(途中で断られないか)確かめる。断られたら理由、問題なければ null */
    private fun refused(url: String): String? {
        val ua = InnerTube.userAgentForStream(url) ?: UA
        var why = InnerTube.probeProblem(url, ua)
        if (why == InnerTube.PROBE_NO_CONNECTION) {
            why = InnerTube.probeProblem(url, ua)
            if (why == InnerTube.PROBE_NO_CONNECTION) return null // つながらないだけ(断られたのではない)
        }
        return why
    }

    /** 再生用の音声 URL */
    fun audioUrl(vid: String): String {
        if (isBroken()) throw IllegalStateException("NewPipe: しばらく使わない")
        val url = bestAudio(info(vid))?.content ?: throw IllegalStateException("NewPipe: 音声が見つかりません")
        refused(url)?.let { why -> markBroken(why); throw IllegalStateException("NewPipe: $why") }
        return url
    }

    /** 動画の URL(映像 + 音声。無ければ音声付きの一本もの) */
    fun videoSources(vid: String, maxHeight: Int = 720): InnerTube.VideoSources {
        if (isBroken()) throw IllegalStateException("NewPipe: しばらく使わない")
        val info = info(vid)
        val video = info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..maxHeight }
            // 互換性の高い H.264(avc1)を優先、同じ画質ならビットレートの高い方
            .sortedWith(compareByDescending<VideoStream> { it.height }
                .thenByDescending { it.codec?.startsWith("avc1") == true }
                .thenByDescending { it.bitrate })
            .firstOrNull()
        val audio = bestAudio(info)?.content
        val prog = info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.height }?.content
        if (video != null && audio != null) {
            val why = refused(video.content) ?: refused(audio)
            if (why == null) return InnerTube.VideoSources(video.content, audio, prog, "${video.height}p")
            if (prog == null || refused(prog) != null) { markBroken(why); throw IllegalStateException("NewPipe: 動画 $why") }
            return InnerTube.VideoSources(null, null, prog, "360p")
        }
        if (prog != null && refused(prog) == null) return InnerTube.VideoSources(null, null, prog, "360p")
        throw IllegalStateException("NewPipe: 映像が見つかりません")
    }
}
