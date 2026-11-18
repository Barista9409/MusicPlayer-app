package com.example.ytmp3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors

/**
 * スキン(見た目)。設定 → 見た目 で選ぶ。
 * - 色: ネイビー(いつもの)/壁紙の色/桜/ミント/ラベンダー/ワイン/モノクロ
 * - イラスト: 背景にイラストが入るスキン
 * - 明るさ: 端末に合わせる/ライト/ダーク/真っ黒
 * - 再生中の画面をジャケットの色にする
 */
object Skin {
    data class Def(val key: String, val label: String, val overlay: Int, val background: Int = 0, val swatch: Int,
                   val illust: Boolean = false, val mesh: Mesh? = null)

    /** グラデーションの色。dark = 白い文字(ダーク固定)、false = 黒い文字(ライト固定) */
    class Mesh(val dark: Boolean, val bg: Int, val colors: IntArray)

    private fun mesh(dark: Boolean, bg: String, vararg cols: String) =
        Mesh(dark, MeshDrawable.c(bg), cols.map { MeshDrawable.c(it) }.toIntArray())

    val all = listOf(
        Def("navy", "ネイビー(いつもの)", 0, swatch = 0xFF16213E.toInt()),
        Def("dynamic", "壁紙の色に合わせる", 0, swatch = 0),
        Def("sakura", "桜", R.style.ThemeOverlay_Skin_Sakura, swatch = 0xFFC2477A.toInt()),
        Def("mint", "ミント", R.style.ThemeOverlay_Skin_Mint, swatch = 0xFF1E8C7A.toInt()),
        Def("lavender", "ラベンダー", R.style.ThemeOverlay_Skin_Lavender, swatch = 0xFF6E5BB8.toInt()),
        Def("wine", "ワイン", R.style.ThemeOverlay_Skin_Wine, swatch = 0xFF8A2846.toInt()),
        Def("mono", "モノクロ", R.style.ThemeOverlay_Skin_Mono, swatch = 0xFF3A3A3A.toInt()),
        Def("sunset", "夕焼けの丘", R.style.ThemeOverlay_Skin_Sunset, R.drawable.skin_sunset, 0xFFE0703C.toInt(), true),
        Def("night", "星降る夜", R.style.ThemeOverlay_Skin_Night, R.drawable.skin_night, 0xFF3D4FA8.toInt(), true),
        Def("ocean", "波と海", R.style.ThemeOverlay_Skin_Ocean, R.drawable.skin_ocean, 0xFF1B87A6.toInt(), true),
        Def("forest", "森の朝", R.style.ThemeOverlay_Skin_Forest, R.drawable.skin_forest, 0xFF3F8A4A.toInt(), true),
        Def("pop", "ポップ", R.style.ThemeOverlay_Skin_Pop, R.drawable.skin_pop, 0xFFE2508C.toInt(), true),
        // グラデーション(文字が読みやすいかを測って選んだ 9 色)
        Def("m_aurora", "オーロラ", R.style.ThemeOverlay_Skin_MeshAurora, swatch = 0, mesh = mesh(true, "#061423", "#117C63", "#1B428A", "#062539", "#4C399E", "#0D6672")),
        Def("m_deepsea", "深海", R.style.ThemeOverlay_Skin_MeshDeepsea, swatch = 0, mesh = mesh(true, "#050B1E", "#1B4F9C", "#0E7C8C", "#132A5E", "#3B2F8F", "#0F5E7A")),
        Def("m_afterglow", "夕焼けの残り", R.style.ThemeOverlay_Skin_MeshAfterglow, swatch = 0, mesh = mesh(true, "#1A1026", "#8F3749", "#52317C", "#A25F3D", "#2D215F", "#6E2E5F")),
        Def("m_rose", "真夜中のバラ", R.style.ThemeOverlay_Skin_MeshRose, swatch = 0, mesh = mesh(true, "#140A14", "#8E2E5A", "#3C2470", "#B85A6E", "#24183F", "#6A2C6E")),
        Def("m_peach", "ピーチ", R.style.ThemeOverlay_Skin_MeshPeach, swatch = 0, mesh = mesh(false, "#FFF4EC", "#FFC3A0", "#FFB5C8", "#FFE0A3", "#F7C6E0", "#FFD1B8")),
        Def("m_lavender", "ラベンダー", R.style.ThemeOverlay_Skin_MeshLavender, swatch = 0, mesh = mesh(false, "#F4F1FF", "#C9BCFF", "#B7D3FF", "#E4C6FF", "#C6E4FF", "#D9CCFF")),
        Def("m_mint", "ミント", R.style.ThemeOverlay_Skin_MeshMint, swatch = 0, mesh = mesh(false, "#EEFBF6", "#A8E6CF", "#B5E2FA", "#D4F5C9", "#9FD8E8", "#C8F0E0")),
        Def("m_sakura", "さくら", R.style.ThemeOverlay_Skin_MeshSakura, swatch = 0, mesh = mesh(false, "#FFF5F8", "#FFC1D3", "#FFD6E0", "#F9C6F0", "#FFE4D6", "#F7B9C9")),
        Def("m_sky", "空", R.style.ThemeOverlay_Skin_MeshSky, swatch = 0, mesh = mesh(false, "#F0F7FF", "#A9D2FF", "#C6E7FF", "#B5C8FF", "#D8F0FF", "#9FC5F2")),
    )

    enum class Brightness(val label: String, val mode: Int) {
        SYSTEM("端末に合わせる", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("ライト", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("ダーク", AppCompatDelegate.MODE_NIGHT_YES),
        BLACK("真っ黒(有機 EL 向け)", AppCompatDelegate.MODE_NIGHT_YES),
    }

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun current(c: Context): Def = prefs(c).getString("skin", "navy").let { k -> all.firstOrNull { it.key == k } ?: all[0] }
    fun setCurrent(c: Context, key: String) {
        prefs(c).edit().putString("skin", key).apply()
        applyNightMode(c)
    }

    /** グラデーションのスキンは、文字が読みやすい明るさに固定 */
    private fun applyNightMode(c: Context) {
        val m = current(c).mesh
        AppCompatDelegate.setDefaultNightMode(when {
            m != null -> if (m.dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            else -> brightness(c).mode
        })
    }

    /** 画面の背景(イラスト・グラデーション)。無ければ null */
    fun backgroundDrawable(c: Context): android.graphics.drawable.Drawable? {
        val s = current(c)
        s.mesh?.let { return MeshDrawable(it) }
        return if (s.background != 0) androidx.core.content.ContextCompat.getDrawable(c, s.background) else null
    }

    fun brightness(c: Context): Brightness =
        runCatching { Brightness.valueOf(prefs(c).getString("brightness", "SYSTEM")!!) }.getOrDefault(Brightness.SYSTEM)
    fun setBrightness(c: Context, b: Brightness) {
        prefs(c).edit().putString("brightness", b.name).apply()
        applyNightMode(c)
    }

    /** 再生中の画面の背景をジャケットの色に(既定: オン) */
    fun artColors(c: Context) = prefs(c).getBoolean("art_colors", true)
    fun setArtColors(c: Context, on: Boolean) = prefs(c).edit().putBoolean("art_colors", on).apply()

    fun isNight(c: Context) =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** App.onCreate から: 全部の画面にスキンをかける */
    fun install(app: Application) {
        applyNightMode(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(a: Activity, b: Bundle?) = applyTheme(a)
            override fun onActivityCreated(a: Activity, b: Bundle?) = applyWindow(a)
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun translucent(a: Activity) = a is NowPlayingActivity || a is AlarmRingActivity

    private fun applyTheme(a: Activity) {
        val s = current(a)
        if (s.key == "dynamic") DynamicColors.applyToActivityIfAvailable(a)
        if (s.overlay != 0) a.theme.applyStyle(s.overlay, true)
        if (brightness(a) == Brightness.BLACK && s.mesh == null) a.theme.applyStyle(R.style.ThemeOverlay_Skin_Black, true)
    }

    private fun applyWindow(a: Activity) {
        if (translucent(a)) return
        val s = current(a)
        val w = a.window
        val night = isNight(a)
        val bg = MaterialColors.getColor(a, android.R.attr.colorBackground, 0)
        // 上のバーの色: ライトはアクセント色、ダークは背景と同じ
        w.statusBarColor = if (night) bg else MaterialColors.getColor(a, androidx.appcompat.R.attr.colorPrimary, bg)
        if (night) w.navigationBarColor = bg
        if (s.background != 0) w.setBackgroundDrawableResource(s.background)
        val m = s.mesh ?: return
        // グラデーション: 背景を画面いっぱいに。上のバーは透明、下のタブは半透明にして背景を見せる
        w.setBackgroundDrawable(MeshDrawable(m))
        w.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightStatusBars = !m.dark
        a.findViewById<android.view.View>(R.id.toolbar)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        a.findViewById<android.view.View>(R.id.bottomNav)?.let { nav ->
            val sc = MaterialColors.getColor(nav, com.google.android.material.R.attr.colorSurfaceContainer)
            nav.setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(sc, 0xB8))
            w.navigationBarColor = androidx.core.graphics.ColorUtils.setAlphaComponent(sc, 0xFF)
        }
    }

    /** 選び直した時: 開いている画面に反映 */
    fun reapply(a: Activity) = a.recreate()
}
