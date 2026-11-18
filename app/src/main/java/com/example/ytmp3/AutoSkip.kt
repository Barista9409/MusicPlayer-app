package com.example.ytmp3

import android.content.Context

/** 曲が何らかの理由で止まった時(読み込みが進まない・音が止まった)に自動で次へ(既定: オン) */
object AutoSkip {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun isOn(c: Context) = prefs(c).getBoolean("auto_skip", true)
    fun setOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto_skip", on).apply()
}
