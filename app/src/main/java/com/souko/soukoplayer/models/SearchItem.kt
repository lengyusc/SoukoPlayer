// 创建 SearchItem.kt
package com.souko.soukoplayer.models

import com.souko.soukoplayer.network.NavArtist

sealed class SearchItem {
    data class AlbumItem(val album: Album) : SearchItem()
    data class TrackItem(val track: Track) : SearchItem()
    data class ArtistItem(val artist: NavArtist) : SearchItem()
}