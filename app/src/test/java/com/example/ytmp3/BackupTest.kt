package com.example.ytmp3

import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    /** 機種変更で消えると困るデータが、バックアップに入っていること */
    @Test fun backupIncludesUserData() {
        listOf("history.json", "playlists.json", "liked.json", "online.json", "playlog.json",
            "alarms.json", "no_good.json").forEach { n ->
            assertTrue("$n がバックアップに入っていない", n in Backup.FILES)
        }
    }
}
