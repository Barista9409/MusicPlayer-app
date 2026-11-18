package com.example.ytmp3

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val uri: String,
    val savedAt: Long,
    val sizeBytes: Long,
    val durationMs: Long,
    val location: String,
    val thumbPath: String?,
    val sourceUrl: String,
    /** 端末上のフォルダ(例 "Music/Artist/Album/")。不明なら空 */
    val folder: String = "",
    val fileName: String = "",
    /** "download" = このアプリで保存 / "scan" = フォルダから読み込み */
    val source: String = SOURCE_DOWNLOAD,
    /** アルバム内のトラック番号(0 = 不明)とディスク番号 */
    val trackNo: Int = 0,
    val discNo: Int = 0,
    /** 重複としてまとめた別ファイル(予備)。元が見つからない時はこちらを使う */
    val altUris: List<String> = emptyList(),
    val altKeys: List<String> = emptyList(),
    /** アプリで情報を編集した(フォルダの再読み込みで上書きしない) */
    val edited: Boolean = false,
    /** 保存した動画(🎬 の時にこちらを流す)。無ければ空 */
    val videoUri: String = "",
    val videoBytes: Long = 0,
    /** 保存した動画の YouTube ID(曲版と MV で違うことがある) */
    val videoVid: String = "",
) {
    val hasVideo get() = videoUri.isNotEmpty()
    val artistOrUnknown get() = artist.ifBlank { "不明なアーティスト" }
    val albumOrUnknown get() = album.ifBlank { "不明なアルバム" }

    /** 同じファイルかどうかの判定キー */
    val fileKey get() = if (fileName.isEmpty()) "" else (folder + fileName).lowercase()

    /** このライブラリ項目が持つ全ファイルのキー(本体 + 予備) */
    val allKeys get() = (listOf(fileKey) + altKeys).filter { it.isNotEmpty() }

    /** 重複判定用(曲名 + アーティスト、記号・空白・大小文字を無視) */
    val dupKey: String get() {
        val t = normalizeForDup(title)
        return if (t.isEmpty()) "" else t + "|" + normalizeForDup(artist)
    }

    companion object {
        const val SOURCE_DOWNLOAD = "download"
        const val SOURCE_SCAN = "scan"
    }
}

data class Playlist(
    val id: Long,
    val name: String,
    val trackIds: List<Long>,
    val createdAt: Long,
)

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELED }

data class DownloadJob(
    val id: Long,
    val url: String,
    val title: String,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Int = 0,
    val message: String = "",
    /** 完了時に追加するプレイリスト(アルバム一括時) */
    val playlistId: Long? = null,
    val trackId: Long? = null,
    /** アルバム一括時のアルバム内の順番(1 始まり)とアルバム名 */
    val trackNo: Int = 0,
    val albumName: String = "",
    /** プレイリスト内での位置(並び順を保つため。-1 = 末尾に追加) */
    val playlistPos: Int = -1,
    /** 取り直し: この曲(id)のファイルとして保存する(復元後に端末に無い曲など) */
    val replaceTrackId: Long? = null,
    /** 動画の保存: この曲(id)に付ける。動画の ID だけ分かっている時は url から曲を探す */
    val video: Boolean = false,
    val videoFor: Long? = null,
)

/** YouTube の動画 ID(11 文字)を URL から取り出す */
private val ytIdPattern = Regex("""(?:youtu\.be/|[?&]v=|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})""")
/** URL から YouTube の動画 ID(パターンは 1 回だけ作る: 何千回も呼ばれるので) */
fun youtubeVideoId(url: String): String? = ytIdPattern.find(url)?.groupValues?.get(1)

private val nonWord = Regex("[^\\p{L}\\p{N}]+")
fun normalizeForDup(s: String) = s.lowercase().replace(nonWord, "")

/** "2 foo" < "10 foo" となるよう数字を桁揃えした比較キー */
fun naturalKey(s: String) = Regex("\\d+").replace(s.lowercase()) { it.value.padStart(8, '0') }

/** アルバム内の曲順: トラック番号 → ファイル名(自然順) → 保存順 */
val albumOrderComparator: Comparator<Track> = compareBy<Track>(
    { if (it.trackNo > 0) 0 else 1 }, { it.discNo }, { it.trackNo },
    { naturalKey(it.fileName) }, { it.savedAt },
)
