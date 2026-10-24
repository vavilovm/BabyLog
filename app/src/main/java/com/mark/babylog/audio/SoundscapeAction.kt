package com.mark.babylog.audio

import android.content.ComponentName
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

@Composable
fun SoundscapeAction() {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    var connect by rememberSaveable { mutableStateOf(false) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(.25f) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(context, connect) {
        if (!connect) return@DisposableEffect onDispose {}
        val future =
            MediaController.Builder(
                    context,
                    SessionToken(context, ComponentName(context, SoundscapeService::class.java)),
                )
                .buildAsync()
        var active = true
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    currentId = player.currentMediaItem?.mediaId
                    playing =
                        player.playWhenReady &&
                            player.playbackState != Player.STATE_IDLE &&
                            player.playbackState != Player.STATE_ENDED
                    volume = player.volume
                }

                override fun onPlayerError(playbackError: PlaybackException) {
                    error = "Не удалось включить звук. Попробуйте ещё раз."
                }
            }
        future.addListener(
            {
                if (active) {
                    val connected = runCatching { future.get() }.getOrNull()
                    if (connected == null) {
                        error = "Плеер недоступен. Откройте приложение снова."
                    } else {
                        controller = connected
                        connected.addListener(listener)
                        currentId = connected.currentMediaItem?.mediaId
                        playing =
                            connected.playWhenReady && connected.playbackState != Player.STATE_IDLE
                        volume = connected.volume
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            active = false
            controller?.removeListener(listener)
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    IconButton(
        modifier = Modifier.size(48.dp),
        onClick = {
            connect = true
            open = true
        },
    ) {
        Icon(
            Icons.Outlined.MusicNote,
            contentDescription = if (playing) "Звуки для сна · играет" else "Звуки для сна",
            tint =
                if (playing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Звуки для сна") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Работают без интернета, в том числе с выключенным экраном.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    soundscapes.forEach { sound ->
                        val selected = currentId == sound.id && playing
                        ListItem(
                            headlineContent = { Text(sound.title) },
                            supportingContent = { Text(sound.description) },
                            trailingContent = {
                                IconButton(
                                    enabled = controller != null,
                                    onClick = {
                                        controller?.let { player ->
                                            error = null
                                            if (selected) player.pause()
                                            else {
                                                if (
                                                    currentId != sound.id ||
                                                        player.playbackState == Player.STATE_IDLE
                                                ) {
                                                    player.setMediaItem(sound.mediaItem())
                                                    player.prepare()
                                                }
                                                player.play()
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        if (selected) Icons.Outlined.Pause
                                        else Icons.Outlined.PlayArrow,
                                        if (selected) "Пауза · ${sound.title}"
                                        else "Слушать · ${sound.title}",
                                        tint =
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            colors =
                                ListItemDefaults.colors(
                                    containerColor =
                                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surface
                                ),
                        )
                    }
                    Text("Громкость · ${(volume * 100).toInt()}%")
                    Slider(
                        value = volume,
                        onValueChange = {
                            volume = it
                            controller?.volume = it
                            context
                                .getSharedPreferences(
                                    "soundscapes",
                                    android.content.Context.MODE_PRIVATE,
                                )
                                .edit()
                                .putFloat("volume", it)
                                .apply()
                        },
                        enabled = controller != null,
                    )
                    Text(
                        "Начните с тихой громкости и держите телефон подальше от малыша.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Готово") } },
        )
}
