package com.example.ytmp3

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MoodAnalyzerTest {
    private val rate = 22050

    /** freq Hz の音を secs 秒(beatEvery 秒ごとに鳴らす。0 なら鳴りっぱなし) */
    private fun tone(freq: Double, amp: Double, secs: Int = 10, beatEvery: Double = 0.0): ShortArray =
        ShortArray(rate * secs) { i ->
            val t = i.toDouble() / rate
            val on = beatEvery <= 0.0 || (t % beatEvery) < beatEvery / 4
            if (on) (sin(2 * PI * freq * t) * amp * 32767).toInt().toShort() else 0
        }

    @Test fun louderIsLouder() {
        val quiet = MoodAnalyzer.features(tone(440.0, 0.05), rate)
        val loud = MoodAnalyzer.features(tone(440.0, 0.8), rate)
        assertTrue(loud.loud > quiet.loud)
    }

    @Test fun higherIsBrighter() {
        val low = MoodAnalyzer.features(tone(110.0, 0.5), rate)
        val high = MoodAnalyzer.features(tone(3000.0, 0.5), rate)
        assertTrue(high.bright > low.bright)
    }

    @Test fun busierHasMoreOnsets() {
        val slow = MoodAnalyzer.features(tone(440.0, 0.5, beatEvery = 1.0), rate)
        val fast = MoodAnalyzer.features(tone(440.0, 0.5, beatEvery = 0.25), rate)
        assertTrue(fast.onset > slow.onset)
    }

    @Test fun tempoIsAboutRight() {
        // 0.5 秒ごとの拍 = 120 BPM(半分の 60 に間違えない)
        val f = MoodAnalyzer.features(tone(440.0, 0.5, secs = 12, beatEvery = 0.5), rate)
        assertTrue("テンポ ${f.bpm}", f.bpm in 110.0..130.0)
    }
}
