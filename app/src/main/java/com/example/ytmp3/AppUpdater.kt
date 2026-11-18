package com.example.ytmp3

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * アプリの更新: 公開用リポジトリ(APK だけを置く)の最新リリースを見て、新しければダウンロードして
 * Android のインストール画面を出す(最後の「インストール」は本人が押す)。
 */
object AppUpdater {
    const val REPO = "Barista9409/MusicPlayer-releases"
    /** 共有用: いつでも最新版の APK がダウンロードできるリンク */
    const val SHARE_URL = "https://github.com/$REPO/releases/latest/download/MusicPlayer.apk"

    data class Release(val name: String, val code: Long, val apkUrl: String, val size: Long, val notes: String = "")

    /** ダウンロードの進み具合(0〜100。していなければ null) */
    val progress = MutableStateFlow<Int?>(null)

    fun currentCode(c: Context): Long = runCatching {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    fun currentName(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull().orEmpty()

    /** 開発版(試しの版)も受け取るか。既定はオフ = 安定版だけ */
    fun wantsDev(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("update_dev", false)
    fun setDev(c: Context, on: Boolean) =
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("update_dev", on).apply()

    /** 版の番号で決まる: 末尾が .0(例 10.27.0)なら安定版、それ以外(10.27.1 など)は開発版 */
    fun isStableName(name: String) = Regex("^\\d+\\.\\d+\\.0$").matches(name)

    /** 今入っている版の種類(「安定版」/「開発版」) */
    fun channelLabel(c: Context): String = if (isStableName(currentName(c))) "安定版" else "開発版"

    /**
     * 最新リリース(無い・調べられない時は例外 / null)。
     * 普段は安定版(GitHub の latest。開発版=プレリリースは含まれない)。開発版も受け取る設定なら、開発版も含めていちばん新しいもの
     */
    fun latest(c: Context? = null): Release? {
        val dev = c != null && wantsDev(c)
        // まずは回数制限のない普通のページで確認(GitHub の API はログインなしだと 1 時間 60 回まで。携帯回線では他の人と共有になることも)
        runCatching { return if (dev) newestFromFeed() else stableFromWeb() }
            .onFailure { ErrorLog.add("アプリ更新", "ページで確認できなかったので API で確認します", it) }
        if (dev) {
            val arr = org.json.JSONArray(get("https://api.github.com/repos/$REPO/releases?per_page=10") ?: return null)
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> parse(o) } }.maxByOrNull { it.code }
        }
        return parse(JSONObject(get("https://api.github.com/repos/$REPO/releases/latest") ?: return null))
    }

    /** 安定版: …/releases/latest の転送先(…/releases/tag/v10.28.0-224)から */
    private fun stableFromWeb(): Release? {
        val conn = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
        val loc = try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8000; conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "MusicPlayer-app")
            if (conn.responseCode !in 300..399) error("HTTP ${conn.responseCode}")
            conn.getHeaderField("Location") ?: error("転送先なし")
        } finally { conn.disconnect() }
        val tag = loc.substringAfter("/releases/tag/", "").substringBefore("?").ifBlank { error("版が読めない: $loc") }
        val feed = runCatching { feedEntries() }.getOrDefault(emptyList())
        return fromTag(tag, feed.firstOrNull { it.first == tag }?.second.orEmpty(), dev = false)
    }

    /** 開発版も: リリースのフィード(releases.atom)でいちばん新しいもの */
    private fun newestFromFeed(): Release? {
        val best = feedEntries().mapNotNull { (tag, notes) -> codeOf(tag)?.let { Triple(tag, notes, it) } }
            .maxByOrNull { it.third } ?: return null
        return fromTag(best.first, best.second, dev = !isStableName(best.first.removePrefix("v").substringBeforeLast("-")))
    }

    /** (タグ, 変更点) の一覧 */
    private fun feedEntries(): List<Pair<String, String>> {
        val xml = getWeb("https://github.com/$REPO/releases.atom")
        return Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val e = m.groupValues[1]
            val tag = Regex("/releases/tag/([^\"<]+)").find(e)?.groupValues?.get(1) ?: return@mapNotNull null
            val html = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1).orEmpty()
            tag to htmlToText(html)
        }.toList()
    }

    private fun codeOf(tag: String) = Regex("-(\\d+)$").find(tag)?.groupValues?.get(1)?.toLongOrNull()

    private fun fromTag(tag: String, notes: String, dev: Boolean): Release? {
        val code = codeOf(tag) ?: return null
        val name = "MusicPlayer " + tag.substringBeforeLast("-") + if (dev) "（開発版）" else ""
        return Release(name, code, "https://github.com/$REPO/releases/download/$tag/MusicPlayer.apk", 0L,
            notes.lines().filterNot { it.startsWith("versionCode=") }.joinToString("\n").trim())
    }

    /** フィードの変更点(HTML を書き戻して文字に) */
    private fun htmlToText(h: String): String {
        val un = h.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        return un.replace(Regex("<br\\s*/?>|</p>|</li>"), "\n").replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun getWeb(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "MusicPlayer-app")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
    }

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "MusicPlayer-app")
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(o: JSONObject): Release? {
        val code = Regex("versionCode=(\\d+)").find(o.optString("body"))?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("-(\\d+)$").find(o.optString("tag_name"))?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        // 変更点(リリースの説明文。versionCode の行は除く)
        val notes = o.optString("body").lines().filterNot { it.startsWith("versionCode=") }.joinToString("\n").trim()
        val dev = o.optBoolean("prerelease")
        val name = o.optString("name").ifBlank { o.optString("tag_name") } + if (dev) "（開発版）" else ""
        return Release(name, code, apk.optString("browser_download_url"), apk.optLong("size"), notes)
    }

    /** ダウンロードしてインストール画面へ(裏で呼ぶ) */
    fun downloadAndInstall(context: Context, r: Release) {
        val app = context.applicationContext
        val f = File(app.cacheDir, "update.apk")
        progress.value = 0
        val t0 = System.currentTimeMillis()
        var mode = "4 本同時"
        try {
            var url = URL(r.apkUrl)
            var conn: HttpURLConnection
            // github.com → objects.githubusercontent.com へのリダイレクトをたどる
            var hops = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10000; conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "MusicPlayer-app")
                val code = conn.responseCode
                if (code in 300..399 && hops < 5) {
                    url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); hops++; continue
                }
                if (code !in 200..299) error("HTTP $code")
                break
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
            // 4 本に分けて同時に取る(1 本ずつより速い)。分けられない時は今まで通り 1 本で
            val ok = total > 4L * 1024 * 1024 && runCatching {
                conn.disconnect()
                parallelDownload(url, f, total, 4)
            }.getOrElse { android.util.Log.w("AppUpdater", "parallel download failed", it); false }
            if (!ok) {
                mode = "1 本(同時に取れなかったため最初から)"
                ErrorLog.add("アプリ更新", "同時ダウンロードに失敗。1 本で取り直します(${String.format("%.1f", (System.currentTimeMillis() - t0) / 1000.0)}秒経過)")
                val c2 = if (total > 4L * 1024 * 1024) (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 20000; setRequestProperty("User-Agent", "MusicPlayer-app")
                } else conn
                c2.inputStream.use { ins ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) progress.value = (done * 100 / total).toInt()
                        }
                    }
                }
                c2.disconnect()
            }
            val sec = (System.currentTimeMillis() - t0) / 1000.0
            val mb = f.length() / 1048576.0
            // 遅い時に原因を調べられるよう記録(10 秒以上かかった時だけ)
            if (sec >= 10) ErrorLog.add("アプリ更新", String.format("%.1f MB を %.1f 秒(%.1f MB/秒・%s)", mb, sec, mb / sec.coerceAtLeast(0.1), mode))
            install(app, f)
        } catch (e: Exception) {
            ErrorLog.add("アプリ更新", "ダウンロードに失敗", e)
            throw e
        } finally {
            progress.value = null
        }
    }

    /** 範囲を分けて同時にダウンロード。サーバーが範囲指定に対応していなければ false */
    private fun parallelDownload(url: URL, f: File, total: Long, parts: Int): Boolean {
        java.io.RandomAccessFile(f, "rw").use { it.setLength(total) }
        val done = java.util.concurrent.atomic.AtomicLong(0)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val size = (total + parts - 1) / parts
        val threads = (0 until parts).map { i ->
            val from = i * size
            val to = minOf(total, from + size) - 1
            Thread {
                try {
                    val c = url.openConnection() as HttpURLConnection
                    c.connectTimeout = 10000; c.readTimeout = 20000
                    c.setRequestProperty("User-Agent", "MusicPlayer-app")
                    c.setRequestProperty("Range", "bytes=$from-$to")
                    if (c.responseCode != 206) {
                        ErrorLog.add("アプリ更新", "分割ダウンロードに未対応(HTTP ${c.responseCode})")
                        failed.set(true); c.disconnect(); return@Thread
                    }
                    java.io.RandomAccessFile(f, "rw").use { raf ->
                        raf.seek(from)
                        c.inputStream.use { ins ->
                            val buf = ByteArray(256 * 1024)
                            while (!failed.get()) {
                                val n = ins.read(buf); if (n < 0) break
                                raf.write(buf, 0, n)
                                val d = done.addAndGet(n.toLong())
                                progress.value = (d * 100 / total).toInt()
                            }
                        }
                    }
                    c.disconnect()
                } catch (e: Exception) {
                    android.util.Log.w("AppUpdater", "part $i failed", e); failed.set(true)
                    ErrorLog.add("アプリ更新", "分割 ${i + 1} の取得に失敗", e)
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        return !failed.get() && done.get() == total
    }

    private fun install(c: Context, apk: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        // 2 回目以降(このアプリ自身で入れた後)は確認なしで更新できる(Android 12 以降)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("MusicPlayer.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = PendingIntent.getBroadcast(c, 77, Intent(c, InstallResultReceiver::class.java), flags)
            s.commit(intent.intentSender)
        }
    }

    /** インストールの結果(確認画面を出す・失敗を知らせる) */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // アプリを開いていれば確認画面を出す。裏(自動更新)では通知から
                    runCatching { context.startActivity(confirm) }
                    UpdateNotifier.notify(context, "👆 あとひと押しで完了です", "「インストール」を押していただければ、仕上がります",
                        PendingIntent.getActivity(context, 78, confirm,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    android.widget.Toast.makeText(context, "更新できませんでした $msg", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
