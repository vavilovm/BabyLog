package com.mark.babylog.audio

import android.content.ComponentName
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SoundscapeCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    var connect by rememberSaveable { mutableStateOf(false) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(.25f) }
    var error by remember { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri
            ->
            val sound = soundscapes.firstOrNull { it.id == exportId }
            exportId = null
            if (uri != null && sound != null) {
                saving = true
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                context.assets.open(sound.assetPath).use { input ->
                                    checkNotNull(context.contentResolver.openOutputStream(uri))
                                        .use { input.copyTo(it) }
                                }
                            }
                        }
                    saving = false
                    Toast.makeText(
                            context,
                            if (result.isSuccess) "Звук сохранён на телефон"
                            else "Не удалось сохранить файл. Попробуйте снова.",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
            }
        }
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
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Звуки для сна", style = MaterialTheme.typography.titleMedium)
            Text(
                if (playing)
                    "Сейчас: ${soundscapes.firstOrNull { it.id == currentId }?.title.orEmpty()}"
                else "Дождь, костёр, океан и мягкий шум · офлайн",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row {
                TextButton(
                    onClick = {
                        connect = true
                        open = true
                    }
                ) {
                    Text("Выбрать звук")
                }
                if (playing) TextButton(onClick = { controller?.pause() }) { Text("Пауза") }
            }
        }
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
                        "Все звуки уже на телефоне. Играют по кругу без пауз, в том числе с выключенным экраном, пока вы не нажмёте паузу."
                    )
                    Text(
                        "Оригинальные звуковые пейзажи. Сохраните файл, чтобы слушать в другом плеере.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    soundscapes.forEach { sound ->
                        Text(sound.title, style = MaterialTheme.typography.titleSmall)
                        Text(sound.description, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(
                                enabled = controller != null,
                                onClick = {
                                    controller?.let { player ->
                                        error = null
                                        if (currentId == sound.id && playing) player.pause()
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
                                Text(if (currentId == sound.id && playing) "Пауза" else "Слушать")
                            }
                            TextButton(
                                enabled = !saving && exportId == null,
                                onClick = {
                                    exportId = sound.id
                                    export.launch("BabyLog-${sound.id}.wav")
                                },
                            ) {
                                Text("Сохранить · 3,8 МБ")
                            }
                        }
                        HorizontalDivider()
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
