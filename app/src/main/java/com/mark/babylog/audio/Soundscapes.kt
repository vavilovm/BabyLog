package com.mark.babylog.audio

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Soundscape(
    val id: String,
    val title: String,
    val description: String,
    val author: String = "BabyLog",
) {
    val assetPath
        get() = "soundscapes/$id.flac"

    fun mediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri("asset:///$assetPath")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(author).build())
            .build()
}

val soundscapes =
    listOf(
        Soundscape("rain", "Дождь", "Полевая запись дождя", "alex36917"),
        Soundscape("fire", "Камин", "Настоящее потрескивание дров", "kyles"),
        Soundscape("ocean", "Океан", "Настоящий морской прибой", "Luftrum"),
        Soundscape("brook", "Ручей", "Полевая запись журчащего ручья", "gluckose"),
        Soundscape("white", "Белый шум", "Ровный широкий спектр"),
        Soundscape("pink", "Розовый шум", "Более мягкий и глубокий шум"),
    )
