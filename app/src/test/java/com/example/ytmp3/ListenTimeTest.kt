package com.example.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTimeTest {
    @Test fun addsPerDayAndIgnoresHugeGaps() {
        val day = 86_400_000L
        val base = 20_000L * day  // ある日の 0 時(UTC)
        val before = ListenTime.sum(base - 10 * day, base + 10 * day)
        ListenTime.add(2_000, base + 3_600_000)
        ListenTime.add(2_000, base + 3_700_000)
        // 止まっていた間の大きな差(1 分超)は数えない
        ListenTime.add(5 * 60_000, base + 3_800_000)
        assertEquals(before + 4_000, ListenTime.sum(base - 10 * day, base + 10 * day))
        // 範囲の外の日は数えない
        assertEquals(0L, ListenTime.sum(base + 100 * day, base + 101 * day))
    }
}
