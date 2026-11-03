package com.example.exp7

import android.net.Uri

/**
 * 一首本地音乐的信息，由 ContentResolver 从 MediaStore 中查询得到。
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: Uri
)
