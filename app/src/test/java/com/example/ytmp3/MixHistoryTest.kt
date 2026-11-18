package com.example.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixHistoryTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test fun blocksByTimeWindowAndRecentCount() {
        val now = 1_000_000_000_000L
        // 2 日前に流れた曲(直近 100 曲からは押し出す)
        MixHistory.played(listOf("v:old"), now - 2 * day)
        repeat(MixHistory.LAST_COUNT) { MixHistory.played(listOf("v:filler$it"), now - 3 * day) }
        val h = MixHistory.snapshot()
        // 似た曲(3 日)は外す・新曲(1 日)とよく聴く曲(12 時間)は戻す
        assertTrue(h.blocked(listOf("v:old"), MixHistory.SIMILAR_MS, now))
        assertFalse(h.blocked(listOf("v:old"), MixHistory.NEW_MS, now))
        assertFalse(h.blocked(listOf("v:old"), MixHistory.FAV_MS, now))
        // 直近 100 曲に入っている曲は、期間に関係なく外す
        assertTrue(h.blocked(listOf("v:filler5"), 0L, now))
        // 流れていない曲は外さない
        assertFalse(h.blocked(listOf("v:never"), MixHistory.SIMILAR_MS, now))
        assertEquals(now - 2 * day, h.lastPlayed(listOf("v:old", "v:never")))
        // 直近 n 曲: 最後の 30 曲には filler70〜99 が入る
        assertTrue(h.inLast(listOf("v:filler99"), 30))
        assertTrue(h.inLast(listOf("v:filler70"), 30))
        assertFalse(h.inLast(listOf("v:filler69"), 30))
    }
}
