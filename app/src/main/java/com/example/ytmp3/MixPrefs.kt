package com.example.ytmp3

import android.content.Context

/** 設定「✨ おすすめ」: あなたのミックスの中身と分け方 */
object MixPrefs {
    private lateinit var app: Context
    fun init(c: Context) { app = c.applicationContext }
    /** 変わったら増える(ホームのミックスの描き直し用) */
    val changed = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private fun prefs() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** ミックスの中身: true = 新曲をたくさん / false = バランスよく(はじめ) */
    fun moreNew(): Boolean = ::app.isInitialized && prefs().getBoolean("mix_more_new", false)
    fun setMoreNew(on: Boolean) { prefs().edit().putBoolean("mix_more_new", on).apply(); DailyMix.forgetArt(); changed.value = System.currentTimeMillis() }

    /** ミックスの分け方: "mood" = 曲調(はじめ) / "artist" = アーティスト / "genre" = ジャンル */
    fun split(): String {
        if (!::app.isInitialized) return "mood"
        return prefs().getString("mix_split", null) ?: if (prefs().getBoolean("mix_by_mood", true)) "mood" else "artist"
    }
    fun byMood(): Boolean = split() == "mood"
    fun byGenre(): Boolean = split() == "genre"
    fun setSplit(mode: String) {
        prefs().edit().putString("mix_split", mode).putBoolean("mix_by_mood", mode == "mood").apply()
        DailyMix.forgetArt(); changed.value = System.currentTimeMillis()
    }

    /**
     * ミックスにするもの(両方オンがはじめ。どちらか 1 つは必ずオン)
     *  useMusic = ミュージック(曲・音源。YouTube Music のラジオ)/ useYouTube = ミュージックビデオ(YouTube の動画)
     * ミュージックビデオだけの時は、動画だけで作る(音源・静止画の曲は入れない。動画が見つからない曲は入れない)
     */
    fun useMusic(): Boolean = !::app.isInitialized || prefs().getBoolean("mix_src_music", true)
    fun useYouTube(): Boolean = !::app.isInitialized || prefs().getBoolean("mix_src_youtube", true)

    /**
     * 取ってくる場所を切り替える。両方オフになる時は切り替えずに false。
     * ミュージックビデオだけになった時は、再生中の画面の 🎬(映像)をオンにする
     */
    fun setSource(music: Boolean, youtube: Boolean): Boolean {
        if (!music && !youtube) return false
        prefs().edit().putBoolean("mix_src_music", music).putBoolean("mix_src_youtube", youtube).apply()
        if (youtube && !music) NowPlayingActivity.setVideoMode(app, true)
        DailyMix.forgetArt(); changed.value = System.currentTimeMillis()
        return true
    }
}
