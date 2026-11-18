package com.example.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistGenresTest {
    @Test fun guessesFromLetters() {
        assertEquals("K-POP", ArtistGenres.guess("아이유", emptyList()))
        assertEquals("J-POP", ArtistGenres.guess("ヨルシカ", emptyList()))
        assertEquals("J-POP", ArtistGenres.guess("YOASOBI", listOf("アイドル")))
        assertEquals("洋楽ポップ", ArtistGenres.guess("Taylor Swift", listOf("Love Story")))
    }

    @Test fun picksTheMostCountedTagThatMeansSomething() {
        assertEquals("K-POP", ArtistGenres.fromTags(listOf("k-pop" to 5, "pop" to 3), "NewJeans", emptyList()))
        assertEquals("アニメ・ゲーム", ArtistGenres.fromTags(listOf("seen live" to 9, "anime" to 4, "j-pop" to 2), "LiSA", emptyList()))
        assertEquals("ロック", ArtistGenres.fromTags(listOf("rock" to 6, "j-pop" to 2), "ONE OK ROCK", emptyList()))
        // 「pop」だけなら、文字で J-POP か洋楽かを決める
        assertEquals("J-POP", ArtistGenres.fromTags(listOf("pop" to 3), "あいみょん", emptyList()))
        assertEquals("洋楽ポップ", ArtistGenres.fromTags(listOf("pop" to 3), "Dua Lipa", emptyList()))
        assertNull(ArtistGenres.fromTags(listOf("seen live" to 3), "Someone", emptyList()))
    }

    @Test fun keepsKpopAndJpopApartForUnknownArtists() {
        assertFalse(ArtistGenres.fits("ヨルシカ", "ただ君に晴れ", "K-POP"))
        assertFalse(ArtistGenres.fits("아이유", "Blueming", "J-POP"))
        assertTrue(ArtistGenres.fits("NewJeans", "Ditto", "K-POP"))   // 英字の名前は外さない
        assertTrue(ArtistGenres.fits("ヨルシカ", "ただ君に晴れ", "J-POP"))
        assertTrue(ArtistGenres.fits("ヨルシカ", "ただ君に晴れ", "ロック")) // K-POP・J-POP 以外は文字では外さない
    }
}
