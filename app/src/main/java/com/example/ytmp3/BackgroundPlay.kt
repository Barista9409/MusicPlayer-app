package com.example.ytmp3

import android.content.Context

/** 設定: アプリを終了(最近使ったアプリから消す)しても、再生を続けるか(既定: 続ける) */
object BackgroundPlay {
    private const val KEY = "bg_play"
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun isOn(c: Context) = prefs(c).getBoolean(KEY, true)
    fun setOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY, on).apply()
}
