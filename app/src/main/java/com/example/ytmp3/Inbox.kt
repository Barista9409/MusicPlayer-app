package com.example.ytmp3

import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 通知の履歴(設定 → お知らせ で見て消せる)。スマホの中(inbox.json)だけに保存。
 * 通知を出す時に add() で一緒に記録する。
 */
object Inbox {
    data class Entry(val id: Long, val time: Long, val title: String, val text: String, val notifId: Int)

    val list = MutableStateFlow<List<Entry>>(emptyList())
    /** まだ見ていないお知らせの数(設定タブとベルのバッジ用) */
    val unread = MutableStateFlow(0)
    private var seenAt = 0L
    private var loaded = false
    private const val MAX = 100

    private fun file(c: Context) = File(c.applicationContext.filesDir, "inbox.json")

    @Synchronized
    private fun ensure(c: Context) {
        if (loaded) return
        loaded = true
        seenAt = prefs(c).getLong("inbox_seen", 0L)
        val a = runCatching { JSONArray(file(c).readText()) }.getOrNull() ?: return
        list.value = (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Entry(o.optLong("id"), o.optLong("time"), o.optString("title"), o.optString("text"), o.optInt("notif"))
        }
        updateUnread()
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun updateUnread() { unread.value = list.value.count { it.time > seenAt } }

    /** お知らせを開いた時: 全部見たことにする */
    @Synchronized
    fun markSeen(c: Context) {
        ensure(c)
        seenAt = System.currentTimeMillis()
        prefs(c).edit().putLong("inbox_seen", seenAt).apply()
        updateUnread()
    }

    @Synchronized
    private fun save(c: Context) {
        updateUnread()
        val a = JSONArray()
        list.value.forEach {
            a.put(JSONObject().put("id", it.id).put("time", it.time).put("title", it.title).put("text", it.text).put("notif", it.notifId))
        }
        runCatching {
            val f = file(c)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(a.toString()); tmp.renameTo(f)
        }
    }

    fun load(c: Context) = ensure(c)

    @Synchronized
    fun add(c: Context, title: String, text: String, notifId: Int) {
        ensure(c)
        val now = System.currentTimeMillis()
        list.value = (listOf(Entry(now, now, title, text, notifId)) + list.value).take(MAX)
        save(c)
    }

    @Synchronized
    fun remove(c: Context, e: Entry) {
        ensure(c)
        list.value = list.value.filterNot { it.id == e.id }
        // 同じ通知がまだ出ていれば一緒に消す(同じ番号の新しい記録が残っている時は消さない)
        if (list.value.none { it.notifId == e.notifId }) cancel(c, e.notifId)
        save(c)
    }

    @Synchronized
    fun clear(c: Context) {
        ensure(c)
        list.value.map { it.notifId }.distinct().forEach { cancel(c, it) }
        list.value = emptyList()
        save(c)
    }

    private fun cancel(c: Context, notifId: Int) {
        runCatching { c.getSystemService(NotificationManager::class.java).cancel(notifId) }
    }
}
