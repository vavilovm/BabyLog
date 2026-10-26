package com.mark.babylog.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun SoundscapeCreditsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val sources =
        remember(context) {
            val json =
                context.assets.open("soundscapes/sources.json").bufferedReader().use {
                    it.readText()
                }
            val recordings = JSONObject(json).getJSONArray("recordings")
            (0 until recordings.length()).map { recordings.getJSONObject(it) }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Записи и лицензии") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Природные звуки — настоящие записи с Freesound, в том числе из коллекции Blanket. В BabyLog выровнена громкость, смягчены громкие пики и подготовлены плавные циклы."
                )
                sources.forEach { source ->
                    Text(
                        "${source.getString("title")} · ${source.getString("author")}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${source.getString("original_title")} · ${source.getString("license")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!source.isNull("editor"))
                        Text(
                            "Подготовка исходной записи: ${source.getString("editor")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    Row {
                        TextButton(onClick = { uri.openUri(source.getString("source_url")) }) {
                            Text("Источник")
                        }
                        TextButton(onClick = { uri.openUri(source.getString("license_url")) }) {
                            Text("Лицензия")
                        }
                    }
                    HorizontalDivider()
                }
                Text(
                    "Белый и розовый шум созданы в BabyLog как шумовые сигналы; это отдельные варианты, без имитации звуков природы.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}
