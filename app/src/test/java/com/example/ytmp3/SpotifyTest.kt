package com.example.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTest {
    @Test fun parsesPlaylistLinkWithTrackingParams() {
        val url = "https://open.spotify.com/playlist/37i9dQZF1DX3WdioUzkg8I?si=THJyTy0aTYCveop_KAEnUw&utm_source=copy-link&pi=HJyO23ozSkqVr"
        assertTrue(Spotify.isSpotify(url))
        assertEquals(Spotify.Link(Spotify.Type.PLAYLIST, "37i9dQZF1DX3WdioUzkg8I"), Spotify.parse(url))
    }

    @Test fun parsesTrackAlbumIntlAndUri() {
        assertEquals(Spotify.Link(Spotify.Type.TRACK, "4cOdK2wGLETKBW3PvgPWqT"),
            Spotify.parse("https://open.spotify.com/intl-ja/track/4cOdK2wGLETKBW3PvgPWqT?si=x"))
        assertEquals(Spotify.Link(Spotify.Type.ALBUM, "1A2B3C"), Spotify.parse("見て https://open.spotify.com/album/1A2B3C"))
        assertEquals(Spotify.Link(Spotify.Type.TRACK, "abc123"), Spotify.parse("spotify:track:abc123"))
        assertEquals(Spotify.Link(Spotify.Type.ARTIST, "0du5cEVh5yTK9QJze8zA0C"),
            Spotify.parse("https://open.spotify.com/intl-ja/artist/0du5cEVh5yTK9QJze8zA0C?si=abc"))
    }

    @Test fun findsLinkInSharedText() {
        assertEquals("https://open.spotify.com/track/abc", Spotify.find("この曲いいよ https://open.spotify.com/track/abc"))
        assertFalse(Spotify.isSpotify("https://www.youtube.com/watch?v=abcdefghijk"))
        assertNull(Spotify.parse("https://example.com/track/abc"))
    }

    private fun r(title: String, artist: String, sec: Long) =
        Searcher.Result(Searcher.Kind.SONG, "abcdefghijk", "https://music.youtube.com/watch?v=abcdefghijk", title, artist, sec, null)

    @Test fun scoresExactMatchHighAndWrongSongLow() {
        val s = Spotify.Song("Lemon", "米津玄師", 255_000)
        assertTrue(Spotify.score(s, r("Lemon", "米津玄師", 256)) >= 80)
        assertTrue(Spotify.score(s, r("Lemon (Official Video)", "米津玄師", 270)) >= 40)
        assertTrue(Spotify.score(s, r("馬と鹿", "米津玄師", 255)) < 80)
        // 長さが大きく違う(ライブ版など)は下げる
        assertTrue(Spotify.score(s, r("Lemon", "米津玄師", 400)) < Spotify.score(s, r("Lemon", "米津玄師", 255)))
    }
}
