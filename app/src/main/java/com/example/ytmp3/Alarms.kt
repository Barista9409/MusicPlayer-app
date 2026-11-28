package com.example.ytmp3

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** アラームで再生するもの */
enum class AlarmTarget { LIBRARY, PLAYLIST, TRACK }

data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    /** Calendar.SUNDAY(1)〜SATURDAY(7)。空なら 1 回のみ */
    val days: Set<Int>,
    val enabled: Boolean = true,
    val target: AlarmTarget = AlarmTarget.LIBRARY,
    val targetId: Long = 0,
    val shuffle: Boolean = true,
    /** 徐々に音量を上げる */
    val fadeIn: Boolean = true,
    /** メディア音量(0〜100%) */
    val volume: Int = 60,
    val label: String = "",
    /** スヌーズ中の次回時刻(0 = なし) */
    val snoozeUntil: Long = 0,
) {
    val timeText get() = String.format("%d:%02d", hour, minute)

    fun daysText(): String {
        if (days.isEmpty()) return "1回のみ"
        if (days.size == 7) return "毎日"
        val weekdays = setOf(2, 3, 4, 5, 6)
        if (days == weekdays) return "平日"
        if (days == setOf(1, 7)) return "週末"
        return DAY_ORDER.filter { it in days }.joinToString("・") { DAY_NAMES[it] }
    }

    fun targetText(): Pair<String, String> = when (target) {
        AlarmTarget.LIBRARY -> "ライブラリ全体" to "シャッフル再生"
        AlarmTarget.PLAYLIST -> {
            val p = Repository.playlist(targetId)
            (p?.name ?: "削除されたプレイリスト") to (p?.let { "プレイリスト · ${it.trackIds.size}曲" } ?: "プレイリスト")
        }
        AlarmTarget.TRACK -> {
            val t = Repository.track(targetId)
            (t?.title ?: "削除された曲") to (listOf("曲", t?.artist.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }

    fun targetArt(): String? = when (target) {
        AlarmTarget.TRACK -> Repository.track(targetId)?.thumbPath
        AlarmTarget.PLAYLIST -> Repository.playlist(targetId)?.let { p ->
            Repository.tracksOf(p).firstOrNull { it.thumbPath != null }?.thumbPath
        }
        AlarmTarget.LIBRARY -> null
    }

    /** 再生する曲(見つからない曲は除く) */
    fun tracks(): List<Track> {
        val list = when (target) {
            AlarmTarget.LIBRARY -> Repository.tracks.value
            AlarmTarget.PLAYLIST -> Repository.playlist(targetId)?.let { Repository.tracksOf(it) }.orEmpty()
            AlarmTarget.TRACK -> listOfNotNull(Repository.track(targetId))
        }.filter { Net.canPlayNow(it) }
        // ☁ オンラインの曲も、今ネットから流せるなら入れる(端末にファイルが無い曲だけのプレイリストでも、選んだ曲が鳴るように)。
        // 曲やプレイリストが消えていた時・オフラインで 1 曲も流せない時だけ、端末にある曲で代用
        return list.ifEmpty { Repository.tracks.value.filterNot { Repository.isMissing(it.id) }.shuffled() }
    }

    /** now 以降で次に鳴る時刻(ミリ秒)。無効なら null */
    fun nextFire(now: Long = System.currentTimeMillis()): Long? {
        if (!enabled) return null
        if (snoozeUntil > now) return snoozeUntil
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        for (i in 0..7) {
            if (c.timeInMillis > now && (days.isEmpty() || c.get(Calendar.DAY_OF_WEEK) in days)) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    companion object {
        /** 表示順: 日〜土 */
        val DAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 7)
        val DAY_NAMES = arrayOf("", "日", "月", "火", "水", "木", "金", "土")
    }
}

/** アラームの保存と AlarmManager への登録 */
object Alarms {
    private lateinit var file: File
    private lateinit var app: Context
    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    const val ACTION_FIRE = "com.example.ytmp3.alarm.FIRE"
    const val EXTRA_ID = "alarm_id"

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "alarms.json")
        _alarms.value = load()
    }

    fun get(id: Long) = _alarms.value.firstOrNull { it.id == id }

    fun upsert(a: Alarm) = synchronized(this) {
        val list = _alarms.value
        _alarms.value = if (list.any { it.id == a.id }) list.map { if (it.id == a.id) a else it } else list + a
        save()
        scheduleAll()
    }

    fun delete(id: Long) = synchronized(this) {
        cancel(id)
        _alarms.value = _alarms.value.filterNot { it.id == id }
        save()
        scheduleAll()
    }

    /** 鳴った後の処理: 1 回のみは無効化、スヌーズは解除 */
    fun onFired(id: Long) {
        val a = get(id) ?: return
        upsert(a.copy(enabled = a.enabled && a.days.isNotEmpty(), snoozeUntil = 0))
    }

    fun snooze(id: Long, minutes: Int = 10) {
        val a = get(id) ?: return
        upsert(a.copy(enabled = true, snoozeUntil = System.currentTimeMillis() + minutes * 60_000L))
    }

    /** 次に鳴るアラームと時刻 */
    fun next(): Pair<Alarm, Long>? =
        _alarms.value.mapNotNull { a -> a.nextFire()?.let { a to it } }.minByOrNull { it.second }

    /** 全アラームを AlarmManager に登録し直す */
    fun scheduleAll() {
        val am = app.getSystemService(AlarmManager::class.java)
        for (a in _alarms.value) {
            val at = a.nextFire()
            if (at == null) { cancel(a.id); continue }
            val pi = firePendingIntent(a.id)
            val show = PendingIntent.getActivity(
                app, a.id.toInt(), Intent(app, AlarmsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            try {
                // 時計アプリと同じ「アラーム」扱い(ステータスバーに時計アイコン、Doze 中も正確)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            } catch (e: SecurityException) {
                // 正確なアラームが許可されていない端末
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < 31 || app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun cancel(id: Long) {
        app.getSystemService(AlarmManager::class.java).cancel(firePendingIntent(id))
    }

    private fun firePendingIntent(id: Long) = PendingIntent.getBroadcast(
        app, id.toInt(),
        Intent(app, AlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun load(): List<Alarm> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val d = o.optJSONArray("days")
                Alarm(
                    id = o.getLong("id"), hour = o.getInt("hour"), minute = o.getInt("minute"),
                    days = if (d == null) emptySet() else List(d.length()) { d.getInt(it) }.toSet(),
                    enabled = o.optBoolean("enabled", true),
                    target = runCatching { AlarmTarget.valueOf(o.optString("target")) }.getOrDefault(AlarmTarget.LIBRARY),
                    targetId = o.optLong("targetId"),
                    shuffle = o.optBoolean("shuffle", true),
                    fadeIn = o.optBoolean("fadeIn", true),
                    volume = o.optInt("volume", 60),
                    label = o.optString("label"),
                    snoozeUntil = o.optLong("snoozeUntil"),
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save() {
        val arr = JSONArray()
        _alarms.value.forEach { a ->
            arr.put(
                JSONObject().put("id", a.id).put("hour", a.hour).put("minute", a.minute)
                    .put("days", JSONArray(a.days.toList())).put("enabled", a.enabled)
                    .put("target", a.target.name).put("targetId", a.targetId).put("shuffle", a.shuffle)
                    .put("fadeIn", a.fadeIn).put("volume", a.volume).put("label", a.label)
                    .put("snoozeUntil", a.snoozeUntil)
            )
        }
        val tmp = File(file.parentFile, "alarms.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}

/** 「あと 9時間12分」 */
fun untilText(at: Long, now: Long = System.currentTimeMillis()): String {
    val m = ((at - now) / 60_000).coerceAtLeast(0)
    val d = m / (60 * 24)
    val h = m / 60 % 24
    val mm = m % 60
    return "あと " + buildString {
        if (d > 0) append("${d}日")
        if (h > 0) append("${h}時間")
        if (d == 0L) append("${mm}分")
    }.ifEmpty { "1分未満" }
}

/** 「今日 7:00」「明日 7:00」「11/3(月) 7:00」 */
fun whenText(at: Long, now: Long = System.currentTimeMillis()): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val time = String.format("%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    val dayDiff = run {
        val a = Calendar.getInstance().apply { timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val b = (today.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        ((a.timeInMillis - b.timeInMillis) / 86_400_000L).toInt()
    }
    return when (dayDiff) {
        0 -> "今日 $time"
        1 -> "明日 $time"
        else -> "${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)}(${Alarm.DAY_NAMES[c.get(Calendar.DAY_OF_WEEK)]}) $time"
    }
}
