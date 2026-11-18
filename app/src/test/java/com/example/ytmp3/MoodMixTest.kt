package com.example.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodMixTest {
    private val rnd = java.util.Random(1)

    /** 強さ(dB)・明るさ・立ち上がり・テンポのまわりに、少しばらつかせた曲を n 曲 */
    private fun songs(n: Int, loud: Double, bright: Double, onset: Double, bpm: Double) = List(n) {
        doubleArrayOf(loud + rnd.nextGaussian(), bright + rnd.nextGaussian() * 150, onset + rnd.nextGaussian() * 0.2, bpm + rnd.nextGaussian() * 5)
    }

    @Test fun splitsIntoUpbeatMiddleCalmInOrder() {
        val calm = songs(10, -22.0, 1200.0, 1.0, 75.0)       // 番号 0〜9
        val upbeat = songs(10, -8.0, 4000.0, 4.0, 140.0)     // 番号 10〜19
        val middle = songs(10, -14.0, 2500.0, 2.5, 110.0)    // 番号 20〜29
        val groups = MoodMix.splitByMood(calm + upbeat + middle)
        assertNotNull(groups)
        assertEquals((10..19).toList(), groups!![0].sorted())  // アップテンポ
        assertEquals((20..29).toList(), groups[1].sorted())    // ミドル
        assertEquals((0..9).toList(), groups[2].sorted())      // しっとり
    }

    @Test fun similarSongsAreSplitRelatively() {
        // 似た曲調ばかりでも、その中で相対的に 3 つに分ける(それぞれ 8 曲以上)
        // 強さ・明るさ・立ち上がりが少しずつ増えていく 30 曲(差は小さめ)
        val gradual = List(30) { i -> doubleArrayOf(-15.0 + i * 0.1, 2400.0 + i * 5, 2.4 + i * 0.01, 108.0 + i * 0.2) }
        val groups = MoodMix.splitByMood(gradual)
        assertNotNull(groups)
        assertEquals(3, groups!!.size)
        assertEquals(30, groups.sumOf { it.size })
        groups.forEach { assertTrue(it.size >= 8) }
        // にぎやかな順: 1 つ目(アップテンポ)には一番にぎやかな曲、3 つ目(しっとり)には一番静かな曲
        assertTrue(29 in groups[0]); assertTrue(0 in groups[2])
    }

    @Test fun identicalSongsAreNotSplit() {
        // 全部まったく同じ数値(分けようがない)→ 曲調では分けない
        assertNull(MoodMix.splitByMood(List(30) { doubleArrayOf(-14.0, 2500.0, 2.5, 110.0) }))
    }

    @Test fun tooFewIsNotSplit() {
        // 9 曲から分ける。8 曲では分けない
        assertNull(MoodMix.splitByMood(songs(8, -14.0, 2500.0, 2.5, 110.0)))
    }

    @Test fun nineSongsCanBeSplit() {
        // 分析済みが 9 曲(3 曲ずつ)でも、はっきり違えば 3 つに分ける
        val g = MoodMix.splitByMood(songs(3, -22.0, 1200.0, 1.0, 75.0) + songs(3, -8.0, 4000.0, 4.0, 140.0) + songs(3, -14.0, 2500.0, 2.5, 110.0))
        assertNotNull(g)
        assertEquals(listOf(3, 4, 5), g!![0].sorted())
        assertEquals(listOf(6, 7, 8), g[1].sorted())
        assertEquals(listOf(0, 1, 2), g[2].sorted())
    }

    private fun namesOf(a: List<DoubleArray>, b: List<DoubleArray>, c: List<DoubleArray>): List<String> {
        val raw = a + b + c
        val members = listOf(a.indices.toList(), b.indices.map { it + a.size }, c.indices.map { it + a.size + b.size })
        return MoodMix.namesFor(raw, members)
    }

    @Test fun namesAreBasicWhenEverythingMovesTogether() {
        // 強さ・明るさ・立ち上がり・テンポがそろって違うだけなら、基本の名前
        assertEquals(listOf("アップテンポ", "ミドルテンポ", "しっとり"),
            namesOf(songs(10, -8.0, 4000.0, 4.0, 140.0), songs(10, -14.0, 2500.0, 2.5, 110.0), songs(10, -22.0, 1200.0, 1.0, 75.0)))
    }

    @Test fun namesFollowTheStandOutFeature() {
        // 強さだけ違う → パワフル
        assertEquals("パワフル", namesOf(songs(10, -4.0, 2500.0, 2.5, 110.0), songs(10, -14.0, 2500.0, 2.5, 110.0), songs(10, -15.0, 2400.0, 2.4, 108.0))[0])
        // 明るさが違う → キラキラポップ / メロウ
        val bright = namesOf(songs(10, -12.0, 4200.0, 2.5, 112.0), songs(10, -12.0, 3500.0, 2.5, 110.0), songs(10, -12.0, 1500.0, 2.4, 108.0))
        assertEquals("キラキラポップ", bright[0]); assertEquals("メロウ", bright[2])
        // テンポだけ違う → ゆったり
        assertEquals("ゆったり", namesOf(songs(10, -12.0, 2500.0, 2.5, 150.0), songs(10, -12.0, 2500.0, 2.5, 110.0), songs(10, -12.0, 2500.0, 2.5, 70.0))[2])
    }

    @Test fun veryQuietIsBedtime() {
        assertEquals("おやすみ前", namesOf(songs(10, -6.0, 4000.0, 4.0, 140.0), songs(10, -14.0, 2500.0, 2.5, 110.0), songs(10, -30.0, 600.0, 0.3, 60.0))[2])
    }

    @Test fun namesNeverRepeat() {
        val n = namesOf(songs(10, -12.0, 2500.0, 4.5, 112.0), songs(10, -12.0, 2500.0, 3.5, 110.0), songs(10, -12.0, 2500.0, 1.0, 108.0))
        assertEquals(3, n.toSet().size)
    }
}
