package com.example.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTest {
    private val ids = listOf("dQw4w9WgXcQ", "abcdefghijk", "A_b-C1d2E3f")

    @Test fun roundTripsThroughLinkAndMessage() {
        val p = Share.Payload(Share.Kind.PLAYLIST, "ドライブ用 🚗", ids)
        val link = Share.link(p)
        assertTrue(link.startsWith(Share.PAGE + "?openExternalBrowser=1#1p."))
        assertEquals(p, Share.decode(link))
        // LINE の文ごと貼られても読める
        assertEquals(p, Share.decode(Share.message(p)))
        assertTrue(Share.isShare(Share.message(p)))
    }

    @Test fun readsOldLinksWithoutQuery() {
        val p = Share.Payload(Share.Kind.SONG, "曲", ids.take(1))
        // 前の版で作ったリンク(? なし)も読める
        assertEquals(p, Share.decode(Share.PAGE + "#" + Share.encode(p)))
    }

    @Test fun readsAppSchemeFromSharePage() {
        val p = Share.Payload(Share.Kind.ALBUM, "Album", ids.take(1))
        assertEquals(p, Share.decode("musicplayer://share/" + Share.encode(p)))
    }

    @Test fun lengthIsAbout11CharsPerSong() {
        val many = (0 until 100).map { "%011d".format(it) }
        val link = Share.link(Share.Payload(Share.Kind.PLAYLIST, "x", many))
        assertTrue(link.length < 1200)
    }

    @Test fun rejectsBrokenLinks() {
        assertNull(Share.decode(Share.PAGE + "#1p.eA.abc"))  // ID が 11 文字で割り切れない
        assertNull(Share.decode("https://example.com/#1p.eA.dQw4w9WgXcQ"))
        assertFalse(Share.isShare("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }
}
