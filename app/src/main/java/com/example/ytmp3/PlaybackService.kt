package com.example.ytmp3

import android.app.PendingIntent
import android.content.Intent
import kotlinx.coroutines.launch
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService

/** バックグラウンド再生(通知・ロック画面の操作に対応) */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** アラーム開始前のメディア音量(止めたら戻す) */
    private var savedStreamVolume = -1
    /** テスト再生中(スヌーズしても予定を作らない) */
    private var testing = false

    companion object {
        /** URL を取っている最中の曲は、止まったと数えるまでこれだけ余分に待つ(遅い方法でも取り終えられるように) */
        private const val FETCH_WAIT_MS = 45_000L
        /** 再生中・再生しようとしている(読み込み中も含む)。裏の作業(曲調の分析の通信など)が再生と取り合わないように見る */
        @Volatile var active = false
            private set
        private const val QUEUE_PREFS = "playback_queue"
        const val ACTION_AUTO_PLAY = "com.example.ytmp3.AUTO_PLAY"
        const val ACTION_APPEND_SIMILAR = "com.example.ytmp3.APPEND_SIMILAR"
        const val ACTION_REBUILD_RADIO = "com.example.ytmp3.REBUILD_RADIO"
        private const val AUTO_CHANNEL = "auto_play"
        private const val AUTO_NOTIF_ID = 77
        const val ACTION_ALARM_START = "com.example.ytmp3.alarm.START"
        const val ACTION_ALARM_STOP = "com.example.ytmp3.alarm.STOP"
        const val ACTION_ALARM_SNOOZE = "com.example.ytmp3.alarm.SNOOZE"
        const val ALARM_CHANNEL = "alarm"
        /** テスト再生(予定を変えない) */
        const val EXTRA_TEST = "test"
        const val ALARM_NOTIF_ID = 2001

        /** テスト再生中か(アラーム画面の表示用) */
        @Volatile var isTesting = false

        /** 鳴っているアラームの id(鳴っていなければ null) */
        val ringing = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    }

    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    /** 動画ごとの取り直し回数 */
    private val videoRetries = HashMap<String, Int>()
    private var playTicker: Runnable? = null
    private var fadeTicker: Runnable? = null
    private var fadeInPending = false
    private var listenedMs = 0L
    private var lastTick = 0L
    private var counted = false

    override fun onCreate() {
        super.onCreate()
        // 試聴(ストリーミング)の URL は取得時と同じ名乗りで読み込む
        // URL を取った方法(c=ANDROID など)に合わせて User-Agent を付ける
        val http = androidx.media3.datasource.ResolvingDataSource.Factory(
            androidx.media3.datasource.DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        ) { spec ->
            val ua = InnerTube.userAgentForStream(spec.uri.toString())
            if (ua != null) spec.withAdditionalHeaders(mapOf("User-Agent" to ua)) else spec
        }
        // YouTube の配信は 10MB ずつ区切って取る(丸ごと取ると途中で止まるため)
        // 検索の試聴キュー(ytstream://v/<動画ID>)は、その曲の番が来た時に本物の URL を取る
        val resolving = androidx.media3.datasource.ResolvingDataSource.Factory(ChunkedDataSource.Factory(http)) { spec ->
            if (spec.uri.scheme != PlayerConnection.STREAM_SCHEME) spec
            else {
                val orig = spec.uri.lastPathSegment ?: throw java.io.IOException("動画 ID がありません")
                // 聴けない曲(Premium 限定など)で別の音源が見つかっていれば、最初からそちらを流す
                val vid = if (Searcher.isUnplayable(orig)) AltSources.knownAlt(orig) ?: orig else orig
                val real = try { Searcher.resolveBlocking(vid) } catch (e: Exception) {
                    // 聴けない曲は取り直しても無駄なので、記録も 1 回だけ(下の「取り直さない」決まりと組み合わせ)
                    if (e !is Searcher.Unplayable) ErrorLog.add("URL取得", "再生 URL を取得できない ($vid)", e)
                    throw java.io.IOException("再生URLを取得できません: ${e.message}", e)
                }
                spec.withUri(android.net.Uri.parse(real))
            }
        }
        // 一度聴いた試聴曲は一時保存して、2 回目からは通信なしで流す
        val dataSources = androidx.media3.datasource.DefaultDataSource.Factory(this, StreamCache.factory(this, resolving))
        val player = ExoPlayer.Builder(this, AudioRenderers(this))
            // 画面が消えても CPU と Wi-Fi を止めない(省電力で音の処理・読み込みが遅れて途切れないように)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(VideoMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSources)
                .setLoadErrorHandlingPolicy(NoRetryForUnplayable())))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            // ネットの動画・試聴が途切れにくいよう多めに先読み(最大 2 分)
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 120_000, 1_500, 3_000)
                    .build()
            )
            .build()
        // 音が途切れた原因を探すための記録: 通知などで一時停止した時、音の処理が間に合わなかった時
        player.addListener(object : Player.Listener {
            private var pausedAt = 0L
            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (reason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                    pausedAt = now
                    ErrorLog.add("一時停止", "ほかのアプリの音(通知・音声など)で一時停止 · ${describe(player)}")
                } else if (reason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && pausedAt > 0) {
                    ErrorLog.add("再開", "一時停止から再開(${String.format("%.1f", (now - pausedAt) / 1000.0)} 秒止まっていた)")
                    pausedAt = 0
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                    ErrorLog.add("停止", "ほかのアプリが音を使い始めたので停止(電話・動画など)")
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                    ErrorLog.add("停止", "イヤホン・Bluetooth が外れたので停止")
            }
        })
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            private var lastLog = 0L
            override fun onAudioUnderrun(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                                         bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastLog < 5_000) return  // 続けて起きた時は 1 回だけ
                lastLog = now
                val dl = DownloadManager.jobs.value.count { it.status == JobStatus.RUNNING }
                ErrorLog.add("音の途切れ", "音の処理が間に合わなかった(${elapsedSinceLastFeedMs}ms 遅れ)" +
                    (if (dl > 0) " · ダウンロード中 $dl 件" else "") + " · ${describe(player)}")
            }
        })
        // 再生中かどうか(裏の作業が再生と取り合わないように)
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                active = p.playWhenReady && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
            }
        })
        // すぐ飛ばした曲を記録(あなたのミックス・ラジオで出にくくする): 2〜30 秒で ⏭ で次へ進んだ時だけ
        // (2 秒未満は、読み込めずに自動で次へ進んだ時と区別できないので数えない)
        player.addListener(object : Player.Listener {
            /** 今の曲の長さ(次へ進んだ時に、どこまで聴いたかの割合を出すため) */
            private var durIndex = -1
            private var durMs = 0L
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && player.duration > 0) { durIndex = player.currentMediaItemIndex; durMs = player.duration }
            }
            /** 前の曲(最後まで流れて次へ進んだかを見る) */
            private var prevItem: androidx.media3.common.MediaItem? = null
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // 最後まで流れて自動で次へ: 聴いたものとして、途中で飛ばした記録を消す
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) prevItem?.let {
                    runCatching { SkipLog.clearPartial(it) }
                    // 聴き終えた曲を、通信なしで曲調の分析にかけておく(使うほどミックスの分け方が正確に)
                    runCatching { MoodMix.learn(it) }
                }
                prevItem = mediaItem
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
                // ミックス・ラジオ・検索で自動で並んだ曲を、8 割まで聴かずに次へ: 曲調の種に数えない
                val auto = PlayLog.current?.kind.let { it == null || it == PlayLog.Kind.STREAM } || oldPosition.mediaItem?.mediaId?.toLongOrNull() == null
                val tl0 = player.currentTimeline
                val forward = !tl0.isEmpty && tl0.getNextWindowIndex(oldPosition.mediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled) == newPosition.mediaItemIndex
                if (forward && auto && oldPosition.mediaItemIndex == durIndex && durMs > 0 && oldPosition.positionMs < durMs * 0.8)
                    oldPosition.mediaItem?.let { runCatching { SkipLog.recordPartial(it) } }
                // 8 割以上聴いてから次へ: 聴いたものとして、途中で飛ばした記録を消す
                if (forward && oldPosition.mediaItemIndex == durIndex && durMs > 0 && oldPosition.positionMs >= durMs * 0.8)
                    oldPosition.mediaItem?.let { runCatching { SkipLog.clearPartial(it) }; runCatching { MoodMix.learn(it) } }
                if (oldPosition.positionMs !in 2_000L until 30_000L) return
                // 戻った時(⏮)は数えない
                val tl = player.currentTimeline
                if (tl.isEmpty || tl.getNextWindowIndex(oldPosition.mediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled) != newPosition.mediaItemIndex) return
                oldPosition.mediaItem?.let { runCatching { SkipLog.record(it) } }
            }
        })
        // 再生の始まりが遅かった曲を記録(原因を探すため): 曲が変わってから音が出るまで 3 秒以上
        player.addListener(object : Player.Listener {
            private var waitFrom = 0L
            private fun start() { waitFrom = android.os.SystemClock.elapsedRealtime(); Searcher.lastStream = "" }
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                if (player.playWhenReady) start()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && player.playbackState != Player.STATE_READY) start()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_READY || waitFrom == 0L) return
                val took = android.os.SystemClock.elapsedRealtime() - waitFrom
                waitFrom = 0L
                if (took < 3_000 || !player.playWhenReady) return
                val how = Searcher.lastStream.ifBlank { "この間の URL 取得なし(ファイル・一時保存・先読み済み)" }
                ErrorLog.add("再生が遅い", "音が出るまで ${String.format("%.1f", took / 1000.0)} 秒 · $how · ${describe(player)}")
            }
        })
        // 再生中にファイルが見つからない等のエラーが出たら次の曲へ
        player.addListener(object : Player.Listener {
            private var consecutive = 0
            override fun onPlayerError(error: PlaybackException) {
                ErrorLog.add("再生エラー", "${ErrorLog.playbackError(error.errorCode, error.errorCodeName)} · ${describe(player)}", error)
                // ライブラリの曲を動画(保存した動画・ネットの MV)で流していて失敗: いつもの音声ファイルに戻して続ける
                val curLib = player.currentMediaItem
                if (VideoMediaSourceFactory.isVideo(curLib) && curLib != null) {
                    val t = curLib.mediaId.toLongOrNull()?.let { Repository.track(it) }
                    if (t != null && t.uri != t.videoUri && !Repository.isMissing(t.id)) {
                        val idx = player.currentMediaItemIndex
                        val pos = player.currentPosition
                        player.replaceMediaItem(idx, t.toMediaItem())
                        player.seekTo(idx, pos)
                        player.prepare()
                        player.play()
                        return
                    }
                }
                // 動画の読み込みが止まった: URL を取り直して続きから(3 回まで)。だめなら音声だけで続ける
                val cur = player.currentMediaItem
                if (VideoMediaSourceFactory.isVideo(cur)) {
                    val v = StreamDownload.videoId(cur)
                    if (v != null && cur != null) {
                        val idx = player.currentMediaItemIndex
                        val pos = player.currentPosition
                        val n = (videoRetries[v] ?: 0) + 1
                        videoRetries[v] = n
                        if (n <= 3) {
                            serviceScope.launch {
                                val s = runCatching {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        Searcher.videoSources("https://www.youtube.com/watch?v=$v")
                                    }
                                }.getOrNull()
                                if (idx >= player.mediaItemCount || StreamDownload.videoId(player.getMediaItemAt(idx)) != v) return@launch
                                val m = cur.mediaMetadata
                                val next = if (s != null) PlayerConnection.videoItem(
                                    Searcher.Result(Searcher.Kind.VIDEO, v, "https://www.youtube.com/watch?v=$v",
                                        m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), 0, m.artworkUri?.toString()), s)
                                else PlayerConnection.audioOnly(cur, v)
                                player.replaceMediaItem(idx, next)
                                player.seekTo(idx, pos)
                                player.prepare()
                                player.play()
                            }
                        } else {
                            player.replaceMediaItem(idx, PlayerConnection.audioOnly(cur, v))
                            player.seekTo(idx, pos)
                            player.prepare()
                            player.play()
                        }
                        return
                    }
                }
                // ライブラリの曲のファイルが読めない(壊れている等): ネットから流しつつ、裏でダウンロードし直す
                val curItem = player.currentMediaItem
                val broken = curItem?.mediaId?.toLongOrNull()?.let { Repository.track(it) }
                val brokenVid = broken?.sourceUrl?.let { youtubeVideoId(it) }
                if (broken != null && brokenVid != null && brokenFiles.add(broken.id)) {
                    val idx = player.currentMediaItemIndex
                    player.replaceMediaItem(idx, PlayerConnection.streamItem(Searcher.Result(Searcher.Kind.SONG, brokenVid,
                        "https://music.youtube.com/watch?v=$brokenVid", broken.title, broken.artist, 0, null)).buildUpon()
                        .setMediaMetadata(curItem.mediaMetadata).build())
                    player.seekTo(idx, 0)
                    player.prepare()
                    player.play()
                    android.widget.Toast.makeText(this@PlaybackService,
                        "「${broken.title}」を読めないため、ネットから再生します", android.widget.Toast.LENGTH_LONG).show()
                    runCatching { DownloadManager.redownload(this@PlaybackService, listOf(broken)) }
                    return
                }
                // 試聴の曲は 1 度だけ yt-dlp で取り直す(一時保存が壊れているかもしれないので消してから)
                val vid = StreamDownload.videoId(player.currentMediaItem)
                if (vid != null) runCatching { StreamCache.get(this@PlaybackService).removeResource("yt:$vid") }
                if (vid != null && Searcher.markDirectFailed(vid)) {
                    player.prepare()
                    player.play()
                    return
                }
                // Premium 限定などの聴けない曲: 取り直さずに、すぐ同じ曲の別の音源を探す(見つからなければ次の曲へ)
                // 取り直してもだめ(年齢確認が要る MV など): 同じ曲の別の動画に差し替えて続ける
                if (vid != null && tryAlternative(player, vid)) return
                consecutive++
                android.widget.Toast.makeText(this@PlaybackService,
                    "「${player.currentMediaItem?.mediaMetadata?.title ?: ""}」を再生できませんでした", android.widget.Toast.LENGTH_LONG).show()
                if (player.hasNextMediaItem() && consecutive < player.mediaItemCount) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) consecutive = 0
            }
            // 読み込み中のまま止まる(試聴の URL が取れない等)のを見張る: 20 秒動かなければ取り直し、それでもだめなら次へ
            private var stuckCheck: Runnable? = null
            private val stuckTries = HashMap<String, Int>()
            override fun onPlaybackStateChanged(state: Int) {
                stuckCheck?.let { tickHandler.removeCallbacks(it) }
                if (state != Player.STATE_BUFFERING || !player.playWhenReady || !AutoSkip.isOn(this@PlaybackService)) return
                val id = player.currentMediaItem?.mediaId ?: return
                val vid = StreamDownload.videoId(player.currentMediaItem)
                val pos = player.currentPosition
                // URL を取りに行く時の動画 ID(☁ の曲なども、仮の URL から読む)
                val fetchVid = vid ?: player.currentMediaItem?.localConfiguration?.uri
                    ?.takeIf { it.scheme == PlayerConnection.STREAM_SCHEME }?.lastPathSegment
                var waited = 0L
                lateinit var r: Runnable
                r = Runnable {
                    if (player.playbackState != Player.STATE_BUFFERING || player.currentMediaItem?.mediaId != id) return@Runnable
                    if (kotlin.math.abs(player.currentPosition - pos) > 1500) return@Runnable
                    // まだ URL を取っている最中なら、止まったとは数えずに待つ(遅い方法は 20 秒ほどかかる。取りやめると最初からになる)
                    if (Searcher.isFetching(fetchVid) && waited < FETCH_WAIT_MS) {
                        waited += 5_000
                        tickHandler.postDelayed(r, 5_000)
                        return@Runnable
                    }
                    val n = (stuckTries[id] ?: 0) + 1
                    stuckTries[id] = n
                    ErrorLog.add("読み込み待ち", "15 秒動かない(${n} 回目) · ${describe(player)}")
                    if (vid == null) { skipStuck(player, "止まってしまった"); return@Runnable }
                    runCatching { StreamCache.get(this@PlaybackService).removeResource("yt:$vid") }
                    if (n == 1 && !Searcher.isUnplayable(vid)) {
                        // URL を取り直して、同じ位置から(取り直しの 2 回目からは yt-dlp)
                        Searcher.markDirectFailed(vid)
                        player.seekTo(player.currentMediaItemIndex, pos)
                        player.prepare()
                        player.play()
                    } else if (!tryAlternative(player, vid)) {
                        skipStuck(player, "読み込めなかった")
                    }
                }
                stuckCheck = r
                tickHandler.postDelayed(r, 15_000)
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // あなたのミックスで流れた曲を覚える(次にミックスを作る時に、最近流れた曲を外すため。飛ばした曲も含む)
                if (mediaItem != null && DailyMix.activeIndex() != null) runCatching { MixHistory.played(SkipLog.keysOf(mediaItem)) }
                // 自分で曲を選んだ時は、失敗の数え直し(前の失敗で次へ進めなくならないように)
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) consecutive = 0
                // 再生中で後回しにしていたタグの書き込み
                TagWriter.playingId = mediaItem?.mediaId
                remember(mediaItem)
                serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { TagWriter.flushPending(this@PlaybackService) } }
                // 高画質のジャケットが無ければ取ってきて差し替える(今の曲と次の曲)
                upgradeArt(player, player.currentMediaItemIndex)
                upgradeArt(player, player.nextMediaItemIndex)
                updateButtons()
                // キューの残りが 1 曲以下になったら、似た曲を後ろに足しておく(止まらずに流れ続ける。早めに足して間に合うように)
                // あなたのミックスは残り 10 曲で続きを足す(終わらないように)
                val left = remaining(player)
                if (RecoRules.shouldAppendNow(left) || (DailyMix.activeIndex() != null && left <= 10)) appendSimilar(player)
                // この先 3 曲のネットの曲の URL を先に取っておく(⏭ を続けて押しても、シャッフルでもすぐ鳴るように)
                warmAhead(player, 3)
            }
        })
        // ウィジェット更新と、再生キューの保存(アプリ終了後もウィジェットから再開できるように)
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                // ウィジェットは表示が変わる出来事の時だけ(音量のフェードなどでは描き直さない)
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                    )
                ) MusicWidget.push(this@PlaybackService, p)
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED,
                    )
                ) saveQueue(p)
            }
        })
        // 音量をそろえる(音声セッションに掛ける)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                VolumeLeveler.attach(this@PlaybackService, audioSessionId)
                SoundSettings.attach(this@PlaybackService, audioSessionId)
            }
        })
        VolumeLeveler.attach(this, player.audioSessionId)
        SoundSettings.attach(this, player.audioSessionId)
        // 曲間のフェード: 曲の終わりを小さく、自然に次の曲へ進んだ時は始まりを大きく
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                fadeInPending = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            }
        })
        fadeTicker = object : Runnable {
            override fun run() {
                val st = SleepTimer.state.value
                // アラーム中・おやすみタイマー(時間指定)のフェード中は、音量をそちらに任せる
                if (ringing.value == null && st !is SleepTimer.State.At) {
                    val sec = SoundSettings.fadeSec(this@PlaybackService)
                    val dur = player.duration
                    val pos = player.currentPosition
                    val left = if (dur > 0) dur - pos else Long.MAX_VALUE
                    var v = 1f
                    if (player.isPlaying && sec > 0) {
                        val fade = sec * 1000L
                        if (player.hasNextMediaItem() && dur > fade * 3 && left < fade) v = minOf(v, left.toFloat() / fade)
                        if (fadeInPending && pos < fade) v = minOf(v, pos.toFloat() / fade)
                        if (pos >= fade) fadeInPending = false
                    }
                    // おやすみタイマー「この曲の終わりで停止」: 最後の 10 秒を小さく
                    if (player.isPlaying && st == SleepTimer.State.EndOfTrack && left < 10_000L) v = minOf(v, left / 10_000f)
                    v = v.coerceIn(0.03f, 1f)
                    if (kotlin.math.abs(player.volume - v) > 0.01f) player.volume = v
                }
                // 止まっている間はゆっくり(電池のため)。再生が始まったらすぐ元の速さに戻す(下の onIsPlayingChanged)
                tickHandler.postDelayed(this, if (player.isPlaying) 100 else 1000)
            }
        }
        tickHandler.postDelayed(fadeTicker!!, 100)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) fadeTicker?.let { tickHandler.removeCallbacks(it); tickHandler.post(it) }
            }
        })
        // 再生の記録: 30 秒以上 または 曲の半分以上 聴いたら 1 回
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                listenedMs = 0; counted = false
            }
        })
        playTicker = object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.elapsedRealtime()
                // 実際に聴いた時間(聴いた記録の「聴いた時間」)
                if (player.isPlaying) ListenTime.add(now - lastTick)
                if (player.isPlaying && !counted) {
                    listenedMs += now - lastTick
                    val dur = player.duration
                    val need = if (dur > 0) minOf(30_000L, dur / 2) else 30_000L
                    if (listenedMs >= need) {
                        counted = true
                        player.currentMediaItem?.let { item -> runCatching { PlayLog.onPlayed(item) } }
                    }
                }
                // 「再生中」なのに 12 秒位置が進まない: 音が止まっているので次へ
                // (通知音・音声アシスタント・ナビの案内などで一時的に止められている間は数えない = isPlaying が false)
                if (player.isPlaying && AutoSkip.isOn(this@PlaybackService)) {
                    val p = player.currentPosition
                    if (p != stallPos) { stallPos = p; stallSince = now }
                    else if (now - stallSince > 12_000) {
                        stallSince = now
                        ErrorLog.add("停止", "再生中なのに 12 秒進まない · ${describe(player)}")
                        skipStuck(player, "止まってしまった")
                    }
                } else { stallPos = -1L; stallSince = now }
                lastTick = now
                tickHandler.postDelayed(this, 2000)
            }
        }
        lastTick = android.os.SystemClock.elapsedRealtime()
        tickHandler.postDelayed(playTicker!!, 2000)
        restoreQueue(player)
        MusicWidget.push(this, player)
        SleepTimer.attach(player)

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, NowPlayingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 車の画面(Android Auto)からも選べるライブラリ付きのセッション
        session = MediaLibrarySession.Builder(this, player, CarLibrary({ savedQueue() }) { cmd -> onCustom(cmd) })
            .setSessionActivity(open)
            .build()
        updateButtons()
        // アプリで ♡ を切り替えた時も通知のボタンに反映
        serviceScope.launch { Liked.list.collect { updateButtons() } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        // 設定「アプリを終了しても再生を続ける」がオフなら、止めて終わる(アラームが鳴っている時は除く)
        if (p != null && p.playWhenReady && !BackgroundPlay.isOn(this) && ringing.value == null) {
            p.pause()
            stopSelf()
            return
        }
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    @Volatile private var appending = false
    /** 読めなかったライブラリの曲(同じ曲で何度も切り替えないように) */
    private val brokenFiles = HashSet<Long>()

    /** 最近流した曲(動画 ID と「曲名|アーティスト」)。似た曲を足す時に同じ曲を避ける */
    private val heard = LinkedHashSet<String>()
    /** これまでに種にした曲(同じ曲から何度もラジオを作らない) */
    private val usedSeeds = LinkedHashSet<String>()

    private fun songKey(title: String, artist: String): String {
        val t = title.lowercase()
            .replace(Regex("[(（\\[【].*?[)）\\]】]"), "")
            .replace(Regex("\\s+-\\s+.*$"), "")
            .replace(Regex("[\\s・　]"), "")
        return "k:$t|" + Radio.mainArtist(artist).lowercase().replace(" ", "")
    }

    private fun keysOf(item: androidx.media3.common.MediaItem): List<String> {
        val track = item.mediaId.toLongOrNull()?.let { Repository.track(it) }
        val vid = StreamDownload.videoId(item) ?: track?.sourceUrl?.let { youtubeVideoId(it) }
        val title = track?.title ?: item.mediaMetadata.title?.toString().orEmpty()
        val artist = track?.artist ?: item.mediaMetadata.artist?.toString().orEmpty()
        return listOfNotNull(vid?.let { "v:$it" }, title.takeIf { it.isNotBlank() }?.let { songKey(it, artist) })
    }

    private fun remember(item: androidx.media3.common.MediaItem?) {
        item ?: return
        synchronized(heard) {
            keysOf(item).forEach { heard.remove(it); heard.add(it) }
            while (heard.size > 400) heard.remove(heard.first())
        }
    }

    /** 「キューが終わったら似た曲を流す」: 最近の曲に似た曲(ラジオ)を後ろに足す。聴いたばかりの曲は避ける */
    /** 止まった曲を飛ばして次へ */
    /** エラー履歴用: 今の曲と再生の様子を 1 行で */
    private fun describe(p: Player): String {
        val item = p.currentMediaItem
        val kind = when {
            item == null -> "なし"
            VideoMediaSourceFactory.isVideo(item) -> "動画"
            StreamDownload.videoId(item) != null -> "試聴"
            item.mediaId.toLongOrNull()?.let { Repository.isOnline(it) } == true -> "☁"
            else -> "ライブラリ"
        }
        val vid = Liked.vidOf(item)
        val state = when (p.playbackState) {
            Player.STATE_IDLE -> "止まっている"; Player.STATE_BUFFERING -> "読み込み中"; Player.STATE_READY -> "再生できる"; else -> "終わり"
        }
        val net = if (!Net.streamAllowed(this)) "ネット不可" else if (Net.metered) "モバイル" else "Wi-Fi"
        return "「${item?.mediaMetadata?.title ?: ""}」($kind${vid?.let { " $it" }.orEmpty()}) " +
            "$state ${formatTime(p.currentPosition)}/${formatTime(p.duration.coerceAtLeast(0))} " +
            "先読み ${formatTime(p.bufferedPosition)}" +
            (if (p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) " ほかのアプリの音で一時停止中" else "") + " $net"
    }

    private fun skipStuck(p: Player, why: String) {
        val title = p.currentMediaItem?.mediaMetadata?.title ?: ""
        ErrorLog.add("次の曲へ", "「$title」が$why")
        android.widget.Toast.makeText(this, "「$title」が${why}ので次の曲へ", android.widget.Toast.LENGTH_LONG).show()
        if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.play() } else p.stop()
    }

    /** 再生中なのに位置が進まない(音が止まっている)のを見張る */
    private var stallPos = -1L
    private var stallSince = 0L

    /** 代わりの動画を試した曲(何度も試さないように) */
    private val altTried = HashSet<String>()

    /** 同じ曲の別の動画を探して差し替え(探し始めたら true) */
    private fun tryAlternative(p: Player, vid: String): Boolean {
        // 別の音源に切り替えた先でまた失敗した時は、さらに別の音源は探さない(行ったり来たりしないように)
        if (vid in altTried) return false
        val idx = p.currentMediaItemIndex
        val item = p.currentMediaItem ?: return false
        val m = item.mediaMetadata
        val title = m.title?.toString().orEmpty()
        val firstTime = AltSources.state(vid) == AltSources.State.UNKNOWN
        serviceScope.launch {
            // 探している間は再生ボタンのくるくるで分かる。知らせるのは結果が出てから 1 回だけ
            val alt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                AltSources.find(vid, title, m.artist?.toString())
            }
            if (idx >= p.mediaItemCount || p.getMediaItemAt(idx).mediaId != item.mediaId) return@launch
            if (alt == null) {
                if (firstTime) ErrorLog.add("再生不可", "「$title」($vid) 聴ける音源が見つからない")
                android.widget.Toast.makeText(this@PlaybackService,
                    "🔒「$title」は聴ける音源が見つからなかったので、次の曲へ進みます", android.widget.Toast.LENGTH_LONG).show()
                if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.play() }
                return@launch
            }
            altTried.add(alt)
            if (firstTime) {
                android.widget.Toast.makeText(this@PlaybackService, "🔄 別の音源で再生しています", android.widget.Toast.LENGTH_SHORT).show()
                ErrorLog.add("別の音源", "「$title」($vid) → $alt")
            }
            val next = PlayerConnection.streamItem(Searcher.Result(Searcher.Kind.SONG, alt, "https://music.youtube.com/watch?v=$alt",
                title, m.artist?.toString().orEmpty(), 0, m.artworkUri?.toString()))
                .buildUpon().setMediaMetadata(m).build()
            p.replaceMediaItem(idx, next)
            p.seekTo(idx, 0)
            p.prepare()
            p.play()
        }
        return true
    }

    /** 通知・ロック画面のボタン(♡ 気になる / ラジオ作り直し) */
    private fun onCustom(cmd: String) {
        val p = session?.player ?: return
        when (cmd) {
            CarLibrary.CMD_LIKE -> p.currentMediaItem?.let { Liked.fromItem(it) }?.let { r ->
                val added = Liked.toggle(r)
                android.widget.Toast.makeText(this, if (added) "「お気に入り」に追加しました" else "「お気に入り」から外しました",
                    android.widget.Toast.LENGTH_SHORT).show()
                updateButtons()
            }
            CarLibrary.CMD_RADIO -> {
                AutoContinue.setOn(this, true)
                android.widget.Toast.makeText(this, "この曲から次の曲を選び直しています…", android.widget.Toast.LENGTH_SHORT).show()
                rebuildRadio(p)
            }
        }
    }

    /** ♡ は試聴中の曲だけ(ライブラリの曲は保存済みなので出さない) */
    private fun updateButtons() {
        val s = session ?: return
        val item = s.player.currentMediaItem
        val vid = Liked.vidOf(item)
        val buttons = buildList {
            if (vid != null) {
                val on = Liked.contains(vid)
                add(androidx.media3.session.CommandButton.Builder()
                    .setDisplayName(if (on) "お気に入りから外す" else "お気に入り")
                    .setIconResId(if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
                    .setSessionCommand(androidx.media3.session.SessionCommand(CarLibrary.CMD_LIKE, android.os.Bundle.EMPTY))
                    .build())
            }
            add(androidx.media3.session.CommandButton.Builder()
                .setDisplayName("この曲からラジオ")
                .setIconResId(R.drawable.ic_autoplay)
                .setSessionCommand(androidx.media3.session.SessionCommand(CarLibrary.CMD_RADIO, android.os.Bundle.EMPTY))
                .build())
        }
        runCatching { s.setCustomLayout(buttons) }
    }

    /** 「この曲からラジオを作り直す」: 今の曲はそのまま、後ろの曲を今の曲に似た曲で入れ替える */
    private fun rebuildRadio(p: Player) {
        if (p.mediaItemCount == 0 || ringing.value != null) return
        val cur = p.currentMediaItemIndex
        if (cur == C.INDEX_UNSET) return
        if (cur + 1 < p.mediaItemCount) p.removeMediaItems(cur + 1, p.mediaItemCount)
        // 作り直したら、この後もラジオで続ける(どこから作り直しても)
        AutoContinue.setOn(this, true)
        if (p.repeatMode != Player.REPEAT_MODE_OFF) p.repeatMode = Player.REPEAT_MODE_OFF
        appending = false
        appendSimilar(p, fromCurrent = true)
    }

    /** 今の曲の後ろに残っている曲の数(リピートは考えない) */
    private fun remaining(p: Player): Int {
        if (p.mediaItemCount == 0) return 0
        if (p.shuffleModeEnabled) {
            var n = 0
            var i = p.currentMediaItemIndex
            val tl = p.currentTimeline
            while (n < 3) {
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
                if (i == C.INDEX_UNSET) break
                n++
            }
            return n
        }
        return RecoRules.remainingInOrder(p.mediaItemCount, p.currentMediaItemIndex)
    }

    private var radioRetries = 0

    private fun appendSimilar(p: Player, fromCurrent: Boolean = false) {
        if (appending || ringing.value != null || p.mediaItemCount == 0) return
        // あなたのミックスを再生中: ラジオではなく、ミックスと同じ決まりで続きを足す(ラジオの設定に関係なく。リピート中は足さない)
        if (!fromCurrent && p.repeatMode == Player.REPEAT_MODE_OFF) DailyMix.activeIndex()?.let { appendMix(p, it); return }
        // ラジオがオフ、またはリピート中(キューが終わらない)なら足さない
        if (!RecoRules.canAppend(fromCurrent, AutoContinue.isOn(this), p.repeatMode == Player.REPEAT_MODE_OFF)) return
        if (!Net.streamAllowed(this)) { ErrorLog.add("ラジオ", "似た曲を足せない: ${Net.reason(this)}"); return }
        // 種の候補: キューの後ろから 6 曲(まだ種にしていない曲を優先)。作り直しの時は今の曲だけ
        val tail = if (fromCurrent) listOfNotNull(p.currentMediaItem)
            else (p.mediaItemCount - 1 downTo maxOf(0, p.mediaItemCount - 6)).map { p.getMediaItemAt(it) }
        val seeds = tail.map { item ->
            val track = item.mediaId.toLongOrNull()?.let { Repository.track(it) }
            Triple(StreamDownload.videoId(item) ?: track?.sourceUrl?.let { youtubeVideoId(it) },
                track?.title ?: item.mediaMetadata.title?.toString().orEmpty(),
                track?.artist ?: item.mediaMetadata.artist?.toString().orEmpty())
        }
        val ordered = if (fromCurrent) seeds
            else seeds.filter { it.first == null || it.first !in usedSeeds } + seeds.filter { it.first != null && it.first in usedSeeds }
        val avoid = HashSet<String>().apply {
            synchronized(heard) { addAll(heard) }
            for (i in 0 until p.mediaItemCount) addAll(keysOf(p.getMediaItemAt(i)))
            // 最近 6 時間にライブラリで聴いた曲も避ける(聞き慣れた曲も少しは混ざるように)
            val from = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
            PlayLog.playsSince(from).mapNotNull { Repository.track(it.first) }.forEach { t ->
                youtubeVideoId(t.sourceUrl)?.let { add("v:$it") }; add(songKey(t.title, t.artist))
            }
        }
        appending = true
        val startCount = p.mediaItemCount
        val lastId = p.getMediaItemAt(p.mediaItemCount - 1).mediaId
        serviceScope.launch {
            try {
                val picked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val out = mutableListOf<Searcher.Result>()
                    val perArtist = HashMap<String, Int>()
                    // No Good に似たもの(足りない時だけ最後に使う)
                    val later = mutableListOf<Searcher.Result>()
                    NoGoodSimilar.refresh()
                    // 種ごとの似た曲(1 つの種で埋めず、種どうしで交互に取る。外れた種があっても全体が引きずられないように)
                    val seedIds = mutableSetOf<String>()
                    val lists = ordered.take(3).mapNotNull { (vid, title, artist) ->
                        val seed = vid ?: runCatching { InnerTube.findSongId(title, artist) }.getOrNull() ?: return@mapNotNull null
                        synchronized(usedSeeds) { usedSeeds.add(seed); while (usedSeeds.size > 100) usedSeeds.remove(usedSeeds.first()) }
                        seedIds += seed
                        runCatching { InnerTube.radio(seed) }.getOrDefault(emptyList())
                    }
                    val longest = lists.maxOfOrNull { it.size } ?: 0
                    val merged = (0 until longest).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
                    run {
                        for (r in merged) {
                            if (out.size >= 15) break
                            val k = songKey(r.title, r.artist)
                            if (r.id in seedIds || "v:${r.id}" in avoid || k in avoid) continue
                            // No Good にした曲・アーティストは足さない
                            if (NoGood.isSong(r.id) || NoGood.isArtist(Radio.mainArtist(r.artist))) continue
                            // No Good に似たものは後回し(ほかで足りなければ最後に使う)
                            if (NoGoodSimilar.similarSong(SkipLog.keysOf(r.videoId, r.title, r.artist)) || NoGoodSimilar.similarArtist(r.artist)) {
                                later += r; continue
                            }
                            // すぐ飛ばした曲(30 日以内)は足さない
                            if (SkipLog.isSkipped(SkipLog.keysOf(r.videoId, r.title, r.artist))) continue
                            // 同じアーティストばかりにならないように(1 回に 3 曲まで。並べる時に続かないようにする)
                            val a = Radio.mainArtist(r.artist).lowercase()
                            if ((perArtist[a] ?: 0) >= 3) continue
                            perArtist[a] = (perArtist[a] ?: 0) + 1
                            avoid += "v:${r.id}"; avoid += k
                            out += r
                        }
                    }
                    // ほかで 8 曲に足りなければ、No Good に似たものも使う(止まらないように)
                    if (out.size < 8) later.filter { r -> out.none { it.id == r.id } }.take(8 - out.size).forEach { out += it }
                    radioOrder(out, DailyMix.knownNewIds())
                }
                // 探している間に別の曲を選んでキューが変わっていたら足さない
                if (p.mediaItemCount != startCount || p.getMediaItemAt(p.mediaItemCount - 1).mediaId != lastId) return@launch
                if (picked.isEmpty()) {
                    ErrorLog.add("ラジオ", "似た曲が見つからない(種: ${ordered.take(3).joinToString { it.second }})")
                    // 通信の一時的な失敗かもしれないので、少し待ってもう一度(2 回まで)
                    if (radioRetries++ < 2) tickHandler.postDelayed({ appending = false; if (RecoRules.shouldAppendNow(remaining(p))) appendSimilar(p, fromCurrent) }, 20_000)
                    return@launch
                }
                radioRetries = 0
                val items = picked.map { r ->
                    val local = VideoAlias.trackFor(r.id)
                    local?.toMediaItem() ?: PlayerConnection.streamItem(r)
                }
                p.addMediaItems(items)
            } finally {
                appending = false
            }
        }
    }

    /** ライブラリの曲のジャケットを高画質に(取れたらロック画面・通知の画像も差し替え。再生は止めない) */
    private fun upgradeArt(p: Player, index: Int) {
        if (index == C.INDEX_UNSET || index !in 0 until p.mediaItemCount) return
        val item = p.getMediaItemAt(index)
        val t = item.mediaId.toLongOrNull()?.let { Repository.track(it) } ?: return
        if (t.thumbPath?.contains("edit_") == true) return
        serviceScope.launch {
            val path = HiArt.cachedPath(t) ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { HiArt.load(this@PlaybackService, t.id.toString(), null) }.getOrNull()
                HiArt.cachedPath(t)
            } ?: return@launch
            val uri = android.net.Uri.fromFile(java.io.File(path))
            // 位置が変わっていても同じ曲を探して差し替える
            val i = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == item.mediaId } ?: return@launch
            val cur = p.getMediaItemAt(i)
            if (cur.mediaMetadata.artworkUri == uri) return@launch
            p.replaceMediaItem(i, cur.buildUpon()
                .setMediaMetadata(cur.mediaMetadata.buildUpon().setArtworkUri(uri).build()).build())
        }
    }

    private fun saveQueue(p: Player) {
        val ids = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        getSharedPreferences(QUEUE_PREFS, MODE_PRIVATE).edit()
            .putString("ids", ids.joinToString(","))
            .putInt("index", p.currentMediaItemIndex)
            .putLong("position", p.currentPosition.coerceAtLeast(0))
            .putBoolean("shuffle", p.shuffleModeEnabled)
            .putInt("repeat", p.repeatMode)
            .apply()
    }

    /** 保存してある前回の再生キュー(曲, 位置, 再生位置)。無ければ null */
    private fun savedQueue(): MediaSession.MediaItemsWithStartPosition? {
        val prefs = getSharedPreferences(QUEUE_PREFS, MODE_PRIVATE)
        val ids = prefs.getString("ids", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return null
        val currentId = ids.getOrNull(prefs.getInt("index", 0))
        val tracks = ids.mapNotNull { Repository.track(it) }.filterNot { Repository.cantPlay(it.id) }
        if (tracks.isEmpty()) return null
        val index = tracks.indexOfFirst { it.id == currentId }
        val pos = if (index >= 0) prefs.getLong("position", 0) else 0L
        return MediaSession.MediaItemsWithStartPosition(tracks.map { it.toMediaItem() }, index.coerceAtLeast(0), pos)
    }

    /** 前回の再生キューを(再生はせずに)復元 */
    private fun restoreQueue(p: Player) {
        val q = savedQueue() ?: return
        val prefs = getSharedPreferences(QUEUE_PREFS, MODE_PRIVATE)
        p.setMediaItems(q.mediaItems, q.startIndex, q.startPositionMs)
        p.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
        p.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
    }

    /** Bluetooth(カーナビ等)につながった時: 止まっていれば前回の続きから再生 */
    private fun autoPlay() {
        val p = session?.player ?: return
        if (ringing.value != null || p.isPlaying) return
        if (p.mediaItemCount == 0) restoreQueue(p)
        if (p.mediaItemCount == 0) return
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.play()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ALARM_START -> {
                startAlarm(intent.getLongExtra(Alarms.EXTRA_ID, 0), intent.getBooleanExtra(EXTRA_TEST, false))
                return START_NOT_STICKY
            }
            ACTION_ALARM_STOP -> { stopAlarm(); return START_NOT_STICKY }
            ACTION_REBUILD_RADIO -> {
                session?.player?.let { rebuildRadio(it) }
                return START_NOT_STICKY
            }
            ACTION_APPEND_SIMILAR -> {
                session?.player?.let { p -> if (RecoRules.shouldAppendNow(remaining(p))) appendSimilar(p) }
                return START_NOT_STICKY
            }
            ACTION_AUTO_PLAY -> {
                // 前面サービスとして始める必要があるので、まず一時的な通知を出してから再生
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.createNotificationChannel(android.app.NotificationChannel(AUTO_CHANNEL, "自動再生",
                    android.app.NotificationManager.IMPORTANCE_LOW))
                val n = androidx.core.app.NotificationCompat.Builder(this, AUTO_CHANNEL)
                    .setSmallIcon(R.drawable.ic_music).setContentTitle("前回の続きを再生します").setSilent(true).build()
                runCatching {
                    androidx.core.app.ServiceCompat.startForeground(this, AUTO_NOTIF_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                }
                val giveUp = {
                    nm.cancel(AUTO_NOTIF_ID)
                    if (session?.player?.isPlaying != true) runCatching {
                        androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
                    }
                }
                // Bluetooth の音の出口(カーナビ・イヤホンの音楽用)ができてから鳴らす。
                // 電話用だけつながった時(カーナビがラジオ等を選んでいる時)は、スマホのスピーカーで鳴ってしまうので鳴らさない
                var waited = 0
                val check = object : Runnable {
                    override fun run() {
                        if (session?.player?.isPlaying == true) { giveUp(); return }
                        if (bluetoothAudioOut()) {
                            autoPlay()
                            // 再生が始まればいつもの通知に切り替わる。始まらなければ前面をやめる
                            tickHandler.postDelayed({ giveUp() }, 3000)
                        } else if (waited < 15_000) {
                            waited += 1000
                            tickHandler.postDelayed(this, 1000)
                        } else {
                            ErrorLog.add("自動再生", "Bluetooth の音の出口がないので、スピーカーで鳴らさないよう再生しませんでした")
                            giveUp()
                        }
                    }
                }
                check.run()
                return START_NOT_STICKY
            }
            ACTION_ALARM_SNOOZE -> {
                val id = ringing.value.takeIf { !testing }
                stopAlarm()
                if (id != null) Alarms.snooze(id, 10)
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // ───────── アラーム ─────────

    private fun startAlarm(id: Long, test: Boolean = false) {
        val alarm = Alarms.get(id)
        // 前面サービスとして 5 秒以内に通知を出す必要がある
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel(ALARM_CHANNEL, "アラーム", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                description = "設定した時刻に音楽を再生します"
                setSound(null, null)
            }
        )
        androidx.core.app.ServiceCompat.startForeground(
            this, ALARM_NOTIF_ID, alarmNotification(alarm),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        if (alarm == null) { stopAlarm(); return }
        ringing.value = id
        testing = test
        isTesting = test
        if (!test) Alarms.onFired(id)

        // メディア音量を設定(止めたら元に戻す)
        val am = getSystemService(android.media.AudioManager::class.java)
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        if (savedStreamVolume < 0) savedStreamVolume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val target = (max * alarm.volume / 100f).toInt().coerceIn(1, max)
        runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0) }

        val p = session?.player ?: return
        SleepTimer.cancel()
        // ライブラリ全体はシャッフル、プレイリストは順番どおり
        val tracks = alarm.tracks().let { if (alarm.target == AlarmTarget.LIBRARY) it.shuffled() else it }
        if (tracks.isEmpty()) {
            // 曲が 1 曲も無い時は端末のアラーム音
            p.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI))
            p.repeatMode = Player.REPEAT_MODE_ONE
        } else {
            p.setMediaItems(tracks.map { it.toMediaItem() }, 0, 0L)
            p.repeatMode = if (tracks.size == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        }
        p.shuffleModeEnabled = false
        p.volume = if (alarm.fadeIn) 0.05f else 1f
        p.prepare()
        p.play()
        if (alarm.fadeIn) {
            // 30 秒かけて徐々に大きく
            val start = System.currentTimeMillis()
            handler.post(object : Runnable {
                override fun run() {
                    if (ringing.value != id) return
                    val f = ((System.currentTimeMillis() - start) / 30_000f).coerceIn(0.05f, 1f)
                    p.volume = f
                    if (f < 1f) handler.postDelayed(this, 300)
                }
            })
        }
        nm.notify(ALARM_NOTIF_ID, alarmNotification(alarm))
        // 画面がオン(使用中)でもアラーム画面を開く。
        // 「他のアプリの上に重ねて表示」が許可されていれば、バックグラウンドからでも開ける。
        // (ロック中・画面オフは通知の全画面インテントで表示される)
        runCatching {
            startActivity(Intent(this, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun stopAlarm() {
        ringing.value = null
        handler.removeCallbacksAndMessages(null)
        session?.player?.let { it.pause(); it.volume = 1f }
        if (savedStreamVolume >= 0) {
            val am = getSystemService(android.media.AudioManager::class.java)
            runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, savedStreamVolume, 0) }
            savedStreamVolume = -1
        }
        androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_DETACH)
        getSystemService(android.app.NotificationManager::class.java).cancel(ALARM_NOTIF_ID)
    }

    private fun alarmNotification(alarm: Alarm?): android.app.Notification {
        val ring = PendingIntent.getActivity(
            this, 30, Intent(this, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val (name, _) = alarm?.targetText() ?: ("" to "")
        return androidx.core.app.NotificationCompat.Builder(this, ALARM_CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("アラーム ${alarm?.timeText ?: ""}" + (alarm?.label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
            .setContentText(if (name.isNotEmpty()) "♪ $name" else "")
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(ring, true)
            .setContentIntent(ring)
            .addAction(R.drawable.ic_snooze, "スヌーズ 10分", action(ACTION_ALARM_SNOOZE, 31))
            .addAction(R.drawable.ic_stop, "停止", action(ACTION_ALARM_STOP, 32))
            .build()
    }

    /**
     * ラジオの並び: 新曲(よく聴くアーティストの最近の曲)を少しだけ前へ(3 つ分)。
     * そのあと、同じアーティストが続かないように並べ替える
     */
    private fun radioOrder(list: List<Searcher.Result>, newIds: Set<String>): List<Searcher.Result> {
        val ranked = list.mapIndexed { i, r -> r to (i - if (r.videoId in newIds) 3.5 else 0.0) }.sortedBy { it.second }.map { it.first }
        val pool = ranked.toMutableList()
        val out = mutableListOf<Searcher.Result>()
        while (pool.isNotEmpty()) {
            val last = out.lastOrNull()?.let { Radio.mainArtist(it.artist).lowercase() }
            val i = pool.indexOfFirst { Radio.mainArtist(it.artist).lowercase() != last }
            out += pool.removeAt(if (i >= 0) i else 0)
        }
        return out
    }

    /** あなたのミックスの続きを足す */
    private fun appendMix(p: Player, index: Int) {
        if (!Net.streamAllowed(this) && Repository.tracks.value.isEmpty()) return
        appending = true
        val exclude = HashSet<String>().apply { for (i in 0 until p.mediaItemCount) addAll(SkipLog.keysOf(p.getMediaItemAt(i))) }
        serviceScope.launch {
            try {
                val items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { DailyMix.more(index, exclude) }.getOrDefault(emptyList())
                }
                if (items.isNotEmpty() && DailyMix.activeIndex() == index) p.addMediaItems(items)
            } finally {
                appending = false
            }
        }
    }

    /** 再生する順(シャッフル・リピートも考える)で、この先 n 曲のネットの曲の再生 URL を先に取る */
    private fun warmAhead(player: Player, n: Int) {
        val tl = player.currentTimeline
        if (tl.isEmpty) return
        var i = player.currentMediaItemIndex
        repeat(n) {
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) return
            StreamDownload.videoId(player.getMediaItemAt(i))?.let { runCatching { Searcher.warm(it) } }
        }
    }

    /** 音が Bluetooth の音楽用の出口(A2DP / LE Audio)に出せる状態か */
    private fun bluetoothAudioOut(): Boolean {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return false
        val types = mutableSetOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            types += android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
            types += android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) types += android.media.AudioDeviceInfo.TYPE_BLE_BROADCAST
        return am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    override fun onDestroy() {
        tickHandler.removeCallbacksAndMessages(null)
        ListenTime.save()
        serviceScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        VolumeLeveler.release()
        SoundSettings.release()
        session?.player?.let { SleepTimer.detach(it) }
        if (ringing.value != null) stopAlarm()
        session?.player?.let { saveQueue(it) }
        MusicWidget.stopped(this)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/**
 * 音の出力の手前に持つ余裕を少し多めに(標準 0.25〜0.75 秒 → 0.5〜1 秒)。
 * ダウンロードの変換などで一瞬処理が詰まっても、音が途切れにくいように
 */
private class AudioRenderers(context: android.content.Context) : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean,
                                enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
        androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioTrackBufferSizeProvider(
                androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(500_000)
                    .setMaxPcmBufferDurationUs(1_000_000)
                    .build()
            )
            .build()
}

/**
 * 読み込みに失敗した時の取り直しの決まり。
 * Premium 限定などで聴けない曲は、取り直しても無駄なのですぐあきらめる(別の音源探しへ早く進む)。それ以外はいつもどおり
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private class NoRetryForUnplayable : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        var t: Throwable? = info.exception
        while (t != null) {
            if (t is Searcher.Unplayable) return C.TIME_UNSET
            t = t.cause.takeIf { it !== t }
        }
        return super.getRetryDelayMsFor(info)
    }
}
