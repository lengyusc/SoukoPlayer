package com.souko.soukoplayer.models

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val albumId: String,
    val album: String,
    val year: Int = 0,         // 改为 Int，默认 0
    val coverUrl: String = "",
    val streamUrl: String,
    val duration: Int? = null,
    val lyrics: String? = null  // 增加歌词字段（支持 LRC 格式字符串）
)