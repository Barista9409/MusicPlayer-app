package com.example.ytmp3

import android.content.Context
import android.view.Menu
import android.widget.Toast

/** 「このアーティストはもう聴かない」(No Good)を、いろいろな場所のメニューから選べるように */
object NoGoodUi {
    private fun usable(a: String) = a.isNotBlank() && a != "不明なアーティスト" && a != "YouTube"

    /** アーティストを No Good にする(ミックス・ラジオ・おすすめに出なくなる) */
    fun setArtist(c: Context, artist: String, id: String = artist) {
        val a = Radio.mainArtist(artist)
        if (!usable(a)) return
        NoGood.addArtist(id, a)
        DailyDiscover.list.value.filter { Radio.mainArtist(it.artist).equals(a, ignoreCase = true) }
            .mapNotNull { it.videoId }.forEach { DailyDiscover.drop(it) }
        Toast.makeText(c, "「$a」はもう聴かない設定にしました。ミックス・ラジオ・おすすめに出なくなります(解除は 設定 → No Good にしたもの)",
            Toast.LENGTH_LONG).show()
    }

    /** No Good を解除 */
    fun unsetArtist(c: Context, artist: String) {
        val a = Radio.mainArtist(artist)
        NoGood.removeArtists(NoGood.artists.value
            .filter { LibraryMatch.artistKey(it.name) == LibraryMatch.artistKey(a) || it.key == a }.map { it.key }.toSet())
        Toast.makeText(c, "「$a」をまた聴くようにしました", Toast.LENGTH_SHORT).show()
    }

    /** メニューに「🚫 〇〇 はもう聴かない」(すでに No Good なら解除)を足す */
    fun addArtistItem(menu: Menu, c: Context, artist: String?, id: String? = null) {
        val a = Radio.mainArtist(artist.orEmpty())
        if (!usable(a)) return
        if (NoGood.isArtist(a) || (id != null && NoGood.isArtist(id))) {
            menu.add("「$a」の No Good を解除").setOnMenuItemClickListener { unsetArtist(c, a); true }
        } else {
            menu.add("🚫「$a」はもう聴かない").setOnMenuItemClickListener { setArtist(c, a, id ?: a); true }
        }
    }
}
