package com.example.ytmp3

import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * あなたのミックス(YouTube の「マイミックス」風)。
 * よく聴くアーティストを 3 つのまとまりに分け、まとまりごとに
 *   ① そのアーティストの新曲(最近のシングル・アルバム)
 *   ② よく聴く曲(ライブラリ)
 *   ③ 似た曲(よく聴く曲のラジオ)
 * を集めて点数を付け、新曲が頭のほうに来るように並べる。押すたびに作り直す(毎回少しずつ変わる)。
 * 残りが少なくなったら、同じ決まりで続きを足す(終わらない)
 */
object DailyMix {
    /** covers = カードのジャケット用に、アーティストごとの代表曲(並べて 1 枚の絵にする) */
    data class Mix(val index: Int, val artists: List<String>, val art: String?, val covers: List<Track> = emptyList(),
                   /** カードの名前(例: 「アップテンポ」「Ado」。見出しが「あなたのミックス」なので「ミックス」は付けない) */
                   val title: String = "ミックス $index",
                   /** 曲調で分けた時の種(このまとまりの曲)。空なら今までのアーティストで作る */
                   val seeds: List<MoodMix.Seed> = emptyList(),
                   /** ジャンルで分けた時のジャンル(似た曲をそのジャンルに絞る) */
                   val genre: String? = null)

    /** 1 回に流す曲数と、続きを足す時の曲数 */
    const val FIRST = 50
    const val MORE = 25

    private var prefs: android.content.SharedPreferences? = null
    /** ↻ で変わる数(0 = いつもの組み合わせ) */
    @Volatile private var shuffleSeed = 0L

    fun init(c: android.content.Context) {
        prefs = c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        shuffleSeed = prefs?.getLong("mix_seed", 0L) ?: 0L
        loadReady()
        (1..3).forEach { i -> prefs?.getString("mix_art_sig_$i", null)?.let { artSig[i] = it } }
        lastArt.value = (1..3).mapNotNull { i ->
            prefs?.getString("mix_art_$i", null)?.split("\n")?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { i to it }
        }.toMap()
    }

    /** ホームの ↻: アーティストの組み合わせを選び直し、新曲も取り直す */
    fun refresh() {
        forgetArt()
        MoodMix.refresh()
        shuffleSeed = System.currentTimeMillis()
        prefs?.edit()?.putLong("mix_seed", shuffleSeed)?.apply()
        // 新曲の一覧は捨てずに、裏で取り直す(次にミックスを押した時に待たせないように)
        prewarm(force = true)
    }

    private val warmScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    @Volatile private var warming = false

    /** ホームを開いた時など: 3 つのミックスの新曲を裏で先に探しておく(押した時にすぐ作れるように。6 時間は覚えておく) */
    fun prewarm(force: Boolean = false) {
        if (warming || !Net.streamAllowed()) return
        warming = true
        warmScope.launch {
            try { runCatching { mixes() }.getOrDefault(emptyList()).forEach { m -> runCatching { newSongs(m.artists, force, MixPrefs.moreNew()) } } }
            finally { warming = false }
        }
    }

    /** 最大 3 つ */
    fun mixes(): List<Mix> {
        val tracks = Repository.tracks.value.filterNot { Repository.cantPlay(it.id) }
        if (tracks.isEmpty()) return emptyList()
        val ids = tracks.associateBy { it.id }
        val score = HashMap<String, Int>()
        PlayLog.counts(30).forEach { (id, n) ->
            ids[id]?.takeIf { it.artist.isNotBlank() }?.let { val a = Radio.mainArtist(it.artist); score[a] = (score[a] ?: 0) + n }
        }
        val byCount = tracks.filter { it.artist.isNotBlank() }.groupingBy { Radio.mainArtist(it.artist) }.eachCount()
        val ranked = (score.entries.sortedByDescending { it.value }.map { it.key } +
            byCount.entries.sortedByDescending { it.value }.map { it.key }).distinct()
            .filterNot { NoGood.isArtist(it) }
        if (ranked.isEmpty()) return emptyList()
        // 曲調で 3 つに分けられれば、それを使う(分析が足りない間は下の分け方)
        if (MixPrefs.byMood()) MoodMix.groups()?.let { gs -> return gs.mapIndexed { i, g -> moodMix(i + 1, g, tracks) } }
        // ジャンルで分ける(よく聴くジャンルの上位 3 つ)。分けられなければ下の分け方
        if (MixPrefs.byGenre()) genreMixes(ranked, tracks)?.let { return it }
        // 聴き方の傾向で 3 つに分ける(記録が少なければ、よく聴く順に振り分ける今までのやり方)
        val clusters = MixClusters.build(ranked, tracks)
        val rnd = java.util.Random(shuffleSeed)
        val groups: List<List<String>>
        val titles: List<String?>
        if (clusters != null) {
            // まとまりごとに、よく聴く人から 5 人。↻ の後は、上位 3 人＋残りから 2 人を選び直す
            groups = clusters.map { c ->
                if (shuffleSeed == 0L) c.artists.take(5) else c.artists.take(3) + c.artists.drop(3).shuffled(rnd).take(2)
            }
            titles = clusters.map { it.title }
        } else {
            val artists = if (shuffleSeed == 0L) ranked.take(9) else
                (ranked.take(6) + ranked.drop(6).take(20).shuffled(rnd).take(3)).shuffled(rnd)
            groups = (0 until minOf(3, artists.size)).map { i -> artists.filterIndexed { j, _ -> j % 3 == i } }
            titles = groups.map { null }
        }
        return groups.mapIndexed { i, g -> artistMix(i + 1, g, titles.getOrNull(i) ?: "ミックス ${i + 1}", tracks) }
    }

    /**
     * ジャンルで分ける: よく聴くアーティスト(上位 40 人)のジャンルを調べ、聴いている量の多いジャンルから 3 つ。
     * それぞれ、そのジャンルのよく聴く人を中心に(似た曲もそのジャンルに絞る)。2 人以上いるジャンルだけ
     */
    private fun genreMixes(ranked: List<String>, tracks: List<Track>): List<Mix>? {
        val top = ranked.take(40)
        ArtistGenres.lookup(top) // まだ調べていない人を裏で(調べ終わったら分け直す)
        val byArtist = top.associateWith { a ->
            ArtistGenres.of(a, tracks.asSequence().filter { Radio.mainArtist(it.artist) == a }.take(5).map { it.title }.toList())
        }
        // 上の順位ほど重く
        val weight = HashMap<String, Int>()
        top.forEachIndexed { i, a -> val g = byArtist.getValue(a); weight[g] = (weight[g] ?: 0) + (top.size - i) }
        val genres = weight.entries.sortedByDescending { it.value }.map { it.key }
            .filter { g -> top.count { byArtist[it] == g } >= 2 }.take(3)
        if (genres.isEmpty()) return null
        val rnd = java.util.Random(shuffleSeed)
        return genres.mapIndexed { i, g ->
            val people = top.filter { byArtist[it] == g }
            val pick = if (shuffleSeed == 0L) people.take(5) else people.take(3) + people.drop(3).shuffled(rnd).take(2)
            artistMix(i + 1, pick, g, tracks).copy(genre = g)
        }
    }

    /** アーティストのまとまりから、ミックスのカードを作る */
    private fun artistMix(index: Int, g: List<String>, title: String, tracks: List<Track>): Mix {
        val i = index - 1
        val art = Radio.seedTrack(g.first())?.let { HiArt.cachedPath(it) ?: it.thumbPath }
            ?: tracks.firstOrNull { Radio.mainArtist(it.artist) == g.first() }?.thumbPath
        // ジャケット用: アーティストを順番に回って、ジャケットのある曲をアルバムが重ならないように拾う
        // (並べる時に見た目の同じ絵は 1 枚にするので、4 枚そろうよう多めに 8 曲まで)
        val perArtist = g.map { a ->
            (listOfNotNull(Radio.seedTrack(a)) + tracks.filter { Radio.mainArtist(it.artist) == a })
                .filter { it.thumbPath != null }.distinctBy { it.album.ifBlank { it.thumbPath!! } }
        }
        val covers = mutableListOf<Track>()
        var round = 0
        while (covers.size < 8 && perArtist.any { it.size > round }) {
            perArtist.forEach { l -> l.getOrNull(round)?.let { t -> if (covers.none { it.thumbPath == t.thumbPath }) covers += t } }
            round++
        }
        return Mix(i + 1, g, art, covers, title)
    }

    /** 曲調のまとまりから、ミックスのカードを作る */
    private fun moodMix(index: Int, g: MoodMix.Group, tracks: List<Track>): Mix {
        val artists = g.artists.ifEmpty { g.seeds.map { Radio.mainArtist(it.artist) }.distinct() }.take(5)
        // ジャケット用: まとまりの曲から、アーティストを順に回って(4 分割で並べる)
        val withArt = g.seeds.mapNotNull { s -> (s.track ?: s.vid?.let { VideoAlias.trackFor(it, includeMissing = true) })?.takeIf { it.thumbPath != null } }
        val covers = withArt.groupBy { Radio.mainArtist(it.artist) }.values.let { lists ->
            val out = mutableListOf<Track>(); var r = 0
            while (out.size < 8 && lists.any { it.size > r }) { lists.forEach { l -> l.getOrNull(r)?.let { if (out.none { o -> o.thumbPath == it.thumbPath }) out += it } }; r++ }
            out
        }
        val art = covers.firstOrNull()?.thumbPath ?: tracks.firstOrNull { Radio.mainArtist(it.artist) == artists.firstOrNull() }?.thumbPath
        return Mix(index, artists, art, covers, g.name, g.seeds)
    }

    fun label(m: Mix) = m.artists.take(3).joinToString("・") + if (m.artists.size > 1) " など" else ""

    /** 再生中のキューが「あなたのミックス」なら、その番号 */
    fun activeIndex(): Int? = PlayLog.current?.key?.takeIf { it.startsWith("MIX") }?.removePrefix("MIX")?.toIntOrNull()

    /**
     * 押した時: 作り置きの中身があればそれをすぐ流す(カードのジャケットと同じ中身)。無ければその場で作る。
     * 流したあと、次に押した時の中身を裏で作り置きする。ジャケットは目の前では変えず、次にホームを開いた時に変える
     */
    fun play(activity: AppCompatActivity, player: PlayerConnection, m: Mix) {
        // 押したミックスのジャケットは、次にホームを開くまで変えない
        holdArt += m.index
        // オフラインの時は作り置き(☁ ネットの曲が入っている)は使わず、端末にある曲だけでその場で作る
        // (作り置きは捨てずに残す。オンラインに戻ったら使う)
        val offline = !Net.streamAllowed()
        // 作り置きでも、作った後にほかのミックスで流れた曲(直近 100 曲。「新曲をたくさん」の時は 30 曲)は外す。
        // 残りが 20 曲未満ならその場で作り直す
        val ready = (if (offline) null else takeReady(m))?.let { list ->
            val h = MixHistory.snapshot()
            val n = if (MixPrefs.moreNew()) MixHistory.NEW_RECENT_WHEN_MORE else MixHistory.LAST_COUNT
            list.filterNot { h.inLast(SkipLog.keysOf(it), n) }
        }?.takeIf { it.size >= 20 }
        if (ready != null) {
            player.playItems(ready, PlayLog.Ref(PlayLog.Kind.STREAM, "MIX${m.index}", m.title, label(m)))
            // 次の作り置きには、今流すキューの曲を入れない(次に押した時に同じ曲が出ないように)
            prepare(m, showArt = false, exclude = ready.flatMap { SkipLog.keysOf(it) }.toSet())
            return
        }
        Toast.makeText(activity, if (offline) "「${m.title}」を端末の曲で準備中…" else "「${m.title}」を準備中…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { build(m, FIRST, emptySet()) }
            if (items.isEmpty()) {
                Toast.makeText(activity, if (offline) "オフラインのため、このミックスで流せる端末の曲がありません" else "ミックスを作れませんでした", Toast.LENGTH_SHORT).show()
                return@launch
            }
            player.playItems(items, PlayLog.Ref(PlayLog.Kind.STREAM, "MIX${m.index}", m.title, label(m)))
            prepare(m, showArt = false, exclude = items.flatMap { SkipLog.keysOf(it) }.toSet())
        }
    }

    /** ミックスごとの、カードに出しているジャケット(作り置きの中身の先頭のほうの 4 曲。端末の画像の場所か、ネットの画像の URL) */
    val lastArt = kotlinx.coroutines.flow.MutableStateFlow<Map<Int, List<String>>>(emptyMap())
    /** まだ出していないジャケット(次にホームを開いた時に出す)。(どのまとまり用か, 絵) */
    private val pendingArt = java.util.concurrent.ConcurrentHashMap<Int, Pair<String, List<String>>>()
    /** 出しているジャケットが、どのまとまり用か(分け方が変わったら使わない) */
    private val artSig = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** カードに出すジャケット(そのまとまり用の作り置きの絵。無ければ null = アーティストのジャケット) */
    fun artFor(m: Mix): List<String>? = lastArt.value[m.index]?.takeIf { artSig[m.index] == sig(m) }

    /** ホームを描くたびに: 作り置きが無い・まとまりが変わったミックスは作り置きを始める(↻・分け直し・設定の切り替えの後もすぐ) */
    fun ensurePrepared(mixes: List<Mix>) {
        mixes.forEach { m ->
            val r = ready[m.index]
            if (r == null || r.first != sig(m)) prepare(m, showArt = true)
            // 作り置きはあるのに、そのジャケットが出ていない時(前の版から更新した直後など)は、作り置きの絵を出す
            else if (artFor(m) == null && !holdArt.contains(m.index) && !pendingArt.containsKey(m.index)) setArt(m.index, artOf(r.third), r.first)
        }
    }
    /** 押したばかりのミックス(ジャケットを目の前で変えない) */
    private val holdArt = java.util.Collections.synchronizedSet(HashSet<Int>())

    // ───────── 作り置き ─────────

    /** 作り置きの中身(ミックスの番号 → (どのミックス用か, 作った時刻, 中身)) */
    private val ready = java.util.concurrent.ConcurrentHashMap<Int, Triple<String, Long, List<MediaItem>>>()
    private val preparing = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** 同じミックスかの印(分け方が変わったら、作り置きは使わない) */
    private fun sig(m: Mix) = m.title + "|" + m.artists.joinToString(",")

    private fun takeReady(m: Mix): List<MediaItem>? {
        val r = ready[m.index] ?: return null
        // 分け方が変わった(名前や中心のアーティストが変わった)ものは使わない。再生していなければ古くても使う(↻ で作り直せる)
        if (r.first != sig(m)) { ready.remove(m.index); return null }
        ready.remove(m.index)
        saveReady()
        return r.third
    }

    /**
     * 次に押した時の中身を、裏で作り置きする。showArt = 作り終えたらすぐカードのジャケットに出すか
     * (ホームを開いた時の作り置きは出す。押したあとの作り置きは、目の前で変わらないよう次にホームを開いた時に出す)
     */
    fun prepare(m: Mix, showArt: Boolean, exclude: Set<String> = emptySet()) {
        // ネットが使えない時は作り置きしない(押した時にその場で作る)。
        // モバイル回線でも作る(取るのは曲の一覧だけで、曲そのものは取らない。「モバイル回線ではネットから再生しない」の時は作らない)
        if (!Net.streamAllowed()) return
        if (!preparing.add(m.index)) return
        warmScope.launch {
            try {
                val items = runCatching { build(m, FIRST, exclude) }.getOrDefault(emptyList())
                if (items.isEmpty()) return@launch
                ready[m.index] = Triple(sig(m), System.currentTimeMillis(), items)
                saveReady()
                val art = artOf(items)
                if (showArt && !holdArt.contains(m.index)) setArt(m.index, art, sig(m)) else pendingArt[m.index] = sig(m) to art
            } finally { preparing.remove(m.index) }
        }
    }

    /**
     * ホームを開いた時: 出していなかったジャケットを出し、作り置きが無い・古いミックスは作り置きを始める
     */
    fun onHomeShown(mixes: List<Mix>) {
        holdArt.clear()
        pendingArt.keys.toList().forEach { i -> pendingArt.remove(i)?.let { (sg, art) -> setArt(i, art, sg) } }
        mixes.forEach { m ->
            val r = ready[m.index]
            // 作り置きが無い・分け方が変わった時だけ作る(再生していない作り置きは、↻ を押すまでそのまま)
            if (r == null || r.first != sig(m)) prepare(m, showArt = true)
        }
    }

    private fun setArt(index: Int, art: List<String>, sg: String) {
        if (art.isEmpty()) return
        artSig[index] = sg
        lastArt.value = lastArt.value + (index to art)
        prefs?.edit()?.putString("mix_art_$index", art.joinToString("\n"))?.putString("mix_art_sig_$index", sg)?.apply()
    }

    /** 中身の先頭のほうから、アーティストが重ならないように 4 曲ぶんのジャケット */
    private fun artOf(items: List<MediaItem>): List<String> {
        val picked = mutableListOf<String>()
        val artists = HashSet<String>()
        // アーティストが重ならないように先頭から。足りなければ同じアーティストの別の曲も
        for (pass in 0..1) for (item in items) {
            if (picked.size >= 4) break
            val t = item.mediaId.toLongOrNull()?.let { Repository.track(it) }
            val art = t?.thumbPath?.takeIf { java.io.File(it).exists() } ?: item.mediaMetadata.artworkUri?.toString() ?: continue
            val a = Radio.mainArtist(item.mediaMetadata.artist?.toString().orEmpty()).lowercase()
            if (art in picked || (pass == 0 && a in artists)) continue
            picked += art; artists += a
        }
        return picked
    }

    /** 作り置きを保存(アプリを閉じても、次に開いた時にジャケットと中身が一致するように) */
    private fun saveReady() = runCatching {
        val o = org.json.JSONObject()
        ready.forEach { (i, r) ->
            val arr = org.json.JSONArray()
            r.third.forEach { item ->
                val tid = item.mediaId.toLongOrNull()
                if (tid != null && Repository.track(tid) != null) arr.put(org.json.JSONObject().put("t", tid))
                else StreamDownload.videoId(item)?.let { vid ->
                    arr.put(org.json.JSONObject().put("v", vid).put("title", item.mediaMetadata.title?.toString().orEmpty())
                        .put("artist", item.mediaMetadata.artist?.toString().orEmpty()).put("thumb", item.mediaMetadata.artworkUri?.toString().orEmpty()))
                }
            }
            o.put(i.toString(), org.json.JSONObject().put("sig", r.first).put("at", r.second).put("items", arr))
        }
        prefs?.edit()?.putString("mix_ready", o.toString())?.apply()
    }

    private fun loadReady() = runCatching {
        val o = org.json.JSONObject(prefs?.getString("mix_ready", null) ?: return@runCatching)
        o.keys().forEach { k ->
            val e = o.getJSONObject(k)
            val arr = e.getJSONArray("items")
            val items = (0 until arr.length()).mapNotNull { j ->
                val it = arr.getJSONObject(j)
                if (it.has("t")) Repository.track(it.getLong("t"))?.toMediaItem()
                else it.optString("v").takeIf { v -> v.length == 11 }?.let { v ->
                    PlayerConnection.streamItem(Searcher.Result(Searcher.Kind.SONG, v, "https://music.youtube.com/watch?v=$v",
                        it.optString("title"), it.optString("artist"), 0, it.optString("thumb").ifBlank { null }))
                }
            }
            if (items.isNotEmpty()) ready[k.toInt()] = Triple(e.getString("sig"), e.getLong("at"), items)
        }
    }

    /** 分け方が変わった時(↻・設定の切り替え): 前の中身のジャケットと作り置きは捨てる */
    fun forgetArt() {
        lastArt.value = emptyMap()
        pendingArt.clear()
        artSig.clear()
        ready.clear()
        prefs?.edit()?.apply { (1..3).forEach { remove("mix_art_$it"); remove("mix_art_sig_$it") }; remove("mix_ready") }?.apply()
    }

    /** 続き(再生サービスから。通信するので IO で)。exclude = すでにキューにある曲の鍵 */
    fun more(index: Int, exclude: Set<String>): List<MediaItem> =
        mixes().firstOrNull { it.index == index }?.let { build(it, MORE, exclude) }.orEmpty()

    /** kind = 種類(最近流れた曲を外す期間が違う): よく聴く曲 / 新曲 / 似た曲 */
    private class Cand(val key: List<String>, val vid: String?, val artist: String, var score: Double,
                       val kind: Int = SIMILAR, val make: () -> MediaItem)
    private const val FAV = 0
    private const val NEW = 1
    private const val SIMILAR = 2

    /** 最近ミックスで流れた曲を外す。外すと少なすぎる(20 曲未満)時は、古く流れたものから戻す */
    private fun dropRecentlyPlayed(pool: MutableList<Cand>, moreNew: Boolean) {
        val h = MixHistory.snapshot()
        val now = System.currentTimeMillis()
        val blocked = pool.filter { c ->
            // 「新曲をたくさん」の新曲は、直近 30 曲に流れたものだけ外す(新曲は数が少なく、選んだ意味が無くならないように)
            if (moreNew && c.kind == NEW) h.inLast(c.key, MixHistory.NEW_RECENT_WHEN_MORE)
            else h.blocked(c.key, when (c.kind) { FAV -> MixHistory.FAV_MS; NEW -> MixHistory.NEW_MS; else -> MixHistory.SIMILAR_MS }, now)
        }
        if (blocked.isEmpty()) return
        pool.removeAll(blocked.toSet())
        val need = MIN_AFTER_DROP - pool.size
        if (need > 0) pool += blocked.sortedBy { h.lastPlayed(it.key) }.take(need)
    }
    private const val MIN_AFTER_DROP = 20

    private fun build(m: Mix, count: Int, exclude: Set<String>): List<MediaItem> {
        val rnd = java.util.Random()
        val now = System.currentTimeMillis()
        val lib = Repository.tracks.value.filterNot { Repository.cantPlay(it.id) }
        val plays = PlayLog.counts(60)
        // 直近 3 時間に聴いた曲(少し下げる)
        val recent = HashSet<String>().apply {
            PlayLog.playsSince(now - 3 * 3600_000L).mapNotNull { Repository.track(it.first) }.forEach { t ->
                addAll(SkipLog.keysOf(youtubeVideoId(t.sourceUrl), t.title, t.artist))
            }
        }
        val cands = mutableListOf<Cand>()
        val online = Net.streamAllowed()
        // No Good に似たものを、まだ調べていない分だけ裏で調べる(次から効く)
        NoGoodSimilar.refresh()
        // ミュージックビデオだけ(設定で「ミュージック」をオフ)
        val mvOnly = !MixPrefs.useMusic() && MixPrefs.useYouTube()
        // 通信が要るもの(新曲・似た曲のラジオ)は、順番ではなく同時に取る(押してから鳴るまでを短く)
        val have = VideoAlias.ownedIds(lib)
        val radioSeeds = if (!online) emptyList() else if (m.seeds.isNotEmpty()) m.seeds.mapNotNull { it.vid }.shuffled(rnd).take(2)
            else m.artists.take(2).mapNotNull { a -> Radio.seedTrack(a)?.let { youtubeVideoId(it.sourceUrl) } }
                .ifEmpty { m.artists.take(1).mapNotNull { a -> runCatching { InnerTube.findSongId("", a) }.getOrNull() } }
        val (newList, radioLists) = if (!online) emptyList<Searcher.Result>() to emptyList<Pair<String, List<Searcher.Result>>>() else
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                // ミックスにするもの(設定): ミュージックビデオだけの時は、新曲も MV に置き換える(MV が無い曲は入れない)
                val nj = async {
                    val list = runCatching { newSongs(m.artists, more = MixPrefs.moreNew()) }.getOrDefault(emptyList())
                    if (!mvOnly) list else list.map { r -> async { toMv(r) } }.mapNotNull { it.await() }
                }
                // ミュージックビデオだけの時は、両方(YouTube Music のラジオ・YouTube のミックス)から動画だけを拾う
                val rj = radioSeeds.map { s -> async { s to runCatching {
                    if (mvOnly) InnerTube.videoRadio(s)
                    else InnerTube.radioFrom(s, MixPrefs.useMusic(), MixPrefs.useYouTube())
                }.getOrNull().orEmpty() } }
                nj.await() to rj.map { it.await() }
            }
        // ① 新曲: 新しいものほど高く(いちばん重く扱う)。「新曲をたくさん」の時は、ほかの曲より必ず前に
        val moreNew = MixPrefs.moreNew()
        // 「新曲をたくさん」: まだミックスで流れていない新曲を先に、流れた新曲はそのあとに流れたのが古い順で
        // (毎回同じ新曲から始まらないように。どちらもほかの種類の曲よりは前)
        val heardOrder: Map<Int, Int> = if (!moreNew) emptyMap() else {
            val h = MixHistory.snapshot()
            newList.indices.map { it to h.lastPlayed(SkipLog.keysOf(newList[it].videoId, newList[it].title, newList[it].artist)) }
                .filter { it.second > 0 }.sortedBy { it.second }.mapIndexed { rank, (i, _) -> i to rank }.toMap()
        }
        newList.forEachIndexed { i, r ->
            val local = r.videoId?.let { VideoAlias.trackFor(it) }?.takeIf { Net.canPlayNow(it) }
            val score = when {
                !moreNew -> 100.0 - i * 2.0
                i in heardOrder -> 200.0 - heardOrder.getValue(i)
                else -> 300.0 - i
            }
            cands += Cand(SkipLog.keysOf(r.videoId, r.title, r.artist), r.videoId, r.artist, score, kind = NEW) {
                local?.toMediaItem() ?: PlayerConnection.streamItem(r)
            }
        }
        // ② よく聴く曲: よく聴いているほど高く(曲調のミックスは、そのまとまりの種の曲)
        if (mvOnly) mvFavorites(m, lib, plays, online).forEach { cands += it }
        else if (m.seeds.isNotEmpty()) m.seeds.forEach { s ->
            val t = s.track ?: s.vid?.let { VideoAlias.trackFor(it, includeMissing = true) }
            val n = t?.let { plays[it.id] } ?: 0
            val playable = t?.takeIf { Net.canPlayNow(it) }
            if (playable == null && (s.vid == null || !online)) return@forEach
            cands += Cand(SkipLog.keysOf(s.vid, s.title, s.artist), s.vid, s.artist, 45.0 + minOf(n, 20) * 1.5, kind = FAV) {
                playable?.toMediaItem() ?: PlayerConnection.streamItem(Searcher.Result(Searcher.Kind.SONG, s.vid!!,
                    "https://music.youtube.com/watch?v=${s.vid}", s.title, s.artist, 0, s.thumb))
            }
        } else m.artists.forEach { a ->
            lib.filter { Radio.mainArtist(it.artist) == a && Net.canPlayNow(it) }.forEach { t ->
                val n = plays[t.id] ?: 0
                cands += Cand(SkipLog.keysOf(youtubeVideoId(t.sourceUrl), t.title, t.artist), youtubeVideoId(t.sourceUrl), t.artist,
                    45.0 + minOf(n, 20) * 1.5, kind = FAV) { t.toMediaItem() }
            }
        }
        // ③ 似た曲: よく聴く曲のラジオから(まだ持っていない曲)
        run {
            radioLists.forEach { (seed, list) ->
                list
                    .filter { it.videoId != null && it.videoId !in have && it.videoId != seed }
                    // ジャンルのミックスは、ほかのジャンルと分かっている曲を入れない
                    .filter { r -> m.genre == null || ArtistGenres.fits(r.artist, r.title, m.genre) }
                    .forEachIndexed { i, r ->
                        cands += Cand(SkipLog.keysOf(r.videoId, r.title, r.artist), r.videoId, r.artist, 40.0 - i * 0.5) {
                            PlayerConnection.streamItem(r)
                        }
                    }
            }
        }
        // 除く: No Good・すでにキューにある曲。同じ曲は点数の高い方だけ
        val seen = HashSet<String>(exclude)
        val pool = cands.sortedByDescending { it.score }.filter { c ->
            if (NoGood.isSong(c.vid) || NoGood.isArtist(Radio.mainArtist(c.artist))) return@filter false
            if (c.key.any { it in seen }) return@filter false
            seen.addAll(c.key); true
        }.onEach { c ->
            if (SkipLog.isSkipped(c.key)) c.score -= 40   // すぐ飛ばした曲は出にくく
            // No Good に似たもの(似た曲・似ているアーティスト)は出にくく(外しはしない。似ているかの判定は外れることもあるため)
            if (NoGoodSimilar.similarSong(c.key)) c.score -= 60
            if (NoGoodSimilar.similarArtist(c.artist)) c.score -= 40
            if (c.key.any { it in recent }) c.score -= 30  // 聴いたばかりの曲も少し下げる
            c.score += rnd.nextDouble() * 15              // 毎回少しずつ違う並びに
        }.sortedByDescending { it.score }.toMutableList()
        // 最近ミックスで流れた曲は外す(似た曲 3 日・新曲 1 日・よく聴く曲 12 時間・どれでも直近 100 曲)
        dropRecentlyPlayed(pool, moreNew)
        // 並べる: 同じアーティストが続かないように、10 曲の中で同じアーティストは 3 曲まで
        val out = mutableListOf<Cand>()
        fun ak(c: Cand) = Radio.mainArtist(c.artist).lowercase()
        while (out.size < count && pool.isNotEmpty()) {
            val last = out.lastOrNull()?.let { ak(it) }
            val window = out.takeLast(9).map { ak(it) }
            val i = pool.indexOfFirst { c -> ak(c) != last && window.count { it == ak(c) } < 3 }
            out += pool.removeAt(if (i >= 0) i else 0)
        }
        return out.map { it.make() }
    }

    /** 曲(音源)を、その曲のミュージックビデオに置き換える。MV が無い・分からない時は null */
    private fun toMv(r: Searcher.Result): Searcher.Result? {
        val v = r.videoId ?: return null
        val (mv, sure) = runCatching { InnerTube.musicVideoLookup(v) }.getOrDefault(null to false)
        if (!sure || mv == null) return null
        return r.copy(id = mv, url = "https://www.youtube.com/watch?v=$mv")
    }

    /**
     * ミュージックビデオだけの時の「よく聴く曲」: 動画も保存してある曲はそれを、ほかはよく聴く順に 20 曲まで MV を探す
     * (1 曲ずつ問い合わせるので、同時に取る)。オフラインの時は動画を保存してある曲だけ
     */
    private fun mvFavorites(m: Mix, lib: List<Track>, plays: Map<Long, Int>, online: Boolean): List<Cand> {
        data class Fav(val vid: String?, val title: String, val artist: String, val thumb: String?, val track: Track?, val n: Int)
        val favs = (if (m.seeds.isNotEmpty()) m.seeds.map { s ->
            val t = s.track ?: s.vid?.let { VideoAlias.trackFor(it, includeMissing = true) }
            Fav(s.vid, s.title, s.artist, s.thumb, t, t?.let { plays[it.id] } ?: 0)
        } else m.artists.flatMap { a -> lib.filter { Radio.mainArtist(it.artist) == a } }.map { t ->
            Fav(youtubeVideoId(t.sourceUrl), t.title, t.artist, t.thumbPath, t, plays[t.id] ?: 0)
        }).sortedByDescending { it.n }
        val out = mutableListOf<Cand>()
        // 動画を保存してある曲(通信なしで映像が出る)
        favs.forEach { f ->
            val t = f.track ?: return@forEach
            if (!t.hasVideo || !Net.canPlayNow(t)) return@forEach
            out += Cand(SkipLog.keysOf(f.vid, f.title, f.artist), f.vid, f.artist, 45.0 + minOf(f.n, 20) * 1.5, kind = FAV) { t.toMediaItem() }
        }
        if (!online) return out
        val ask = favs.filter { it.vid != null && it.track?.hasVideo != true }.take(20)
        val found = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            ask.map { f -> async { f to toMv(Searcher.Result(Searcher.Kind.SONG, f.vid!!, "https://music.youtube.com/watch?v=${f.vid}", f.title, f.artist, 0, f.thumb)) } }
                .map { it.await() }
        }
        found.forEach { (f, mv) ->
            if (mv == null) return@forEach
            out += Cand(SkipLog.keysOf(mv.videoId, f.title, f.artist) + SkipLog.keysOf(f.vid, f.title, f.artist), mv.videoId, f.artist,
                45.0 + minOf(f.n, 20) * 1.5, kind = FAV) { PlayerConnection.streamItem(mv) }
        }
        return out
    }

    /** これまでに見つけてある新曲の動画 ID(ラジオで少し前に出すのに使う) */
    fun knownNewIds(): Set<String> = synchronized(newCache) { newCache.values.flatMap { it.second }.mapNotNull { it.videoId }.toSet() }

    /** 新曲の置き場(押すたびに通信しないよう 6 時間覚えておく) */
    private val newCache = HashMap<String, Pair<Long, List<Searcher.Result>>>()

    /** まとまりのアーティストの新曲(今年・去年のシングル・アルバムから。新しい順) */
    private fun newSongs(artists: List<String>, force: Boolean = false, more: Boolean = false): List<Searcher.Result> {
        // 「新曲をたくさん」は、アルバム・シングルを 12 まで、1 つから 4 曲まで(ふつうは 6 つから 3 曲)
        val ck = artists.joinToString("|") + if (more) "#more" else ""
        if (!force) synchronized(newCache) { newCache[ck]?.takeIf { System.currentTimeMillis() - it.first < 6 * 3600_000L }?.let { return it.second } }
        val minYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 1
        fun same(a: String, b: String) = Radio.mainArtist(a).equals(b, ignoreCase = true) || a.contains(b, ignoreCase = true)
        // まずはホームの「新作」で見つけてあるもの。少なければアーティストのページを見に行く
        var rel = NewReleases.list.value.filter { r -> artists.any { same(r.result.artist, it) } }
        if (rel.size < 2) rel = rel + artists.take(3).flatMap { a ->
            runCatching { InnerTube.findArtist(a)?.let { (real, id) -> InnerTube.artistReleases(real, id) } }.getOrNull().orEmpty()
        }
        val songs = rel.filter { it.year >= minYear }.distinctBy { it.result.id }
            .sortedByDescending { it.year }.take(if (more) 12 else 6)
            .flatMap { r -> runCatching { InnerTube.listTracks(r.result) }.getOrDefault(emptyList()).filter { it.videoId != null }.take(if (more) 4 else 3) }
            .distinctBy { it.videoId }
        synchronized(newCache) { newCache[ck] = System.currentTimeMillis() to songs }
        return songs
    }
}
