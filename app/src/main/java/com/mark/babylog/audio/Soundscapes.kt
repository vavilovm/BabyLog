package com.mark.babylog.audio

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Soundscape(val id: String, val title: String, val description: String) {
    val assetPath
        get() = "soundscapes/$id.wav"

    fun mediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri("asset:///$assetPath")
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setArtist("BabyLog · Звуки для сна").build()
            )
            .build()
}

val soundscapes =
    listOf(
        Soundscape("rain", "Дождь", "Ровный дождь и мягкие капли"),
        Soundscape("fire", "Костёр", "Тёплый гул и потрескивание"),
        Soundscape("ocean", "Океан", "Медленные волны прибоя"),
        Soundscape("brook", "Ручей", "Журчание воды"),
        Soundscape("white", "Белый шум", "Ровный широкий спектр"),
        Soundscape("pink", "Розовый шум", "Более мягкий и глубокий шум"),
    )
