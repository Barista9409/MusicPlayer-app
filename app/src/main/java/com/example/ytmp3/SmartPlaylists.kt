package com.example.ytmp3

import android.content.Context

/**
 * 自動プレイリスト: 「よく聴く 50 曲」「最近追加した 50 曲」「しばらく聴いていない曲」。
 * 普通のプレイリストとして作り、中身を自動で入れ替える(アラーム・ショートカット・車の画面からも選べる)。
 * 消したものは作り直さない。
 */
object SmartPlaylists {
    private const val PREFS = "settings"

    private enum class Kind(val key: String, val title: String) {
        TOP("smart_top", "⟳ よく聴く 50 曲"),
        RECENT("smart_recent", "⟳ 最近追加した 50 曲"),
        FORGOTTEN("smart_forgotten", "⟳ しばらく聴いていない曲"),
    }

    /** 自動プレイリストか(並べ替え・曲の追加をしても次の更新で上書きされる) */
    fun isSmart(c: Context, playlistId: Long): Boolean {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Kind.entries.any { p.getLong(it.key, -1L) == playlistId }
    }

    /** 中身を作り直す(起動時など。6 時間に 1 回まで) */
    fun refresh(c: Context, force: Boolean = false) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - p.getLong("smart_at", 0L) < 6 * 3600_000L) return
        val tracks = Repository.tracks.value.filterNot { Repository.cantPlay(it.id) }
        if (tracks.isEmpty()) return
        val counts = PlayLog.counts(30)
        val ids = mapOf(
            Kind.TOP to counts.entries.sortedByDescending { it.value }.map { it.key }
                .filter { id -> tracks.any { it.id == id } }.take(50),
            Kind.RECENT to tracks.sortedByDescending { it.savedAt }.take(50).map { it.id },
            Kind.FORGOTTEN to run {
                val old = PlayLog.rediscover().filter { id -> tracks.any { it.id == id } }
                val played = PlayLog.playedIds()
                (old + tracks.filter { it.id !in played }.shuffled().map { it.id }).distinct().take(50)
            },
        )
        val ed = p.edit()
        for ((kind, list) in ids) {
            val saved = p.getLong(kind.key, -1L)
            when {
                // 消されたものは作り直さない
                saved == -2L -> continue
                saved >= 0 && Repository.playlist(saved) == null -> { ed.putLong(kind.key, -2L); continue }
                list.isEmpty() -> continue
                saved >= 0 -> Repository.setPlaylistOrder(saved, list)
                else -> ed.putLong(kind.key, Repository.createPlaylist(kind.title, list).id)
            }
        }
        ed.putLong("smart_at", System.currentTimeMillis()).apply()
    }
}
