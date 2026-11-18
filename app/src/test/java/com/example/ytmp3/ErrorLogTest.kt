package com.example.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ErrorLogTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12) = Calendar.getInstance().apply {
        set(y, mo - 1, d, h, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun readsTimeInThisYear() {
        assertEquals(at(2026, 11, 1, 9), ErrorLog.timeOf("11/01 09:00:00 [停止] x", at(2026, 11, 15)))
    }

    @Test fun lastYearWhenInFuture() {
        // 1 月に見た 12 月の記録は去年
        assertEquals(at(2025, 12, 30, 9), ErrorLog.timeOf("12/30 09:00:00 [停止] x", at(2026, 1, 3)))
    }

    @Test fun noTimeForOddLines() {
        assertNull(ErrorLog.timeOf("おかしな行", at(2026, 11, 15)))
    }

    @Test fun explainsInJapanese() {
        val e = java.io.IOException("Response code: 403")
        assertEquals("サーバーの返事 403(断られた)", ErrorLog.explain(e))
        assertEquals("途中で取りやめた", ErrorLog.explain(InterruptedException()))
        assertEquals("時間切れ(通信が遅い)", ErrorLog.explain(java.net.SocketTimeoutException("x")))
        assertEquals("再生 URL が取れない", ErrorLog.explain(IllegalStateException("再生 URL が取れない")))
        assertEquals("読み込みを断られた", ErrorLog.playbackError(2004, "ERROR_CODE_IO_BAD_HTTP_STATUS"))
    }

    @Test fun routineLines() {
        assertTrue(ErrorLog.isRoutine("11/14 13:25:09 [アプリ更新] 115.9 MB を 16.4 秒(7.1 MB/秒・4 本同時)"))
        assertTrue(ErrorLog.isRoutine("11/14 15:54:09 [停止] イヤホン・Bluetooth が外れたので停止"))
        assertFalse(ErrorLog.isRoutine("11/14 10:30:11 [停止] ほかのアプリが音を使い始めたので停止(電話・動画など)"))
        assertFalse(ErrorLog.isRoutine("11/14 13:25:09 [アプリ更新] ダウンロードに失敗"))
    }
}
