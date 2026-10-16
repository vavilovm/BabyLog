package com.mark.babylog

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mark.babylog.sync.FamilySyncStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class SyncDisplayState(val title: String) {
    LOCAL("Сохранено на устройстве"),
    SYNCING("Синхронизация…"),
    PENDING("Сохранено · ждёт синхронизации"),
    FAILED("Не удалось синхронизировать"),
    SYNCED("Синхронизировано с семьёй"),
    READY("Семейный журнал подключён"),
}

internal fun syncDisplayState(
    family: Boolean,
    pending: Int,
    status: FamilySyncStatus,
): SyncDisplayState =
    when {
        !family -> SyncDisplayState.LOCAL
        status.running -> SyncDisplayState.SYNCING
        status.error != null -> SyncDisplayState.FAILED
        pending > 0 -> SyncDisplayState.PENDING
        pending < 0 -> SyncDisplayState.READY
        status.lastSyncedAt != null -> SyncDisplayState.SYNCED
        else -> SyncDisplayState.READY
    }

@Composable
internal fun SyncStatusAction(
    family: Boolean,
    pending: Int,
    status: FamilySyncStatus,
    zone: ZoneId,
    onRetry: () -> Unit,
) {
    var details by remember { mutableStateOf(false) }
    val state = syncDisplayState(family, pending, status)
    IconButton(onClick = { details = true }, modifier = Modifier.size(48.dp)) {
        Icon(
            when (state) {
                SyncDisplayState.LOCAL -> Icons.Outlined.PhoneAndroid
                SyncDisplayState.SYNCING -> Icons.Outlined.Sync
                SyncDisplayState.PENDING -> Icons.Outlined.CloudUpload
                SyncDisplayState.FAILED -> Icons.Outlined.CloudOff
                SyncDisplayState.SYNCED -> Icons.Outlined.CloudDone
                SyncDisplayState.READY -> Icons.Outlined.CloudQueue
            },
            contentDescription = state.title,
            tint =
                if (state == SyncDisplayState.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (details)
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(state.title) },
            text = {
                Text(
                    buildString {
                        append(
                            when (state) {
                                SyncDisplayState.LOCAL ->
                                    "Записи сохраняются на этом устройстве. Для общего журнала подключите семью."
                                SyncDisplayState.SYNCING ->
                                    "Обмениваемся записями с семейным журналом."
                                SyncDisplayState.PENDING ->
                                    "Изменения сохранены на устройстве и будут отправлены при подключении."
                                SyncDisplayState.FAILED ->
                                    "Локальные записи сохранены. Повторите попытку при подключении к интернету."
                                SyncDisplayState.SYNCED ->
                                    "Последний обмен с семейным журналом завершён."
                                SyncDisplayState.READY ->
                                    "Ожидаем первый успешный обмен с семейным журналом."
                            }
                        )
                        if (family && pending > 0) append("\nОжидают отправки: $pending.")
                        if (family)
                            status.lastSyncedAt?.let {
                                append(
                                    "\nПоследняя синхронизация: " +
                                        Instant.ofEpochMilli(it)
                                            .atZone(zone)
                                            .format(DateTimeFormatter.ofPattern("dd.MM · HH:mm"))
                                )
                            }
                    }
                )
            },
            confirmButton = { TextButton(onClick = { details = false }) { Text("Понятно") } },
            dismissButton = {
                if (family && !status.running && state != SyncDisplayState.SYNCED)
                    TextButton(onClick = onRetry) { Text("Повторить") }
            },
        )
}
