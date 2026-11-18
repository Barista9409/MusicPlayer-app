package com.example.ytmp3

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * ホーム画面のアプリのアイコン(色違い)。
 * マニフェストの activity-alias(IconDefault / IconWhite …)のうち、選んだものだけを有効にする
 */
object AppIcon {
    data class Def(val key: String, val label: String, val alias: String, val mipmap: Int)

    val all = listOf(
        Def("default", "標準(ミッドナイト)", "IconDefault", R.mipmap.ic_launcher),
        Def("white", "ホワイト", "IconWhite", R.mipmap.ic_launcher_white),
        Def("black", "ブラック", "IconBlack", R.mipmap.ic_launcher_black),
        Def("bordeaux", "ボルドー", "IconBordeaux", R.mipmap.ic_launcher_bordeaux),
        Def("forest", "フォレスト", "IconForest", R.mipmap.ic_launcher_forest),
        Def("sky", "スカイブルー", "IconSky", R.mipmap.ic_launcher_sky),
        Def("sakura", "サクラ", "IconSakura", R.mipmap.ic_launcher_sakura),
        Def("lavender", "ラベンダー", "IconLavender", R.mipmap.ic_launcher_lavender),
        Def("sunset", "サンセット", "IconSunset", R.mipmap.ic_launcher_sunset),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun current(c: Context): Def = prefs(c).getString("app_icon", "default").let { k -> all.firstOrNull { it.key == k } ?: all[0] }

    /** アイコンを切り替える(選んだ入口を先に有効にしてから、ほかを無効に。入口が 0 個になる瞬間を作らない) */
    fun set(c: Context, key: String) {
        val def = all.firstOrNull { it.key == key } ?: return
        val pm = c.packageManager
        fun comp(d: Def) = ComponentName(c.packageName, "${c.packageName}.${d.alias}")
        pm.setComponentEnabledSetting(comp(def), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        all.filter { it != def }.forEach {
            pm.setComponentEnabledSetting(comp(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        prefs(c).edit().putString("app_icon", key).apply()
    }

    /** 起動時: 設定と実際に有効な入口が違えば合わせる(バックアップから戻した時など) */
    fun sync(c: Context) {
        val want = current(c)
        val pm = c.packageManager
        val st = pm.getComponentEnabledSetting(ComponentName(c.packageName, "${c.packageName}.${want.alias}"))
        val enabled = st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && want.key == "default")
        if (!enabled) set(c, want.key)
    }
}
