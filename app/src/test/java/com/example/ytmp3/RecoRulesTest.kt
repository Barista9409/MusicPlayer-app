package com.example.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoRulesTest {
    @Test fun radioAppendsOnlyWhenOnAndNotRepeating() {
        assertTrue(RecoRules.canAppend(fromCurrent = false, radioOn = true, repeatOff = true))
        assertFalse(RecoRules.canAppend(fromCurrent = false, radioOn = false, repeatOff = true))
        assertFalse(RecoRules.canAppend(fromCurrent = false, radioOn = true, repeatOff = false))
        // 「この曲から作り直す」はいつでも
        assertTrue(RecoRules.canAppend(fromCurrent = true, radioOn = false, repeatOff = false))
    }

    @Test fun appendsWhenOneOrNoneLeft() {
        assertFalse(RecoRules.shouldAppendNow(2))
        assertTrue(RecoRules.shouldAppendNow(1))
        assertTrue(RecoRules.shouldAppendNow(0))
    }

    @Test fun remainingInOrder() {
        assertEquals(4, RecoRules.remainingInOrder(5, 0))
        assertEquals(1, RecoRules.remainingInOrder(5, 3))
        assertEquals(0, RecoRules.remainingInOrder(5, 4))
        assertEquals(0, RecoRules.remainingInOrder(0, 0))
        assertEquals(0, RecoRules.remainingInOrder(3, -1))
    }

    @Test fun capPerArtistKeepsOrderAndCountsMainArtist() {
        val list = listOf("A" to "1", "A feat. B" to "2", "a" to "3", "B" to "4", "C, A" to "5")
        val out = RecoRules.capPerArtist(list, 2) { it.first }.map { it.second }
        // A の曲(feat. を含む)は 2 曲まで。「C, A」はメインが C
        assertEquals(listOf("1", "2", "4", "5"), out)
    }

    @Test fun mainArtistDropsCollaborators() {
        assertEquals("Ado", Radio.mainArtist("Ado, Vaundy"))
        assertEquals("YOASOBI", Radio.mainArtist("YOASOBI feat. 誰か"))
        assertEquals("solo", Radio.mainArtist("solo"))
    }
}
