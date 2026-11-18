package com.example.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMatchTest {
    private fun track(title: String, artist: String) = Track(
        id = 1, title = title, artist = artist, album = "", uri = "", savedAt = 0, sizeBytes = 0,
        durationMs = 0, location = "", thumbPath = null, sourceUrl = "",
    )

    @Test fun artistKeyIgnoresCaseSpacesSymbolsAndTopic() {
        assertEquals("yoasobi", LibraryMatch.artistKey("YOASOBI - Topic"))
        assertEquals("mrsgreenapple", LibraryMatch.artistKey("Mrs. GREEN APPLE"))
        assertEquals(LibraryMatch.artistKey("あいみょん"), LibraryMatch.artistKey(" あいみょん "))
    }

    @Test fun titleKeyDropsBracketsAndSuffix() {
        assertEquals(LibraryMatch.titleKey("アイドル"), LibraryMatch.titleKey("アイドル (Official Music Video)"))
        assertEquals(LibraryMatch.titleKey("Lemon"), LibraryMatch.titleKey("Lemon【MV】"))
        assertEquals(LibraryMatch.titleKey("Lemon"), LibraryMatch.titleKey("Lemon - Remastered"))
    }

    @Test fun partsSplitsFeaturingAndCollaborators() {
        assertEquals(listOf("a", "b", "c"), LibraryMatch.parts("A, B & C"))
        assertEquals(listOf("a", "b"), LibraryMatch.parts("A feat. B"))
    }

    @Test fun indexFindsArtistsIncludingCollaborators() {
        val idx = LibraryMatch.Index(listOf(track("曲", "Ado & Vaundy")))
        assertTrue(idx.hasArtist("Ado"))
        assertTrue(idx.hasArtist("vaundy - Topic"))
        assertFalse(idx.hasArtist("King Gnu"))
        assertFalse(idx.hasArtist(""))
    }

    @Test fun indexMatchesSameSongWithDifferentDecoration() {
        val idx = LibraryMatch.Index(listOf(track("Lemon", "米津玄師")))
        assertTrue(idx.hasSong("Lemon (Official Video)", "米津玄師"))
        assertTrue(idx.hasSong("LEMON", "米津玄師 feat. 誰か"))
        assertFalse(idx.hasSong("Lemon", "別の人"))
        assertFalse(idx.hasSong("馬と鹿", "米津玄師"))
        assertFalse(idx.hasSong("(MV)", "米津玄師"))
    }

    @Test fun emptyLibraryMatchesNothing() {
        val idx = LibraryMatch.Index(emptyList())
        assertFalse(idx.hasArtist("Ado"))
        assertFalse(idx.hasSong("唱", "Ado"))
    }
}
