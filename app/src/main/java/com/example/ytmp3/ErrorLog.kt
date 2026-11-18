package com.example.ytmp3

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最近のエラーの記録(設定 → このアプリについて → エラー履歴 で見てコピーできる)。
 * 再生が止まった・次の曲へ飛ばした・URL が取れなかった・アプリが落ちた などを残す。
 * 新しい 300 件まで、かつ 2 週間より古いものは自動で消す。アプリを更新した時は境目の印を入れる。
 */
object ErrorLog {
    private const val MAX = 300
    /** これより古い記録は消す */
    private const val KEEP_DAYS = 14
    private var file: File? = null
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN)
    private var version = ""

    fun init(c: Context) {
        val f = File(c.filesDir, "error_log.txt")
        file = f
        version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull().orEmpty()
        synchronized(lines) {
            lines.clear()
            runCatching { f.readLines() }.getOrNull()?.takeLast(MAX)?.let { lines.addAll(it) }
            dropOld()
            // 更新した時は境目の印(前の版の記録と見分けられるように)
            val verFile = File(c.filesDir, "error_log_ver.txt")
            val before = runCatching { verFile.readText().trim() }.getOrNull()
            if (before != version) {
                if (!before.isNullOrBlank() && version.isNotBlank())
                    lines.addLast("${fmt.format(Date())} ── v$version に更新(前: v$before) ──")
                runCatching { verFile.writeText(version) }
            }
            runCatching { f.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n") }
        }
        // アプリが落ちた時も記録してから、いつもの処理へ
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { add("クラッシュ", "スレッド ${t.name}", e) }
            prev?.uncaughtException(t, e)
        }
    }

    /** 1 件追加(例外があれば原因の連なりも短く) */
    fun add(tag: String, message: String, e: Throwable? = null) {
        val cause = e?.let { chain(it) }
        val line = buildString {
            append(fmt.format(Date())).append(" [").append(tag).append("] ").append(message.replace('\n', ' '))
            if (cause != null) append(" ← ").append(cause)
        }
        android.util.Log.w("ErrorLog", line)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
            dropOld()
            runCatching { file?.writeText(lines.joinToString("\n") + "\n") }
        }
    }

    /** 2 週間より古い記録を消す(行の頭の「月/日」で判断。年をまたいだ分も考える) */
    private fun dropOld() {
        val now = System.currentTimeMillis()
        val cut = now - KEEP_DAYS * 86_400_000L
        while (lines.isNotEmpty()) {
            val t = timeOf(lines.first(), now) ?: break
            if (t >= cut) break
            lines.removeFirst()
        }
    }

    /** 行の頭の「MM/dd HH:mm:ss」の時刻(未来になる時は去年のもの) */
    internal fun timeOf(line: String, now: Long): Long? {
        val m = Regex("^(\\d{2})/(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})").find(line) ?: return null
        val (mo, d, h, mi, se) = m.destructured
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val year = cal.get(java.util.Calendar.YEAR)
        cal.set(year, mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), se.toInt())
        cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis > now + 86_400_000L) cal.add(java.util.Calendar.YEAR, -1)
        return cal.timeInMillis
    }

    /** 問題ではない、いつもの出来事(「問題を送る」には入れない。画面では見られる) */
    fun isRoutine(line: String): Boolean =
        ("[アプリ更新]" in line && " MB を " in line) || "イヤホン・Bluetooth が外れた" in line

    /** 原因の連なりを日本語で短く(最後に、アプリの中で起きた場所) */
    private fun chain(e: Throwable): String {
        val out = mutableListOf<String>()
        var t: Throwable? = e
        var n = 0
        while (t != null && n < 4) {
            explain(t)?.let { if (it !in out) out += it }
            t = t.cause.takeIf { it !== t }
            n++
        }
        val where = e.stackTrace.firstOrNull { it.className.startsWith("com.example.ytmp3") }
            ?.let { "(場所 ${it.fileName}:${it.lineNumber})" }.orEmpty()
        return out.ifEmpty { listOf("原因不明") }.joinToString(" / ") + where
    }

    /** 例外 1 つを日本語に(分かりきったものは null で省く) */
    internal fun explain(t: Throwable): String? {
        val name = t.javaClass.simpleName
        val msg = t.message?.lines()?.firstOrNull()?.trim()?.take(160).orEmpty()
        val code = Regex("Response code: (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            msg.contains("Premium", ignoreCase = true) -> "Music Premium 会員だけが聴ける曲"
            code != null -> "サーバーの返事 $code" + when (code) {
                403 -> "(断られた)"; 404 -> "(見つからない)"; 410 -> "(期限切れ)"; 429 -> "(回数が多すぎる)"
                in 500..599 -> "(サーバー側の不調)"; else -> ""
            }
            name == "ExoPlaybackException" && msg.startsWith("Source error") -> null
            name == "ExoPlaybackException" && msg.startsWith("Unexpected runtime error") -> "再生中の想定外のエラー"
            name == "InterruptedException" || name == "CancellationException" || name == "JobCancellationException" -> "途中で取りやめた"
            name == "SocketTimeoutException" -> "時間切れ(通信が遅い)"
            name == "UnknownHostException" -> "つながらない(電波・ネットを確認)"
            name == "ConnectException" -> "サーバーにつながらない"
            name == "SSLException" || name == "SSLHandshakeException" -> "安全な通信ができない"
            name == "FileNotFoundException" -> "ファイルが見つからない" + msg.takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty()
            name == "OutOfMemoryError" -> "メモリが足りない"
            name == "SecurityException" -> "許可がない" + msg.takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty()
            // アプリが自分で出したもの(日本語の説明つき)はそのまま
            msg.any { it.code > 0x3000 } -> msg
            else -> "$name: $msg".trimEnd(':', ' ')
        }
    }

    /** 再生エラーの種類を日本語に */
    fun playbackError(code: Int, name: String): String = when (name) {
        "ERROR_CODE_IO_BAD_HTTP_STATUS" -> "読み込みを断られた"
        "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" -> "通信に失敗"
        "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" -> "通信の時間切れ"
        "ERROR_CODE_IO_FILE_NOT_FOUND" -> "ファイルが見つからない"
        "ERROR_CODE_IO_NO_PERMISSION" -> "ファイルを読む許可がない"
        "ERROR_CODE_IO_UNSPECIFIED" -> "読み込みの失敗"
        "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE" -> "ファイルの範囲外を読もうとした"
        "ERROR_CODE_PARSING_CONTAINER_MALFORMED", "ERROR_CODE_PARSING_MANIFEST_MALFORMED" -> "ファイルが壊れている"
        "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" -> "対応していない形式"
        "ERROR_CODE_DECODER_INIT_FAILED", "ERROR_CODE_DECODING_FAILED" -> "音・映像の変換に失敗"
        "ERROR_CODE_AUDIO_TRACK_INIT_FAILED", "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED" -> "音の出力に失敗"
        "ERROR_CODE_BEHIND_LIVE_WINDOW" -> "ライブ配信に遅れた"
        "ERROR_CODE_TIMEOUT" -> "時間切れ"
        else -> "再生できない(番号 $code)"
    }

    /** 表示・コピー用(新しい順) */
    fun text(): String = synchronized(lines) {
        if (lines.isEmpty()) "(記録はありません)"
        else "MusicPlayer v$version / Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})\n\n" +
            lines.reversed().joinToString("\n")
    }

    /** 新しい順に n 件 */
    fun recent(n: Int): List<String> = synchronized(lines) { lines.reversed().take(n) }

    fun count() = synchronized(lines) { lines.size }

    fun clear() = synchronized(lines) {
        lines.clear()
        runCatching { file?.delete() }
    }
}
