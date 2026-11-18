package com.example.ytmp3

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.json.JSONObject

/**
 * 聴けない曲(Premium 限定など)の「別の音源」。
 * 見つかったものは VideoAlias に、見つからなかったものはここに(1 日たったら探し直す)覚える
 */
object AltSources {
    enum class State { UNKNOWN, FOUND, NONE }

    private const val RETRY_MS = 24 * 3600_000L
    private lateinit var app: Context
    /** 見つからなかった曲 → 探した時刻 */
    private val none = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 探している最中の曲(同じ曲を同時に 2 回探さない。別の曲どうしは待たせない) */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Deferred<String?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 状態が変わったら増える(一覧の描き直し用) */
    val changed = MutableStateFlow(0L)

    fun init(c: Context) {
        app = c.applicationContext
        runCatching {
            val o = JSONObject(prefs().getString("alt_none", "{}") ?: "{}")
            o.keys().forEach { k -> none[k] = o.getLong(k) }
        }
    }

    private fun prefs() = app.getSharedPreferences("alt_sources", Context.MODE_PRIVATE)
    private fun saveNone() = runCatching {
        prefs().edit().putString("alt_none", JSONObject(none as Map<*, *>).toString()).apply()
    }

    /** 聴けない曲か(一覧で灰色だった・再生して Premium 限定と分かった) */
    fun isLocked(vid: String?, restricted: Boolean = false) = vid != null && (restricted || Searcher.isUnplayable(vid))

    /** 見つかっている別の音源 */
    fun knownAlt(vid: String?): String? = vid?.let { VideoAlias.get(it) }?.takeIf { !Searcher.isUnplayable(it) }

    fun state(vid: String?): State {
        vid ?: return State.UNKNOWN
        if (knownAlt(vid) != null) return State.FOUND
        val t = none[vid] ?: return State.UNKNOWN
        return if (System.currentTimeMillis() - t < RETRY_MS) State.NONE else State.UNKNOWN
    }

    /** 再生・追加に使う動画 ID(聴けない曲で別の音源があればそちら) */
    fun playableId(vid: String?, restricted: Boolean = false): String? =
        if (isLocked(vid, restricted)) knownAlt(vid) ?: vid else vid

    /**
     * 別の音源を探す(通信するので IO で)。見つからなければ null。1 日以内に探して無かった曲は探さない。
     * 通信エラーで探せなかった時は覚えない(次にまた探す)
     */
    suspend fun find(vid: String, title: String?, artist: String?): String? {
        knownAlt(vid)?.let { return it }
        if (state(vid) == State.NONE) return null
        // 登録してから動かす(登録より先に終わって、終わった結果が残り続けないように)
        val job = inFlight.getOrPut(vid) {
            scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    val alt = runCatching { InnerTube.alternativeFor(vid, title, artist) }
                    alt.onSuccess { a ->
                        if (a != null) { VideoAlias.put(vid, a); none.remove(vid) } else none[vid] = System.currentTimeMillis()
                        saveNone()
                        changed.value = System.currentTimeMillis()
                    }
                    alt.getOrNull()
                } finally { inFlight.remove(vid) }
            }
        }
        job.start()
        return job.await()
    }

    /** 一覧を開いた時: 聴けない曲の別の音源を、裏で順に(一度に 1 曲ずつ、最大 15 曲)探しておく */
    fun lookAhead(results: List<Searcher.Result>) {
        val todo = results.filter { isLocked(it.videoId, it.restricted) && state(it.videoId) == State.UNKNOWN }.take(15)
        if (todo.isEmpty() || !Net.streamAllowed()) return
        scope.launch { todo.forEach { r -> find(r.videoId!!, r.title, r.artist) } }
    }

    /** 一覧の 2 行目に出す説明と、行の濃さ(聴けない曲でなければ null) */
    fun label(r: Searcher.Result): Pair<String, Float>? {
        if (!isLocked(r.videoId, r.restricted)) return null
        return when (state(r.videoId)) {
            State.FOUND -> "🔄 別の音源で再生" to 1f
            State.NONE -> "🔒 Premium 限定・聴ける音源なし" to 0.45f
            State.UNKNOWN -> "🔒 Premium 限定・別の音源を探します" to 0.6f
        }
    }
}
