package com.example.ytmp3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionChannelTest {
    @Test fun stableIsDotZero() {
        assertTrue(AppUpdater.isStableName("10.27.0"))
        assertTrue(AppUpdater.isStableName("11.0.0"))
        assertFalse(AppUpdater.isStableName("10.27.1"))
        assertFalse(AppUpdater.isStableName("10.26"))
        assertFalse(AppUpdater.isStableName("10.27.10"))
    }
}
