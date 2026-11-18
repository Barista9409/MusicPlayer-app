package com.example.ytmp3

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

/** 曲間のフェードとイコライザーの設定・適用 */
object SoundSettings {
    private const val PREFS = "settings"

    // ───────── 曲間のフェード ─────────

    /** フェードの秒数(0 = オフ) */
    fun fadeSec(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("fade_sec", 0)
    fun setFadeSec(c: Context, s: Int) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("fade_sec", s).apply()

    // ───────── イコライザー ─────────

    /** 選べる音質(端末の用意した設定 + 低音強め) */
    data class Preset(val id: String, val label: String)

    private val names = mapOf(
        "Normal" to "標準", "Classical" to "クラシック", "Dance" to "ダンス", "Flat" to "フラット",
        "Folk" to "フォーク", "Heavy Metal" to "メタル", "Hip Hop" to "ヒップホップ", "Jazz" to "ジャズ",
        "Pop" to "ポップ", "Rock" to "ロック",
    )

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var session = 0

    fun presetId(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("eq_preset", "off") ?: "off"

    fun presets(): List<Preset> {
        val out = mutableListOf(Preset("off", "オフ"), Preset("bass", "低音強め"), Preset("vocal", "ボーカルくっきり"))
        val e = eq ?: runCatching { Equalizer(0, 0) }.getOrNull()
        if (e != null) {
            runCatching {
                for (i in 0 until e.numberOfPresets) {
                    val n = e.getPresetName(i.toShort())
                    if (n != "Normal" && n != "Flat") out += Preset("p$i", names[n] ?: n)
                }
            }
            if (e !== eq) runCatching { e.release() }
        }
        return out
    }

    fun setPreset(c: Context, id: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("eq_preset", id).apply()
        apply(c)
    }

    /** 再生サービスから: 音声セッションが決まった/変わった時 */
    fun attach(c: Context, sessionId: Int) {
        // 同じ音声セッションなら掛け直さない(掛け直しの瞬間に音が乱れることがあるため)
        if (sessionId == session && session != 0 && (eq != null || presetId(c) == "off")) return
        release(); session = sessionId
        apply(c)
    }

    fun apply(c: Context) {
        if (session == 0) return
        val id = presetId(c)
        runCatching {
            if (id == "off") { eq?.enabled = false; bass?.enabled = false; return }
            val e = eq ?: Equalizer(0, session).also { eq = it }
            when {
                id == "bass" -> {
                    flat(e); e.enabled = true
                    (bass ?: BassBoost(0, session).also { bass = it }).apply { setStrength(700); enabled = true }
                }
                id == "vocal" -> {
                    bass?.enabled = false
                    // 中音域(1〜4kHz あたり)を持ち上げ、低音を少し控えめに
                    val (lo, hi) = e.bandLevelRange.let { it[0] to it[1] }
                    for (b in 0 until e.numberOfBands) {
                        val f = e.getCenterFreq(b.toShort()) / 1000
                        val lvl = when {
                            f < 150 -> -300
                            f in 800..5000 -> 400
                            else -> 0
                        }.coerceIn(lo.toInt(), hi.toInt())
                        e.setBandLevel(b.toShort(), lvl.toShort())
                    }
                    e.enabled = true
                }
                id.startsWith("p") -> {
                    bass?.enabled = false
                    e.usePreset(id.removePrefix("p").toShort())
                    e.enabled = true
                }
            }
        }
    }

    private fun flat(e: Equalizer) {
        for (b in 0 until e.numberOfBands) e.setBandLevel(b.toShort(), 0)
    }

    fun label(c: Context): String = presets().firstOrNull { it.id == presetId(c) }?.label ?: "オフ"

    fun release() {
        runCatching { eq?.release() }; runCatching { bass?.release() }
        eq = null; bass = null
    }
}
