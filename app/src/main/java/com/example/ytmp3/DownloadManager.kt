package com.example.ytmp3

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * ダウンロードキュー。1 件ずつ順番に処理する。
 * アプリのプロセスが生きている間動き、DownloadService(前面サービス)がプロセスを維持する。
 */
object DownloadManager {
    private lateinit var app: Context
    private lateinit var saveLocation: SaveLocation
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val initMutex = Mutex()

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _engineStatus = MutableStateFlow("初期化中…")
    val engineStatus: StateFlow<String> = _engineStatus.asStateFlow()

    /** キューに処理中・待機中・取得中のものがあるか */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var ready = false
    private var updating = false
    private var worker: Job? = null
    private val pendingFetch = AtomicInteger(0)
    private val canceled = mutableSetOf<Long>()

    /** YouTube にブロックされたら別クライアントで再試行する(null = yt-dlp 既定) */
    private val PLAYER_CLIENTS = listOf(null, "android_vr", "tv", "web_safari", "mweb")

    fun init(context: Context) {
        app = context.applicationContext
        saveLocation = SaveLocation(app)
        scope.launch {
            // 準備はすぐ。更新の確認は少し後で(起動直後の再生と通信・処理を取り合わないように)
            if (ensureReady()) {
                kotlinx.coroutines.delay(90_000)
                lastEngineCheck = System.currentTimeMillis()
                updateEngineNow(manual = false)
                // 開きっぱなしでも、1 日に 1 回は新しい版が無いか見る(YouTube の変化に追いつくため)
                while (true) {
                    kotlinx.coroutines.delay(ENGINE_CHECK_EVERY_MS)
                    updateEngineIfStale(ENGINE_CHECK_EVERY_MS)
                }
            }
        }
    }

    // ───────── engine ─────────

    /** yt-dlp のキャッシュ(試聴用。暗号解読の結果などを使い回す) */
    fun cacheDir(): File? = if (::app.isInitialized) File(app.filesDir, "ytdlp-cache").apply { mkdirs() } else null

    /** 検索・試聴からも使う */
    suspend fun ready(): Boolean = ensureReady()

    private suspend fun ensureReady(): Boolean = initMutex.withLock {
        if (ready) return true
        try {
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            ready = true
            _engineStatus.value = "準備完了 · yt-dlp ${version()}"
        } catch (e: Exception) {
            _engineStatus.value = "初期化に失敗: ${e.message}"
            log("初期化に失敗: ${e.message}")
        }
        ready
    }

    private fun version() = runCatching { YoutubeDL.getInstance().versionName(app) }.getOrNull()
        ?.removePrefix("yt-dlp")?.trim() ?: "不明"

    fun updateEngine() {
        scope.launch { if (ensureReady()) updateEngineNow(manual = true) }
    }

    /** 設定の「プログラムを更新」: 終わるまで待って結果の文を返す */
    suspend fun updateEngineAndWait(): String {
        if (!ensureReady()) return "準備に失敗しました。少し待ってからもう一度お試しください"
        if (_jobs.value.any { it.status == JobStatus.RUNNING }) return "ダウンロード中は更新できません。終わってからお試しください"
        val before = version() ?: "?"
        updateEngineNow(manual = true)
        val after = version() ?: "?"
        return when {
            after != before -> "更新しました($before → $after)"
            _engineStatus.value.contains("失敗") -> "確認できませんでした($after)"
            else -> "最新です($after)"
        }
    }

    private const val ENGINE_CHECK_EVERY_MS = 24 * 3600_000L
    @Volatile private var lastEngineCheck = 0L

    /**
     * 前に確かめてから minGapMs 以上たっていれば、裏で yt-dlp の新しい版を確かめる(あれば更新)。
     * 速い取り方が YouTube に断られた時(YouTube 側が変わった合図)にも呼ぶ。ダウンロード中は見送る
     */
    fun updateEngineIfStale(minGapMs: Long = 6 * 3600_000L) {
        if (!::app.isInitialized || updating) return
        if (_jobs.value.any { it.status == JobStatus.RUNNING }) return
        val now = System.currentTimeMillis()
        if (now - lastEngineCheck < minGapMs) return
        lastEngineCheck = now
        scope.launch { if (ensureReady()) updateEngineNow(manual = false) }
    }

    /** 今の版(表示用) */
    fun engineVersion(): String = version() ?: "?"

    private fun updateEngineNow(manual: Boolean) {
        if (_jobs.value.any { it.status == JobStatus.RUNNING }) {
            if (manual) _engineStatus.value = "ダウンロード中は更新できません · yt-dlp ${version()}"
            return
        }
        if (updating) return
        updating = true
        _engineStatus.value = "yt-dlp の更新を確認中…"
        try {
            val st = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE)
            _engineStatus.value = when (st) {
                YoutubeDL.UpdateStatus.DONE -> {
                    InnerTube.reloadClients() // 新しい yt-dlp の名乗り方を使う
                    "yt-dlp を更新しました (${version()})"
                }
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "準備完了 · yt-dlp ${version()}(最新)"
                else -> "yt-dlp ${version()}"
            }
        } catch (e: Exception) {
            _engineStatus.value = "準備完了 · yt-dlp ${version()}(更新確認に失敗)"
            log("yt-dlp の更新に失敗: ${e.message}")
        } finally {
            updating = false
        }
    }

    // ───────── public API ─────────

    /** URL がプレイリスト/アルバムを指しているか */
    fun isListUrl(url: String): Boolean {
        val u = url.lowercase()
        if (u.contains("/browse/mpre")) return true
        if (u.contains("/playlist")) return true
        return u.contains("list=") && !u.contains("v=") && !u.contains("youtu.be/")
    }

    fun hasListParam(url: String) = url.contains("list=") || url.lowercase().contains("/browse/")

    /**
     * URL を追加する。
     * @param wholeList プレイリスト/アルバム全体を取得する
     * @param makePlaylist 取得した曲でプレイリストを作る
     */
    /** プレイリストごとの「曲 id → 元の位置」(読み込み・アルバム一括の並び順を保つ) */
    private val playlistPositions = HashMap<Long, HashMap<Long, Int>>()

    /** 既にある曲の位置を登録(プレイリスト読み込み時) */
    fun registerPositions(playlistId: Long, positions: Map<Long, Int>) = synchronized(lock) {
        playlistPositions.getOrPut(playlistId) { HashMap() }.putAll(positions)
    }

    /** プレイリスト読み込みで足りない曲をまとめてダウンロード */
    fun addForPlaylist(context: Context, playlistId: Long, items: List<Triple<String, String, Int>>) {
        if (items.isEmpty()) return
        recomputeBusy(force = true)
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        addJobs(items.map { (url, title, pos) ->
            DownloadJob(Repository.newId(), normalizeVideoUrl(url), title, playlistId = playlistId, playlistPos = pos)
        })
        startWorker()
        recomputeBusy()
    }

    fun add(context: Context, url0: String, wholeList: Boolean, makePlaylist: Boolean) {
        // 聴けない曲(🔒)で別の音源が見つかっていれば、そちらをダウンロード
        val url = if (wholeList) url0 else youtubeVideoId(url0)?.let { v ->
            AltSources.playableId(v)?.takeIf { it != v }?.let { "https://music.youtube.com/watch?v=$it" }
        } ?: url0
        if (wholeList) pendingFetch.incrementAndGet()
        recomputeBusy(force = true)
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        scope.launch {
            if (wholeList) {
                try {
                    enqueueList(url, makePlaylist)
                } finally {
                    pendingFetch.decrementAndGet()
                }
            } else {
                val u = normalizeVideoUrl(url)
                addJobs(listOf(DownloadJob(Repository.newId(), u, u)))
            }
            startWorker()
            recomputeBusy()
        }
    }

    // ───────── 動画の保存 ─────────

    /** ライブラリの曲に動画(MV)を付ける。YouTube の曲だけ。すでにある曲は飛ばす */
    fun addVideos(context: Context, tracks: List<Track>): Int {
        // 動画が無いと分かっている曲は飛ばす
        val jobs = tracks.filter { youtubeVideoId(it.sourceUrl) != null && !it.hasVideo && VideoCheck.known(youtubeVideoId(it.sourceUrl)) != false }
            .filter { t -> _jobs.value.none { it.video && it.videoFor == t.id && (it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING) } }
            .map { DownloadJob(Repository.newId(), normalizeVideoUrl(it.sourceUrl), "🎬 " + it.title, video = true, videoFor = it.id) }
        if (jobs.isEmpty()) return 0
        recomputeBusy(force = true)
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        scope.launch { addJobs(jobs); startWorker(); recomputeBusy() }
        return jobs.size
    }

    /** 検索などの動画を保存: ライブラリに無ければ曲(音声)も追加して、その曲に動画を付ける */
    fun addVideoForUrl(context: Context, url: String, title: String) {
        val vid = youtubeVideoId(url) ?: return
        val existing = VideoAlias.trackFor(vid, includeMissing = true)
        if (existing != null) { addVideos(context, listOf(existing)); return }
        recomputeBusy(force = true)
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        scope.launch {
            val u = normalizeVideoUrl(url)
            addJobs(listOf(
                DownloadJob(Repository.newId(), u, title.ifBlank { u }),
                DownloadJob(Repository.newId(), u, "🎬 " + title.ifBlank { u }, video = true),
            ))
            startWorker(); recomputeBusy()
        }
    }

    /** 保存した動画だけ消す(曲と音声は残す) */
    fun removeVideos(context: Context, tracks: List<Track>) {
        val ts = tracks.filter { it.hasVideo }
        scope.launch {
            val updated = ts.map { t ->
                // 音声も動画の音で聴いている曲(音声を消す設定)は、動画を消すと聴けなくなるので曲ごと ☁ に
                val sameFile = t.uri == t.videoUri
                runCatching { deleteUri(context, android.net.Uri.parse(t.videoUri)) }
                if (sameFile) t.copy(videoUri = "", videoBytes = 0, videoVid = "", sizeBytes = 0)
                else t.copy(videoUri = "", videoBytes = 0, videoVid = "")
            }
            Repository.updateTracks(updated)
            val gone = ts.filter { it.uri == it.videoUri }.map { it.id }.toSet()
            if (gone.isNotEmpty()) {
                Repository.markOnline(gone)
                Repository.setMissing(Repository.missing.value + gone)
            }
        }
    }

    private fun deleteUri(context: Context, uri: android.net.Uri) {
        if (android.provider.DocumentsContract.isDocumentUri(context, uri)) android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
        else context.contentResolver.delete(uri, null, null)
    }

    private suspend fun runVideoJob(job: DownloadJob, onDownloaded: () -> Unit) {
        val tmpDir = File(app.cacheDir, "dl/${job.id}")
        try {
            if (!ensureReady()) { update(job.id) { it.copy(status = JobStatus.FAILED, message = "初期化に失敗しました") }; return }
            log("動画の保存を開始: ${job.title}", job.id)
            val srcVid = youtubeVideoId(job.url)
            // どの曲に付けるか(検索から: 曲の保存が終わるまで少し待つ)
            var track = job.videoFor?.let { Repository.track(it) }
            var waited = 0
            // 同じ曲の音声のダウンロードが順番待ち・途中の間は待ち続ける(それ以外は最大 1 分)
            fun audioPending() = _jobs.value.any { !it.video && youtubeVideoId(it.url) == srcVid &&
                (it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING) }
            while (track == null && srcVid != null && (audioPending() || waited < 60)) {
                track = VideoAlias.trackFor(srcVid, includeMissing = true)
                if (track != null || isCanceled(job.id)) break
                if (waited == 0) update(job.id) { it.copy(message = "曲の保存を待っています…") }
                kotlinx.coroutines.delay(2000); waited += 2
            }
            if (isCanceled(job.id)) { update(job.id) { it.copy(status = JobStatus.CANCELED, message = "キャンセルしました") }; return }
            if (track == null || srcVid == null) { update(job.id) { it.copy(status = JobStatus.FAILED, message = "動画を付ける曲が見つかりません") }; return }
            // Wi-Fi の時だけ: モバイル回線なら待つ(ほかのダウンロードは先に進める)
            if (VideoPrefs.wifiOnly(app) && Net.metered) {
                onDownloaded()
                update(job.id) { it.copy(message = "Wi-Fi を待っています(設定で変えられます)") }
                while (Net.metered && VideoPrefs.wifiOnly(app) && !isCanceled(job.id)) kotlinx.coroutines.delay(10_000)
                if (isCanceled(job.id)) { update(job.id) { it.copy(status = JobStatus.CANCELED, message = "キャンセルしました") }; return }
            }
            // 曲版(ジャケットの静止画だけ)なら対になる MV を探す
            update(job.id) { it.copy(message = "動画を探しています…") }
            val (mv, sure) = runCatching { InnerTube.musicVideoLookup(srcVid) }.getOrDefault(srcVid to false)
            if (sure) VideoCheck.put(srcVid, mv)
            if (mv == null) {
                update(job.id) { it.copy(status = JobStatus.FAILED, message = "この曲には動画がありません(ジャケットの静止画だけ)") }
                return
            }
            val h = VideoPrefs.quality(app)
            var out: File? = null
            var lastError = "不明なエラー"
            for ((i, client) in PLAYER_CLIENTS.withIndex()) {
                if (isCanceled(job.id)) break
                tmpDir.deleteRecursively(); tmpDir.mkdirs()
                if (i > 0) update(job.id) { it.copy(message = "別の方法で再試行 (${i + 1}/${PLAYER_CLIENTS.size})") }
                try {
                    val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$mv").apply {
                        addOption("--no-playlist")
                        addOption("--no-cache-dir")
                        addOption("-f", "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/b[height<=$h][ext=mp4]/bv*[height<=$h]+ba/b[height<=$h]/b")
                        addOption("--merge-output-format", "mp4")
                        addOption("-o", "${tmpDir.absolutePath}/%(title).100B.%(ext)s")
                        if (client != null) addOption("--extractor-args", "youtube:player_client=$client")
                    }
                    var lastPct = -1
                    lowPriority { YoutubeDL.getInstance().execute(req, job.id.toString()) { progress, _, line ->
                        val pct = progress.toInt()
                        if (line.startsWith("[Merger]")) update(job.id) { it.copy(progress = 0, message = "映像と音声をまとめています…") }
                        else if (pct >= 0 && pct != lastPct && (lastPct < 0 || pct < lastPct || pct >= lastPct + 3 || pct == 100)) { lastPct = pct; update(job.id) { it.copy(progress = pct, message = "動画をダウンロード中 $pct%") } }
                        if (line.isNotBlank() && !line.startsWith("[download]")) log(line, job.id)
                    } }
                    out = tmpDir.listFiles { f -> f.extension.lowercase() in setOf("mp4", "mkv", "webm") }?.maxByOrNull { it.length() }
                    if (out != null) break
                    lastError = "動画ファイルが作られませんでした"
                } catch (e: YoutubeDL.CanceledException) {
                    break
                } catch (e: Exception) {
                    if (isCanceled(job.id)) break
                    lastError = e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: e.toString()
                    log("動画の失敗: $lastError", job.id)
                }
            }
            if (isCanceled(job.id)) { update(job.id) { it.copy(status = JobStatus.CANCELED, message = "キャンセルしました") }; return }
            val file = out ?: run { update(job.id) { it.copy(status = JobStatus.FAILED, message = lastError) }; return }
            onDownloaded()
            update(job.id) { it.copy(message = "保存中…", progress = 0) }
            // 曲と同じフォルダ分け(アーティスト / アルバム / 曲名.mp4)
            val t = Repository.track(track.id) ?: track
            val sub = buildList {
                add(FileNames.safe(t.artist, 100).ifEmpty { "不明なアーティスト" })
                if (t.album.isNotBlank()) add(FileNames.safe(t.album, 100))
            }
            val size = file.length()
            val saved = saveLocation.save(file, sub, safeName(t.title).ifEmpty { "video" } + "." + file.extension.lowercase())
            var updated = (Repository.track(t.id) ?: t).copy(videoUri = saved.uri.toString(), videoBytes = size, videoVid = mv)
            // 設定: 音声ファイルを消して、動画の音で聴く
            if (VideoPrefs.dropAudio(app) && !Repository.isMissing(t.id) && t.uri.isNotBlank() && t.uri != updated.videoUri) {
                if (runCatching { deleteUri(app, android.net.Uri.parse(t.uri)) }.isSuccess)
                    updated = updated.copy(uri = updated.videoUri, sizeBytes = size)
            }
            Repository.updateTracks(listOf(updated))
            update(job.id) { it.copy(status = JobStatus.DONE, progress = 100, title = "🎬 " + t.title, trackId = t.id,
                message = "動画を保存しました · ${SaveLocation.folderLabel(saved.folder.removeSuffix("/"))}") }
            log("動画を保存しました: ${t.title}", job.id)
        } catch (e: Exception) {
            update(job.id) { it.copy(status = JobStatus.FAILED, message = e.message ?: e.toString()) }
        } finally {
            tmpDir.deleteRecursively()
            synchronized(lock) { canceled -= job.id }
        }
    }

    /** 復元の取り直しの進み具合(終わった数, 全体)。取り直し中でなければ null */
    fun restoreProgress(jobs: List<DownloadJob> = _jobs.value): Pair<Int, Int>? {
        val r = jobs.filter { it.replaceTrackId != null }
        if (r.none { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }) return null
        return r.count { it.status != JobStatus.QUEUED && it.status != JobStatus.RUNNING } to r.size
    }

    /** 端末に無い曲を元の URL から取り直す(同じ曲として保存) */
    fun redownload(context: Context, tracks: List<Track>) {
        val jobs = tracks.filter { it.sourceUrl.isNotBlank() }
            .map { DownloadJob(Repository.newId(), normalizeVideoUrl(it.sourceUrl), it.title, replaceTrackId = it.id) }
        if (jobs.isEmpty()) return
        recomputeBusy(force = true)
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        scope.launch {
            addJobs(jobs)
            startWorker()
            recomputeBusy()
        }
    }

    /** この端末でファイルが開けない曲のうち、元の URL があるもの(取り直せる曲) */
    fun missingOnDevice(context: Context): List<Track> = Repository.tracks.value.filter { t ->
        t.sourceUrl.isNotBlank() && youtubeVideoId(t.sourceUrl) != null && !Repository.isIntentionalOnline(t.id) &&
            (Repository.isMissing(t.id) || !fileMatches(context, t))
    }

    /** 記録されている場所に、同じ名前のファイルがあるか(別の端末では同じ番号が別の曲のことがある) */
    private fun fileMatches(context: Context, t: Track): Boolean = runCatching {
        context.contentResolver.query(android.net.Uri.parse(t.uri), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> c.moveToFirst() && (t.fileName.isBlank() || c.getString(0) == t.fileName) } ?: false
    }.getOrDefault(false)

    fun cancel(id: Long) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        when (job.status) {
            JobStatus.QUEUED -> update(id) { it.copy(status = JobStatus.CANCELED, message = "キャンセルしました") }
            JobStatus.RUNNING -> {
                synchronized(lock) { canceled += id }
                runCatching { YoutubeDL.getInstance().destroyProcessById(id.toString()) }
            }
            else -> {}
        }
        recomputeBusy()
    }

    fun cancelAll() {
        _jobs.value.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }.forEach { cancel(it.id) }
    }

    fun retry(context: Context, id: Long) {
        update(id) { it.copy(status = JobStatus.QUEUED, progress = 0, message = "") }
        recomputeBusy(force = true)
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        startWorker()
    }

    fun remove(id: Long) = synchronized(lock) {
        _jobs.value = _jobs.value.filterNot { it.id == id && it.status != JobStatus.RUNNING }
    }

    fun clearFinished() = synchronized(lock) {
        _jobs.value = _jobs.value.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
    }

    // ───────── internals ─────────

    private fun addJobs(list: List<DownloadJob>) = synchronized(lock) {
        // 終わった分(完了・失敗)は新しい 300 件まで(ずっと使っていても一覧が溜まり続けないように)
        val all = _jobs.value + list
        val finished = all.filter { it.status == JobStatus.DONE || it.status == JobStatus.FAILED || it.status == JobStatus.CANCELED }
        _jobs.value = if (finished.size <= 300) all
            else finished.dropLast(300).map { it.id }.toSet().let { drop -> all.filterNot { it.id in drop } }
    }

    private fun update(id: Long, f: (DownloadJob) -> DownloadJob) = synchronized(lock) {
        _jobs.value = _jobs.value.map { if (it.id == id) f(it) else it }
    }

    private fun recomputeBusy(force: Boolean = false) {
        val active = _jobs.value.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
        _busy.value = force || active || pendingFetch.get() > 0 || worker != null || activeJobs.get() > 0
    }

    /**
     * ダウンロード(通信)は 1 件ずつ。変換・保存に入ったら枠を空けて次のダウンロードを始める。
     * 同時に処理する曲は最大 3 件(スマホの負荷を抑える)。
     */
    private val downloadSlot = kotlinx.coroutines.sync.Semaphore(1)
    private val jobSlot = kotlinx.coroutines.sync.Semaphore(3)
    private val activeJobs = AtomicInteger(0)

    private fun startWorker() = synchronized(lock) {
        if (worker != null) return
        worker = scope.launch {
            while (true) {
                jobSlot.acquire()
                downloadSlot.acquire()
                val next = synchronized(lock) {
                    val j = _jobs.value.firstOrNull { it.status == JobStatus.QUEUED }
                    if (j == null) worker = null
                    else _jobs.value = _jobs.value.map { if (it.id == j.id) it.copy(status = JobStatus.RUNNING, progress = 0, message = "準備中…") else it }
                    j
                }
                if (next == null) { downloadSlot.release(); jobSlot.release(); break }
                activeJobs.incrementAndGet()
                val released = java.util.concurrent.atomic.AtomicBoolean(false)
                val releaseSlot = { if (released.compareAndSet(false, true)) downloadSlot.release() }
                scope.launch {
                    try {
                        runJob(next, releaseSlot)
                    } finally {
                        releaseSlot()
                        jobSlot.release()
                        activeJobs.decrementAndGet()
                        recomputeBusy()
                    }
                }
            }
            recomputeBusy()
        }
    }

    private suspend fun enqueueList(url: String, makePlaylist: Boolean) {
        val placeholderId = Repository.newId()
        addJobs(listOf(DownloadJob(placeholderId, url, "プレイリスト情報を取得中…", JobStatus.RUNNING)))
        log("プレイリスト/アルバムを取得: $url")
        if (!ensureReady()) {
            update(placeholderId) { it.copy(status = JobStatus.FAILED, message = "初期化に失敗しました") }
            return
        }
        try {
            val req = YoutubeDLRequest(url).apply {
                addOption("--flat-playlist")
                addOption("-J")
                addOption("--no-warnings")
            }
            val out = YoutubeDL.getInstance().execute(req).out
            val json = JSONObject(out)
            val rawTitle = json.optString("title")
            val listTitle = rawTitle.removePrefix("Album - ").ifBlank { "プレイリスト" }
            // YouTube Music のアルバムなら、並び順をトラック番号として記録する
            val isAlbum = rawTitle.startsWith("Album - ") || url.contains("OLAK5uy_") ||
                url.lowercase().contains("/browse/mpre") || json.optString("id").startsWith("OLAK5uy_")
            val entries = json.optJSONArray("entries")
            val items = mutableListOf<Pair<String, String>>()
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    val vid = e.optString("id")
                    if (vid.length != 11) continue
                    items += "https://www.youtube.com/watch?v=$vid" to e.optString("title").ifBlank { vid }
                }
            }
            if (items.isEmpty()) {
                update(placeholderId) { it.copy(status = JobStatus.FAILED, title = listTitle, message = "曲が見つかりませんでした") }
                return
            }
            val playlistId = if (makePlaylist) Repository.createPlaylist(listTitle).id else null
            log("「$listTitle」${items.size} 曲をキューに追加")
            synchronized(lock) { _jobs.value = _jobs.value.filterNot { it.id == placeholderId } }
            addJobs(items.mapIndexed { i, (u, t) ->
                DownloadJob(Repository.newId(), u, t, playlistId = playlistId,
                    trackNo = if (isAlbum) i + 1 else 0, albumName = if (isAlbum) listTitle else "",
                    playlistPos = i)
            })
        } catch (e: Exception) {
            val msg = e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: e.toString()
            log("取得に失敗: $msg")
            update(placeholderId) { it.copy(status = JobStatus.FAILED, message = "取得に失敗: $msg") }
        }
    }

    private fun isAgeGate(msg: String) =
        msg.contains("confirm your age", true) || msg.contains("inappropriate", true) || msg.contains("age-restricted", true) || msg.contains("年齢")

    private suspend fun runJob(job: DownloadJob, onDownloaded: () -> Unit = {}) {
        update(job.id) { it.copy(status = JobStatus.RUNNING, progress = 0, message = "準備中…") }
        if (job.video) { runVideoJob(job, onDownloaded); return }
        log("ダウンロード開始: ${job.title}", job.id)
        if (!ensureReady()) {
            update(job.id) { it.copy(status = JobStatus.FAILED, message = "初期化に失敗しました") }
            return
        }
        val tmpDir = File(app.cacheDir, "dl/${job.id}")
        try {
            var mp3: File? = null
            var lastError = "不明なエラー"
            for ((i, client) in PLAYER_CLIENTS.withIndex()) {
                if (isCanceled(job.id)) break
                tmpDir.deleteRecursively()
                tmpDir.mkdirs()
                if (i > 0) {
                    log("── 別の方法で再試行 (${i + 1}/${PLAYER_CLIENTS.size}: $client)", job.id)
                    update(job.id) { it.copy(message = "別の方法で再試行 (${i + 1}/${PLAYER_CLIENTS.size})") }
                }
                try {
                    runYtDlp(job, tmpDir, client, onDownloaded)
                    mp3 = tmpDir.listFiles { f -> f.extension in AUDIO_EXTS }?.firstOrNull()
                    if (mp3 != null) break
                    lastError = "音声ファイルが生成されませんでした"
                } catch (e: YoutubeDL.CanceledException) {
                    break
                } catch (e: Exception) {
                    if (isCanceled(job.id)) break
                    lastError = e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: e.toString()
                    log("失敗: $lastError", job.id)
                }
            }
            if (isCanceled(job.id)) {
                update(job.id) { it.copy(status = JobStatus.CANCELED, message = "キャンセルしました") }
                return
            }
            val file = mp3
            if (file == null) {
                // 年齢確認が要る動画(MV など): 同じ曲の別の動画で取り直す(1 回だけ)
                val vid = youtubeVideoId(job.url)
                if (vid != null && isAgeGate(lastError) && !job.url.contains("&alt=1")) {
                    val alt = runCatching { InnerTube.alternativeFor(vid, job.title.takeUnless { it.startsWith("http") }) }.getOrNull()
                    if (alt != null) {
                        VideoAlias.put(vid, alt)
                        log("年齢確認が必要な動画のため、同じ曲の別の動画で取り直します: $alt", job.id)
                        val altUrl = "https://www.youtube.com/watch?v=$alt&alt=1"
                        update(job.id) { it.copy(url = altUrl, message = "同じ曲の別の動画で取り直し中…") }
                        tmpDir.deleteRecursively()
                        runJob(job.copy(url = altUrl), onDownloaded)
                        return
                    }
                }
                update(job.id) { it.copy(status = JobStatus.FAILED,
                    message = if (isAgeGate(lastError)) "年齢確認が必要な動画のためダウンロードできません(代わりの動画も見つかりませんでした)" else lastError) }
                return
            }
            onDownloaded()
            update(job.id) { it.copy(message = "保存中…", progress = 0) }
            val saved = saveAndRecord(file, job)
            // 取り直し: 元の曲(プレイリスト・編集内容・再生の記録)はそのままで、ファイルだけ付け替える
            val old = job.replaceTrackId?.let { Repository.track(it) }
            // 同じファイルがフォルダ読み込みで既に登録されていれば、その曲(id)を更新する
            val dup = if (old != null) null else Repository.tracks.value.firstOrNull { it.fileKey.isNotEmpty() && it.fileKey == saved.fileKey }
            val track = when {
                old != null -> old.copy(
                    uri = saved.uri, sizeBytes = saved.sizeBytes, durationMs = saved.durationMs.takeIf { it > 0 } ?: old.durationMs,
                    location = saved.location, folder = saved.folder, fileName = saved.fileName,
                    thumbPath = old.thumbPath?.takeIf { java.io.File(it).exists() } ?: saved.thumbPath,
                    trackNo = if (old.trackNo > 0) old.trackNo else saved.trackNo,
                    altUris = emptyList(), altKeys = emptyList(),
                ).also { Repository.updateTracks(listOf(it)); Repository.clearOnline(it.id); Repository.setMissing(Repository.missing.value - it.id) }
                dup != null -> saved.copy(id = dup.id).also { Repository.updateTracks(listOf(it)) }
                else -> saved.also { Repository.addTrack(it) }
            }
            // 既存の曲と重複していればまとめる(まとめ先の id を使う)
            Repository.mergeDuplicates()
            val finalId = if (Repository.track(track.id) != null) track.id
            else Repository.tracks.value.firstOrNull { t -> saved.fileKey.isNotEmpty() && saved.fileKey in t.allKeys }?.id ?: track.id
            job.playlistId?.let { pid ->
                Repository.addToPlaylist(pid, listOf(finalId))
                // 変換は並行して終わるので、元の順番に並べ直す
                if (job.playlistPos >= 0) {
                    val map = synchronized(lock) {
                        playlistPositions.getOrPut(pid) { HashMap() }.also { it[finalId] = job.playlistPos }.toMap()
                    }
                    Repository.reorderByPositions(pid, map)
                }
            }
            update(job.id) {
                it.copy(status = JobStatus.DONE, title = track.title, progress = 100,
                    message = "保存しました · ${track.location}", trackId = finalId)
            }
            log("保存しました: ${track.title}", job.id)
        } catch (e: Exception) {
            update(job.id) { it.copy(status = JobStatus.FAILED, message = e.message ?: e.toString()) }
            log("エラー: ${e.message}", job.id)
        } finally {
            tmpDir.deleteRecursively()
            synchronized(lock) { canceled -= job.id }
        }
    }

    private fun isCanceled(id: Long) = synchronized(lock) { id in canceled }

    private val AUDIO_EXTS = setOf("mp3", "m4a")

    private fun runYtDlp(job: DownloadJob, outDir: File, playerClient: String?, onDownloaded: () -> Unit) {
        val fast = saveLocation.fastMode
        val request = YoutubeDLRequest(job.url).apply {
            addOption("--no-playlist")
            addOption("--no-cache-dir")
            addOption("-x")
            if (fast) {
                // 高速モード: YouTube の AAC(m4a)をそのまま保存(再エンコードしない)
                addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                addOption("--audio-format", "m4a")
                addOption("--write-thumbnail") // ジャケットは埋め込まずアプリ内で使う
            } else {
                addOption("-f", "bestaudio/best")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
                addOption("--embed-thumbnail")
            }
            addOption("--convert-thumbnails", "jpg")
            addOption("--embed-metadata")
            addOption("-o", "${outDir.absolutePath}/%(title).100B.%(ext)s")
            if (playerClient != null) addOption("--extractor-args", "youtube:player_client=$playerClient")
            // アルバム一括時はトラック番号をファイルにも書き込む
            if (job.trackNo > 0 && playerClient == null) addOption("--parse-metadata", "%(id&${job.trackNo})s:%(track_number)s")
        }
        var lastPct = -1
        var stage = ""
        lowPriority { YoutubeDL.getInstance().execute(request, job.id.toString()) { progress, _, line ->
            val pct = progress.toInt()
            // 後処理の段階を表示(100% の後に止まって見えないように)
            val newStage = when {
                line.startsWith("[ExtractAudio]") -> if (line.contains("Not converting")) "音声を取り出し中…" else if (fast) "音声を取り出し中…" else "MP3 に変換中…"
                line.startsWith("[ThumbnailsConvertor]") || line.startsWith("[EmbedThumbnail]") -> if (fast) "ジャケットを準備中…" else "ジャケットを埋め込み中…"
                line.startsWith("[Metadata]") -> "曲情報を書き込み中…"
                line.startsWith("[FixupM4a]") -> "ファイルを整えています…"
                else -> null
            }
            if (newStage != null && newStage != stage) {
                if (stage.isEmpty()) {
                    log("ダウンロード完了 → 後処理: ${job.title}", job.id)
                    onDownloaded() // 次のダウンロードを始めてよい
                }
                stage = newStage
                update(job.id) { it.copy(progress = 0, message = newStage) }
            } else if (stage.isEmpty() && pct >= 0 && pct != lastPct && (lastPct < 0 || pct < lastPct || pct >= lastPct + 3 || pct == 100)) {
                // 進み具合は 3% ごと(画面の描き直しが多くなりすぎないように)
                lastPct = pct
                update(job.id) { it.copy(progress = pct, message = "ダウンロード中 $pct%") }
            }
            if (line.isNotBlank() && !line.startsWith("[download]")) log(line, job.id)
        } }
    }

    /** アルバム一括ダウンロード中のフォルダ名(アーティスト)を揃えるため */
    private val albumArtistFolder = HashMap<String, String>()

    private fun saveAndRecord(mp3: File, job: DownloadJob): Track {
        val id = Repository.newId()
        var title = mp3.nameWithoutExtension
        var artist = ""
        var albumArtist = ""
        var album = ""
        var duration = 0L
        var trackNo = 0
        var thumbPath: String? = null
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(mp3.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            artist = cleanArtist(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty())
            albumArtist = cleanArtist(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty())
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            trackNo = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            mmr.embeddedPicture?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                    val f = File(Repository.thumbDir, "$id.jpg")
                    FileOutputStream(f).use { squareThumb(bmp).compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    HiArt.save(app, id, bmp) // 再生画面用の大きい画像
                    thumbPath = f.absolutePath
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { mmr.release() }
        }
        // 高速モードではジャケットを埋め込まないので、書き出された jpg を使う
        if (thumbPath == null) {
            mp3.parentFile?.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "webp", "png") }?.firstOrNull()?.let { img ->
                BitmapFactory.decodeFile(img.absolutePath)?.let { bmp ->
                    val f = File(Repository.thumbDir, "$id.jpg")
                    FileOutputStream(f).use { squareThumb(bmp).compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    HiArt.save(app, id, bmp) // 再生画面用の大きい画像
                    thumbPath = f.absolutePath
                }
            }
        }
        if (trackNo <= 0) trackNo = job.trackNo
        if (album.isBlank()) album = job.albumName

        // フォルダ分け: アーティスト / アルバム / 01 曲名.mp3
        val folderArtist = if (job.albumName.isNotEmpty()) {
            val key = "${job.playlistId}:${job.albumName}"
            synchronized(lock) { albumArtistFolder.getOrPut(key) { albumArtist.ifBlank { artist } } }
        } else albumArtist.ifBlank { artist }
        val sub = buildList {
            add(FileNames.safe(folderArtist, 100).ifEmpty { "不明なアーティスト" })
            if (album.isNotBlank()) add(FileNames.safe(album, 100))
        }
        val fileName = (if (trackNo > 0) String.format("%02d ", trackNo) else "") + safeName(title).ifEmpty { "audio" } + "." + mp3.extension.lowercase()

        val size = mp3.length()
        val saved = saveLocation.save(mp3, sub, fileName)
        return Track(
            id = id, title = title, artist = artist, album = album, uri = saved.uri.toString(),
            savedAt = System.currentTimeMillis(), sizeBytes = size, durationMs = duration,
            location = SaveLocation.folderLabel(saved.folder.removeSuffix("/")), thumbPath = thumbPath,
            sourceUrl = job.url, folder = saved.folder, fileName = saved.fileName,
            source = Track.SOURCE_DOWNLOAD, trackNo = trackNo,
        )
    }

    /** "アーティスト名 - Topic" の " - Topic" を外す */
    private fun cleanArtist(s: String) = s.trim().removeSuffix(" - Topic").trim()

    private fun squareThumb(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(sq, 320, 320, true)
    }

    /** ファイル/フォルダ名に使えない文字を除く */
    fun safeName(s: String): String = FileNames.safe(s, 150)

    /** youtu.be/ID や si= 付き URL を https://www.youtube.com/watch?v=ID に正規化 */
    fun normalizeVideoUrl(raw: String): String {
        val id = Regex("""(?:youtu\.be/|[?&]v=|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})""")
            .find(raw)?.groupValues?.get(1)
        return if (id != null) "https://www.youtube.com/watch?v=$id" else raw
    }

    private fun log(msg: String, jobId: Long? = null) {
        synchronized(lock) {
            val l = _log.value
            _log.value = (if (l.size >= 400) l.drop(l.size - 399) else l) + msg
            if (jobId != null) {
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date())
                val jl = jobLogs.getOrPut(jobId) { ArrayList() }
                jl += "$time $msg"
                if (jl.size > 300) jl.subList(0, jl.size - 300).clear()
                // 一覧から消えたダウンロードの記録は捨てる
                if (jobLogs.size > 400) {
                    val ids = _jobs.value.map { it.id }.toSet()
                    jobLogs.keys.retainAll(ids)
                }
            }
        }
    }

    /** この曲のダウンロード(音声・動画)の進み具合の短い表示。していなければ null */
    fun progressFor(t: Track): String? {
        val vid = youtubeVideoId(t.sourceUrl)
        val j = _jobs.value.firstOrNull { j ->
            (j.status == JobStatus.RUNNING || j.status == JobStatus.QUEUED) &&
                (j.videoFor == t.id || j.replaceTrackId == t.id || (vid != null && youtubeVideoId(j.url) == vid))
        } ?: return null
        val kind = if (j.video) "🎬" else "⬇"
        return when {
            j.status == JobStatus.QUEUED -> "$kind 待機中"
            j.progress > 0 -> "$kind ${j.progress}%"
            else -> "$kind " + j.message.removeSuffix("…").take(14)
        }
    }

    /** 全体の進み具合(0〜1)。ダウンロード中でなければ null */
    fun overallProgress(): Float? {
        val active = _jobs.value.filter { it.status == JobStatus.RUNNING || it.status == JobStatus.QUEUED }
        if (active.isEmpty()) return null
        return active.sumOf { if (it.status == JobStatus.RUNNING) it.progress.coerceIn(0, 100) else 0 } / (active.size * 100f)
    }

    /** ダウンロードごとの記録(一覧で行をタップした時に表示) */
    private val jobLogs = HashMap<Long, MutableList<String>>()
    fun logOf(jobId: Long): List<String> = synchronized(lock) { jobLogs[jobId]?.toList().orEmpty() }

    /**
     * ダウンロード・変換(yt-dlp と ffmpeg)は優先度を下げて動かす。
     * 再生の音の処理を先に回して、ダウンロード中に音が途切れないように(子プロセスにも引き継がれる)
     */
    private inline fun <T> lowPriority(block: () -> T): T {
        val tid = android.os.Process.myTid()
        val before = runCatching { android.os.Process.getThreadPriority(tid) }.getOrDefault(android.os.Process.THREAD_PRIORITY_DEFAULT)
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try { return block() } finally { runCatching { android.os.Process.setThreadPriority(before) } }
    }
}
