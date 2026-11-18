package com.example.ytmp3

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NoGoodTest {
    @Before fun clear() { NoGood.songs.value = emptyList(); NoGood.artists.value = emptyList() }
    @After fun tearDown() = clear()

    @Test fun songsMatchByVideoId() {
        NoGood.songs.value = listOf(NoGood.Entry("abcdefghijk", "曲", "人"))
        assertTrue(NoGood.isSong("abcdefghijk"))
        assertFalse(NoGood.isSong("zzzzzzzzzzz"))
        assertFalse(NoGood.isSong(null))
    }

    @Test fun artistsMatchByIdOrNameIgnoringCase() {
        NoGood.artists.value = listOf(NoGood.Entry("UC123", "King Gnu", ""))
        assertTrue(NoGood.isArtist("UC123"))
        assertTrue(NoGood.isArtist("king gnu"))
        assertTrue(NoGood.isArtist(" King Gnu "))
        assertFalse(NoGood.isArtist("Ado"))
        assertFalse(NoGood.isArtist(null))
    }

    @Test fun removeUnhides() {
        NoGood.artists.value = listOf(NoGood.Entry("UC123", "King Gnu", ""))
        NoGood.removeArtists(setOf("UC123"))
        assertFalse(NoGood.isArtist("King Gnu"))
    }
}
