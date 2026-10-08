package com.souko.soukoplayer.models

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val year: String,
    val created: Long // 时间戳，用于排序
)