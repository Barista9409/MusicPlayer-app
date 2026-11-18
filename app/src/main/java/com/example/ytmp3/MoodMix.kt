package com.example.ytmp3

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * あなたのミックスを「曲調」で 3 つに分ける。
 *
 * 種 = 最近聴いた違う曲 50 曲(アーティストごとに 5 曲まで。ミックス・ラジオで自動で流れて途中で飛ばした曲は数えない)
 *    ＋ お気に入り(♡)から 30 曲まで(多い人は毎日入れ替え)
 * 種の曲を MoodAnalyzer で数値にし(一度分析した曲は覚えておく)、近いものどうしで 3 つに分ける(k 平均法)。
 * にぎやかな順に 3 つ。名前は中身の目立つ特徴から(アップテンポ・パワフル・さわやか・しっとり・メロウ など)。
 * 分け直すのは 1 日 1 回・種が 10 曲以上入れ替わった時・↻ の時だけ(押すたびに名前と中身が入れ替わらないように)
 */
object MoodMix {
    data class Seed(val key: String, val vid: String?, val title: String, val artist: String, val track: Track?, val thumb: String?)
    data class Group(val name: String, val seeds: List<Seed>, val artists: List<String>)

    private lateinit var app: Context
    /** 分析は優先度の低い専用の 1 本で(再生の音の処理と取り合わないように) */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "mood-analyzer")
    }
    // 裏の作業で何か起きても、アプリ(と再生)を巻き込まない
    private val scope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher() +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e -> ErrorLog.add("曲調の分析", "途中で止まりました", e) })
    private val feats = java.util.concurrent.ConcurrentHashMap<String, MoodAnalyzer.Feat>()
    /** 分析できなかった曲 → 時刻(7 日は試し直さない) */
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var file: File? = null

    /** 分析中(ホームの ↻ をくるくるにする) */
    val busy = MutableStateFlow(false)
    /** 分析の進み具合(済んだ数, 全部)。分析していない時は (0, 0)。ホームの表示は 5 曲ごとに変わる */
    val progress = MutableStateFlow(0 to 0)
    /** 分け方が変わったら増える(ホームの描き直し用) */
    val changed = MutableStateFlow(0L)

    fun init(c: Context) {
        app = c.applicationContext
        file = File(c.filesDir, "mood_features.json")
        runCatching {
            val o = JSONObject(file!!.readText())
            o.optJSONObject("f")?.let { f -> f.keys().forEach { k -> f.getJSONArray(k).let { a ->
                feats[k] = MoodAnalyzer.Feat(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3)) } } }
            o.optJSONObject("x")?.let { x -> x.keys().forEach { k -> failed[k] = x.getLong(k) } }
        }
    }

    private fun save() = runCatching {
        val f = JSONObject(); feats.forEach { (k, v) -> f.put(k, JSONArray().put(v.loud).put(v.bright).put(v.onset).put(v.bpm)) }
        val x = JSONObject(); failed.forEach { (k, v) -> x.put(k, v) }
        file?.writeText(JSONObject().put("f", f).put("x", x).toString())
    }

    private fun keyOf(vid: String?, t: Track?): String? = vid?.let { "v:$it" } ?: t?.let { "t:${it.id}" }

    // ───────── 種 ─────────

    /** ↻ で変わる数(お気に入りから選ぶ曲の入れ替え) */
    @Volatile private var shuffle = 0L

    fun seeds(): List<Seed> {
        val out = LinkedHashMap<String, Seed>()
        // 最近聴いた違う曲 50 曲(新しい順。アーティストごとに 5 曲まで)
        val perArtist = HashMap<String, Int>()
        val events = PlayLog.playsSince(System.currentTimeMillis() - 90L * 86_400_000L).sortedByDescending { it.second }
        for ((id, _) in events) {
            if (out.size >= 50) break
            val t = Repository.track(id) ?: continue
            val vid = youtubeVideoId(t.sourceUrl)
            val key = keyOf(vid, t) ?: continue
            if (key in out) continue
            if (SkipLog.isPartial(SkipLog.keysOf(vid, t.title, t.artist))) continue
            if (NoGood.isSong(vid) || NoGood.isArtist(Radio.mainArtist(t.artist))) continue
            val a = Radio.mainArtist(t.artist).lowercase()
            if ((perArtist[a] ?: 0) >= 5) continue
            perArtist[a] = (perArtist[a] ?: 0) + 1
            out[key] = Seed(key, vid, t.title, t.artist, t, t.thumbPath)
        }
        // お気に入りから 30 曲まで(毎日・↻ で入れ替え)
        val day = System.currentTimeMillis() / 86_400_000L
        var liked = 0
        Liked.list.value.filter { it.videoId != null }.shuffled(java.util.Random(day * 7919 + shuffle)).forEach { r ->
            if (liked >= 30) return@forEach
            val t = VideoAlias.trackFor(r.videoId, includeMissing = true)
            val key = keyOf(r.videoId, t) ?: return@forEach
            if (key in out || NoGood.isSong(r.videoId) || NoGood.isArtist(Radio.mainArtist(r.artist))) return@forEach
            out[key] = Seed(key, r.videoId, r.title, r.artist, t, t?.thumbPath ?: r.thumb)
            liked++
        }
        return out.values.toList()
    }

    // ───────── 分析(裏で 1 曲ずつ) ─────────

    @Volatile private var running = false
    @Volatile private var lastKick = 0L

    /** まだ分析していない種を分析する(ダウンロード済みはファイル、☁ は Wi-Fi の時だけ最初の 1 分半を取ってくる) */
    fun kick(force: Boolean = false) {
        if (!::app.isInitialized || running) return
        // ホームの描き直しのたびに呼ばれるので、1 分に 1 回まで(描き直しと分析がくり返さないように)
        val now = System.currentTimeMillis()
        if (!force && now - lastKick < 60_000) return
        lastKick = now
        running = true
        scope.launch {
            try {
                // 今の状況で分析できる曲だけ(できない曲を毎回試して、ホームの描き直しがくり返されないように)
                // 再生の記録は再生側が同時に書き足すことがあるので、読めなかった時は今回は見送る
                val todo = runCatching { seeds() }.getOrElse { return@launch }.filter { !feats.containsKey(it.key) && (failed[it.key] ?: 0L) < System.currentTimeMillis() - 7L * 86_400_000L && canTryNow(it) }
                    // 通信なしで分析できる曲(端末のファイル・一時保存)を先に、Wi-Fi で取ってくる曲を後に
                    .sortedBy { if (canTryOffline(it)) 0 else 1 }
                if (todo.isEmpty()) return@launch
                busy.value = true
                progress.value = 0 to todo.size
                var done = 0
                var processed = 0
                for (s in todo) {
                    processed++
                    if (processed % 5 == 0) progress.value = processed to todo.size
                    val (f, skipped) = runCatching { analyze(s) }.getOrElse { null to false }
                    // 見送り(モバイル回線で ☁ の曲など)は失敗にしない。次に条件がそろった時に分析する
                    if (f != null) feats[s.key] = f else if (!skipped) failed[s.key] = System.currentTimeMillis() else continue
                    if (++done % 5 == 0) { save(); changed.value = System.currentTimeMillis() }
                }
                save()
                if (done > 0) changed.value = System.currentTimeMillis()
            } finally {
                running = false
                busy.value = false
                progress.value = 0 to 0
            }
        }
    }

    /** 今すぐ分析できそうか(端末にファイルがある・一時保存がある・Wi-Fi で取ってこられる) */
    private fun canTryNow(s: Seed): Boolean =
        canTryOffline(s) || (s.vid != null && Net.streamAllowed(app) && !Net.metered && !PlaybackService.active)

    /** 通信なしで分析できるか(端末にファイルがある・一時保存がある) */
    private fun canTryOffline(s: Seed): Boolean {
        val t = s.track
        if (t != null && t.uri.isNotBlank() && !Repository.isMissing(t.id)) return true
        val vid = s.vid ?: return false
        return StreamCache.headBytes(app, vid) >= 400_000
    }

    /**
     * 曲を最後まで(8 割以上)聴いた時(再生サービスから): その曲を、通信なしで(端末のファイルか一時保存から)分析しておく。
     * 使うほど分析済みが増えて、曲調の分け方が正確になる
     */
    fun learn(item: androidx.media3.common.MediaItem) {
        if (!::app.isInitialized) return
        val t = item.mediaId.toLongOrNull()?.let { Repository.track(it) }
        val vid = StreamDownload.videoId(item) ?: t?.sourceUrl?.let { youtubeVideoId(it) }
        val key = keyOf(vid, t) ?: return
        if (feats.containsKey(key) || failed.containsKey(key)) return
        val title = t?.title ?: item.mediaMetadata.title?.toString().orEmpty()
        val artist = t?.artist ?: item.mediaMetadata.artist?.toString().orEmpty()
        val seed = Seed(key, vid, title, artist, t, t?.thumbPath)
        if (!canTryOffline(seed)) return
        scope.launch {
            // 通信はしない(一時保存・ファイルだけ)。見送りなら何もしない
            val (f, _) = if (canTryOffline(seed)) analyzeOffline(seed) else return@launch
            if (f != null) { feats[key] = f; save(); changed.value = System.currentTimeMillis() }
        }
    }

    /** 通信なしの分析(端末のファイル・一時保存だけ) */
    private fun analyzeOffline(s: Seed): Pair<MoodAnalyzer.Feat?, Boolean> {
        val t = s.track
        if (t != null && t.uri.isNotBlank() && !Repository.isMissing(t.id))
            MoodAnalyzer.analyze(app, android.net.Uri.parse(t.uri))?.let { return it to false }
        val vid = s.vid ?: return null to true
        val cached = File(app.cacheDir, "mood_l_$vid.part")
        return try {
            val n = StreamCache.copyHead(app, vid, 1_600_000, cached)
            if (n < 400_000) null to true
            else MoodAnalyzer.analyzePartial(cached.absolutePath, startSec = if (n >= 1_200_000) 30 else 0) to false
        } finally { cached.delete() }
    }

    /** (結果, 見送ったか)。見送り = 今は条件がそろわない(モバイル回線・ネット不可) */
    private suspend fun analyze(s: Seed): Pair<MoodAnalyzer.Feat?, Boolean> {
        val t = s.track
        // 端末のファイル・一時保存で一度試したか(試して失敗したなら、見送りではなく失敗として記録する)
        var tried = false
        // ダウンロード済み(端末にファイルがある)
        if (t != null && t.uri.isNotBlank() && !Repository.isMissing(t.id)) {
            tried = true
            MoodAnalyzer.analyze(app, android.net.Uri.parse(t.uri))?.let { return it to false }
        }
        val vid = s.vid ?: return null to false
        // ☁ で、聴いた時の一時保存が残っていればそれを使う(通信しない)。1.2MB 以上あれば頭の 30 秒は飛ばす
        val cached = File(app.cacheDir, "mood_c_$vid.part")
        try {
            val n = StreamCache.copyHead(app, vid, 1_600_000, cached)
            if (n >= 400_000) tried = true
            if (n >= 400_000) MoodAnalyzer.analyzePartial(cached.absolutePath, startSec = if (n >= 1_200_000) 30 else 0)?.let { return it to false }
        } finally { cached.delete() }
        // ☁: Wi-Fi の時だけ、最初の 1 分半ほどを取ってきて後半を分析
        // 再生中は通信しない(再生中の曲の読み込みと取り合わないように)。見送りにして、再生していない時に
        if (!Net.streamAllowed(app) || Net.metered || PlaybackService.active) return null to !tried
        if (Searcher.isUnplayable(vid)) return null to false
        val url = runCatching { Searcher.streamUrlQuiet(vid) }.getOrNull() ?: return null to false
        val tmp = File(app.cacheDir, "mood_$vid.part")
        return try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            InnerTube.userAgentForStream(url)?.let { c.setRequestProperty("User-Agent", it) }
            c.setRequestProperty("Range", "bytes=0-1599999")
            try {
                if (c.responseCode !in 200..299) return null to false
                // サーバーが範囲の指定を無視しても、1.6MB で読むのを止める(通信を使いすぎないように)
                c.inputStream.use { ins -> tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024); var total = 0
                    while (total < 1_600_000) {
                        val n = ins.read(buf, 0, minOf(buf.size, 1_600_000 - total)); if (n < 0) break
                        out.write(buf, 0, n); total += n
                    }
                } }
            } finally { c.disconnect() }
            MoodAnalyzer.analyzePartial(tmp.absolutePath, startSec = 30) to false
        } catch (e: Exception) { null to false } finally { tmp.delete() }
    }

    // ───────── 3 つに分ける ─────────

    private var cache: List<Group>? = null
    private var cacheDay = -1L
    private var cacheKeys: Set<String> = emptySet()
    /** 前に分けた時に分析済みだった種の数 */
    private var cacheAnalyzed = 0

    /** ↻: 種を集め直し(お気に入りも入れ替え)、分け直す */
    fun refresh() {
        shuffle = System.currentTimeMillis()
        synchronized(this) { cache = null; cacheDay = -1L }
        changed.value = System.currentTimeMillis()
        kick(force = true)
    }

    /** 曲調で分けた 3 つ。分析が足りない・偏りすぎて分けられない時は null(呼ぶ側で今までの分け方に) */
    fun groups(): List<Group>? {
        if (!::app.isInitialized) return null
        val seeds = seeds()
        val day = System.currentTimeMillis() / 86_400_000L
        val keys = seeds.map { it.key }.toSet()
        val analyzed = seeds.count { feats.containsKey(it.key) }
        synchronized(this) {
            val diff = (keys - cacheKeys).size + (cacheKeys - keys).size
            // 分け直すのは: 日が変わった・種が 10 曲以上入れ替わった・分析が 10 曲以上進んだ・↻ の時
            val step = if (analyzed < 30) 3 else 10
            if (cacheDay == day && diff < 10 && analyzed - cacheAnalyzed < step && cacheKeys.isNotEmpty()) return cache
        }
        val result = compute(seeds)
        synchronized(this) {
            // 分析が足りない間は覚えない(分析が進んだらすぐ分けられるように)
            if (result != null) { cache = result; cacheDay = day; cacheKeys = keys; cacheAnalyzed = analyzed }
        }
        return result ?: synchronized(this) { cache }
    }

    private fun compute(seeds: List<Seed>): List<Group>? {
        val have = seeds.filter { feats.containsKey(it.key) }
        if (have.size < MIN_SONGS) return null
        val raw = have.map { s -> feats[s.key]!!.let { doubleArrayOf(it.loud, it.bright, it.onset, it.bpm) } }
        val split = splitByMood(raw) ?: return null
        // members[c] = c 番目のまとまり(0 = にぎやか, 1 = まんなか, 2 = おだやか)の種の番号
        val members = split
        // 名前は中身のいちばん目立つ特徴から(パワフル・さわやか・メロウ など)
        val names = namesFor(raw, members)
        val ranked = listOf(0, 1, 2)
        // アーティストは、いちばん多く入っているまとまりだけに(同じ新曲が 2 つのミックスに出ないように)
        val count = HashMap<String, IntArray>()
        members.forEachIndexed { c, m -> m.forEach { i -> val a = Radio.mainArtist(have[i].artist); count.getOrPut(a) { IntArray(3) }[c]++ } }
        val home = count.mapValues { (_, v) -> v.indices.maxByOrNull { v[it] }!! }
        // 分析がまだの種は、同じアーティストが多いまとまりに仮に入れる(分析されたら本当の曲調に置き換わる)
        val pending = seeds.filter { !feats.containsKey(it.key) }.groupBy { c ->
            home[Radio.mainArtist(c.artist)] ?: -1
        }
        return ranked.mapIndexed { rank, c ->
            val s = members[c].map { have[it] } + pending[c].orEmpty()
            val artists = s.map { Radio.mainArtist(it.artist) }.filter { home[it] == c }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            Group(names[rank], s, artists)
        }
    }

    /**
     * 曲調の数値(強さ・明るさ・立ち上がり・テンポ)を 3 つに分ける。にぎやかな順(アップテンポ・ミドル・しっとり)に、
     * それぞれに入る曲の番号を返す。1 つでも 8 曲未満になるほど偏っていれば null
     */
    internal fun splitByMood(raw: List<DoubleArray>): List<List<Int>>? {
        if (raw.size < MIN_SONGS) return null
        // 数値をそろえる(平均 0・ばらつき 1)。テンポは間違えやすいので重みを軽く
        val weight = doubleArrayOf(1.0, 1.0, 1.0, 0.4)
        val dims = 4
        val mean = DoubleArray(dims) { d -> raw.map { it[d] }.average() }
        val sd = DoubleArray(dims) { d -> kotlin.math.sqrt(raw.map { (it[d] - mean[d]).let { x -> x * x } }.average()).coerceAtLeast(1e-6) }
        val x = raw.map { r -> DoubleArray(dims) { d -> (r[d] - mean[d]) / sd[d] * weight[d] } }
        // にぎやかさ(大きいほどアップテンポ)
        fun energy(v: DoubleArray) = v.sum()
        // k 平均法(k = 3)。最初の中心は、にぎやかさ順で 1/6・1/2・5/6 の所の曲
        val order = x.indices.sortedBy { energy(x[it]) }
        var centers = listOf(order[order.size / 6], order[order.size / 2], order[order.size * 5 / 6]).map { x[it].copyOf() }
        var assign = IntArray(x.size) { -1 }
        for (round in 0 until 30) {
            val next = IntArray(x.size) { i -> centers.indices.minByOrNull { c -> dist(x[i], centers[c]) }!! }
            centers = centers.indices.map { c ->
                val m = x.indices.filter { next[it] == c }
                if (m.isEmpty()) centers[c] else DoubleArray(dims) { d -> m.map { x[it][d] }.average() }
            }
            val same = next.contentEquals(assign)
            assign = next
            if (same) break
        }
        val members = centers.indices.map { c -> x.indices.filter { assign[it] == c } }
        // 偏りすぎて、1 つが少なすぎる(分析済みが少ないうちは 3 曲、多くなったら 8 曲)なら、曲調では分けない
        if (members.any { it.size < minGroup(x.size) }) return null
        // にぎやかな順に並べる
        return centers.indices.sortedByDescending { energy(centers[it]) }.map { members[it] }
    }

    /**
     * それぞれのまとまりの名前。にぎやか・まんなか・おだやかの位置ごとに、いちばん目立つ特徴で選ぶ。
     * 目立つ特徴がなければ、その位置の基本の名前(アップテンポ・ミドルテンポ・しっとり)。位置ごとに候補が違うので、名前は重ならない
     *  - にぎやか: テンポ → アップテンポ / 強さ → パワフル / 明るさ → キラキラポップ / 立ち上がり → ノリノリ
     *  - まんなか: 立ち上がり → リズミカル / 明るさ → さわやか / ほか → ミドルテンポ
     *  - おだやか: 強さが低い → しっとり / 明るさが低い → メロウ / テンポが遅い → ゆったり / 実際にとても静か → おやすみ前
     */
    internal fun namesFor(raw: List<DoubleArray>, members: List<List<Int>>): List<String> {
        val dims = 4 // 0 強さ, 1 明るさ, 2 立ち上がり, 3 テンポ
        val mean = DoubleArray(dims) { d -> raw.map { it[d] }.average() }
        val sd = DoubleArray(dims) { d -> kotlin.math.sqrt(raw.map { (it[d] - mean[d]).let { x -> x * x } }.average()).coerceAtLeast(1e-6) }
        // それぞれのまとまりの平均(全体の平均からのずれ。ばらつきを 1 として)
        val z = members.map { m ->
            DoubleArray(dims) { d -> if (m.isEmpty()) 0.0 else m.map { (raw[it][d] - mean[d]) / sd[d] }.average() }
        }
        // テンポは倍や半分に間違えやすいので、控えめに見る
        val trust = doubleArrayOf(1.0, 1.0, 1.0, 0.6)
        fun rel(c: Int, d: Int): Double {
            val others = z.indices.filter { it != c }
            return (z[c][d] - others.map { z[it][d] }.average()) * trust[d]
        }
        // いちばん目立つ特徴が、はっきり目立つ(2 番目より 0.3 以上)時だけ、その名前。全部そろって高い・低い時は基本の名前
        fun pick(c: Int, options: Map<Int, String>, sign: Double, threshold: Double, base: String): String {
            val sorted = (0 until dims).sortedByDescending { d -> rel(c, d) * sign }
            val best = sorted[0]
            val clear = rel(c, best) * sign >= threshold && rel(c, best) * sign - rel(c, sorted[1]) * sign >= 0.3
            return if (clear && best in options) options.getValue(best) else base
        }
        val lively = pick(0, mapOf(3 to "アップテンポ", 0 to "パワフル", 1 to "キラキラポップ", 2 to "ノリノリ"), 1.0, 0.5, "アップテンポ")
        val middle = run {
            // まんなかは、全体の平均と比べて目立つものがあれば
            val opts = mapOf(2 to "リズミカル", 1 to "さわやか")
            val sorted = (0 until dims).sortedByDescending { z[1][it] * trust[it] }
            val best = sorted[0]
            val clear = z[1][best] * trust[best] >= 0.3 && z[1][best] * trust[best] - z[1][sorted[1]] * trust[sorted[1]] >= 0.3
            if (clear && best in opts) opts.getValue(best) else "ミドルテンポ"
        }
        // 「おやすみ前」は、比べ方ではなく実際にとても静かな時だけ(平均の強さ -24dB 以下・立ち上がり 1 秒に 1.2 以下)
        val quiet = members[2].takeIf { it.isNotEmpty() }?.let { m -> m.map { raw[it][0] }.average() <= -24.0 && m.map { raw[it][2] }.average() <= 1.2 } == true
        val calm = if (quiet) "おやすみ前"
            else pick(2, mapOf(0 to "しっとり", 1 to "メロウ", 3 to "ゆったり"), -1.0, 0.5, "しっとり")
        return listOf(lively, middle, calm)
    }

    /** 曲調で分け始める分析済みの曲数(1 つのまとまりに 3 曲) */
    internal const val MIN_SONGS = 9

    /** 1 つのまとまりに最低いる曲数(分析済みが少ないうちは 3 曲、48 曲以上で 8 曲) */
    internal fun minGroup(n: Int) = (n / 6).coerceIn(3, 8)

    private fun dist(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { (a[it] - b[it]).let { d -> d * d } }
}
