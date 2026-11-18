package com.example.ytmp3

import android.content.Context

/**
 * キューが終わったら似た曲を自動で流す(ラジオ)。
 * - 基本はオン(設定で全体をオフにもできる)
 * - プレイリストを再生している時だけはオフ(プレイリストの最後で止まる)
 * - 再生中の画面の ∞ で、今のキューだけオン・オフを切り替えられる(次に別の曲を流すと元に戻る)
 */
object AutoContinue {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 設定画面のスイッチ(全体) */
    fun isGlobalOn(c: Context) = prefs(c).getBoolean("auto_continue", true)
    fun setGlobalOn(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("auto_continue", on).apply()
        override = null
        saveOverride()
    }

    /** 今のキューがプレイリストか */
    @Volatile var playingPlaylist = false
        private set
    /** 再生中の画面で切り替えた、今のキューだけの設定(アプリが一度終わっても覚えておく) */
    @Volatile private var override: Boolean? = null
    private var store: android.content.SharedPreferences? = null

    /** 起動時: 前回の「今のキューだけの設定」を戻す */
    fun init(c: Context) {
        val p = prefs(c)
        store = p
        override = if (p.contains("radio_override")) p.getBoolean("radio_override", true) else null
    }

    private fun saveOverride() {
        val p = store ?: return
        p.edit().apply { override?.let { putBoolean("radio_override", it) } ?: remove("radio_override") }.apply()
    }

    /** 今のキューで似た曲を流すか */
    fun isOn(c: Context) = override ?: (isGlobalOn(c) && !playingPlaylist)

    /** 再生中の画面のラジオ: 今のキューだけ */
    fun setOn(c: Context, on: Boolean) {
        if (store == null) store = prefs(c)
        override = on
        saveOverride()
    }

    /** 新しく再生を始めた時 */
    fun newQueue(isPlaylist: Boolean) {
        playingPlaylist = isPlaylist
        override = null
        saveOverride()
    }

    /** 起動時に前回のキューの種類だけ戻す(今のキューだけの設定は消さない) */
    fun restoreQueue(isPlaylist: Boolean) {
        playingPlaylist = isPlaylist
    }
}
