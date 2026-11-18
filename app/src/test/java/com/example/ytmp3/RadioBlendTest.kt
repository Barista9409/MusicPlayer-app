package com.example.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioBlendTest {
    private fun r(id: String, title: String, artist: String) =
        Searcher.Result(Searcher.Kind.SONG, id, "https://music.youtube.com/watch?v=$id", title, artist, 0, null)

    private val ytm = listOf(r("s", "アイドル", "YOASOBI")) + listOf(
        "夜に駆ける" to "YOASOBI", "怪物" to "YOASOBI", "KICK BACK" to "米津玄師", "Lemon" to "米津玄師",
        "ドライフラワー" to "優里", "ベテルギウス" to "優里", "Subtitle" to "Official髭男dism", "Pretender" to "Official髭男dism",
        "新時代" to "Ado", "うっせぇわ" to "Ado", "知らない歌" to "Someone Random", "謎の曲" to "Unknown Band",
    ).mapIndexed { i, (t, a) -> r("y$i", t, a) }

    private val yt = listOf(
        r("s", "YOASOBI「アイドル」 Official Music Video", "Ayase / YOASOBI"),
        r("v1", "YOASOBI「夜に駆ける」 Official Music Video", "Ayase / YOASOBI"),
        r("v2", "米津玄師 - KICK BACK", "米津玄師"),
        r("v3", "Ado 「唱」MUSIC VIDEO", "Ado"),
        r("v4", "優里『ドライフラワー』Official Music Video", "優里ちゃんねる"),
        r("v5", "Official髭男dism - Subtitle [Official Video]", "Official髭男dism"),
        r("v6", "猫ミーム 集", "Random Uploader"),
        r("v7", "米津玄師 MV「Lemon」", "米津玄師"),
    )

    @Test fun dropsUnrelatedFromBothSides() {
        val out = RadioBlend.blend(ytm, yt, "s").map { it.title }
        assertEquals("アイドル", out.first())
        assertFalse("知らない歌" in out); assertFalse("謎の曲" in out); assertFalse("猫ミーム 集" in out)
        assertTrue("夜に駆ける" in out); assertTrue("ベテルギウス" in out)
        // 同じ曲の MV は重ねない
        assertFalse(out.any { it.contains("Lemon") && it != "Lemon" })
        // もう片方にだけある、アーティストが重なる曲は入る
        assertTrue("Ado 「唱」MUSIC VIDEO" in out)
    }

    @Test fun keepsPrimaryWhenTooLittleOverlaps() {
        val heal = listOf(r("seed", "【脳と身体を深く休ませる】528Hz", "静かな瞑想と癒しの音楽")) +
            (0 until 20).map { r("h$it", "睡眠用BGM $it 癒し", "Healing Channel ${it % 4}") }
        val pop = listOf(r("j1", "History", "ちゃんみな"), r("j2", "君ロス", "乃木坂46"))
        assertEquals(heal, RadioBlend.blend(heal, pop, "seed"))
    }

    @Test fun emptySideFallsBack() {
        assertEquals(ytm, RadioBlend.blend(ytm, emptyList(), "s"))
        assertEquals(yt, RadioBlend.blend(emptyList(), yt, "s"))
    }
}
