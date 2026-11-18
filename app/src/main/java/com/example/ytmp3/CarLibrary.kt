package com.example.ytmp3

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 車の画面(Android Auto)などから見えるライブラリ:
 * 全曲シャッフル / プレイリスト / アルバム / アーティスト / 最近追加。
 * 曲を選ぶと、そのプレイリスト・アルバム等の続きもキューに入る。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CarLibrary(
    private val resume: () -> MediaSession.MediaItemsWithStartPosition?,
    private val onCustom: (String) -> Unit = {},
) : MediaLibrarySession.Callback {

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val base = super.onConnect(session, controller)
        val cmds = base.availableSessionCommands.buildUpon()
            .add(androidx.media3.session.SessionCommand(CMD_LIKE, Bundle.EMPTY))
            .add(androidx.media3.session.SessionCommand(CMD_RADIO, Bundle.EMPTY))
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(cmds)
            .setAvailablePlayerCommands(base.availablePlayerCommands)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession, controller: MediaSession.ControllerInfo,
        customCommand: androidx.media3.session.SessionCommand, args: Bundle,
    ): ListenableFuture<androidx.media3.session.SessionResult> {
        onCustom(customCommand.customAction)
        return Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    private fun folder(id: String, title: String, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, sub: String? = null) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(sub).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(type).build()
        ).build()

    private fun playable(id: String, title: String, sub: String?) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(sub).setIsBrowsable(false).setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
    ).build()

    private fun playable() = Repository.tracks.value.filter { Net.canPlayNow(it) }

    /** まとまり(ctx)の曲。ctx = "pl|<id>" / "al|<アルバム>" / "ar|<アーティスト>" / "recent" */
    private fun tracksOf(ctx: String): List<Track> {
        val all = playable()
        return when {
            ctx.startsWith("pl|") -> ctx.removePrefix("pl|").toLongOrNull()?.let { Repository.playlist(it) }
                ?.let { Repository.tracksOf(it).filterNot { t -> Repository.cantPlay(t.id) } }.orEmpty()
            ctx.startsWith("al|") -> dec(ctx.removePrefix("al|")).let { a -> all.filter { it.album == a }.sortedWith(albumOrderComparator) }
            ctx.startsWith("ar|") -> dec(ctx.removePrefix("ar|")).let { a -> all.filter { Radio.mainArtist(it.artist) == a }.sortedWith(albumOrderComparator) }
            ctx == "recent" -> all.sortedByDescending { it.savedAt }.take(100)
            else -> emptyList()
        }
    }

    private fun children(parent: String): List<MediaItem> {
        val all = playable()
        return when {
            parent == ROOT -> listOf(
                playable("action|shuffle", "全曲シャッフル", "${all.size} 曲"),
                folder("playlists", "プレイリスト", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder("albums", "アルバム", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folder("artists", "アーティスト", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                folder("ctx|recent", "最近追加", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            )
            parent == "playlists" -> Repository.playlists.value.map { p ->
                folder("ctx|pl|${p.id}", p.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, "${Repository.tracksOf(p).size} 曲")
            }
            parent == "albums" -> all.filter { it.album.isNotBlank() }.groupBy { it.album }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .map { (a, ts) -> folder("ctx|al|${enc(a)}", a, MediaMetadata.MEDIA_TYPE_ALBUM, ts.first().artist) }
            parent == "artists" -> all.filter { it.artist.isNotBlank() }.groupBy { Radio.mainArtist(it.artist) }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .map { (a, ts) -> folder("ctx|ar|${enc(a)}", a, MediaMetadata.MEDIA_TYPE_ARTIST, "${ts.size} 曲") }
            parent.startsWith("ctx|") -> {
                val ctx = parent.removePrefix("ctx|")
                tracksOf(ctx).map { t -> playable("q|$ctx|${t.id}", t.title, t.artist.ifBlank { null }) }
            }
            else -> emptyList()
        }
    }

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?):
        ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "MusicPlayer"), params))

    override fun onGetChildren(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
        page: Int, pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val list = children(parentId)
        val from = (page * pageSize).coerceAtMost(list.size)
        val to = (from + pageSize).coerceAtMost(list.size)
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(if (pageSize > 0) list.subList(from, to) else list), params))
    }

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String):
        ListenableFuture<LibraryResult<MediaItem>> {
        val t = mediaId.substringAfterLast('|').toLongOrNull()?.let { Repository.track(it) }
        return Futures.immediateFuture(
            if (t != null) LibraryResult.ofItem(t.toMediaItem(), null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        )
    }

    /** 再生できる形に(アプリからの曲はそのまま、車の画面からの ID は曲に置き換える) */
    private fun resolve(items: List<MediaItem>): List<MediaItem> = items.mapNotNull { item ->
        if (item.localConfiguration != null) item
        else item.mediaId.substringAfterLast('|').toLongOrNull()?.let { Repository.track(it) }?.toMediaItem()
    }

    override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>):
        ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(resolve(mediaItems).toMutableList())

    override fun onSetMediaItems(
        session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        startIndex: Int, startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val first = mediaItems.singleOrNull()?.takeIf { it.localConfiguration == null }?.mediaId
        // 車の画面: 全曲シャッフル
        if (first == "action|shuffle") {
            val list = playable().shuffled().map { it.toMediaItem() }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(list, 0, 0L))
        }
        // 車の画面: プレイリスト等の曲 → そのまとまりを続けて
        if (first != null && first.startsWith("q|")) {
            val ctx = first.removePrefix("q|").substringBeforeLast('|')
            val tid = first.substringAfterLast('|').toLongOrNull()
            val ts = tracksOf(ctx)
            if (ts.isNotEmpty()) {
                PlayLog.setCurrent(null, emptyList())
                AutoContinue.newQueue(ctx.startsWith("pl|"))
                val idx = ts.indexOfFirst { it.id == tid }.coerceAtLeast(0)
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(ts.map { it.toMediaItem() }, idx, 0L))
            }
        }
        val list = resolve(mediaItems)
        return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(list,
            if (startIndex == androidx.media3.common.C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0)),
            startPositionMs))
    }

    // アプリが終了していても、カーナビ等の「再生」で前回の続きから
    override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo):
        ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFuture(resume() ?: MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))

    companion object {
        /** 通知・ロック画面のボタン */
        const val CMD_LIKE = "com.example.ytmp3.CMD_LIKE"
        const val CMD_RADIO = "com.example.ytmp3.CMD_RADIO"
        const val ROOT = "root"
    }
}
