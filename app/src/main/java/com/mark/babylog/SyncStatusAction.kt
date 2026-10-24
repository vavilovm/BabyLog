package com.mark.babylog

import androidx.compose.material3.*
import androidx.compose.runtime.*
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
internal fun SyncStatusDetails(
    family: Boolean,
    pending: Int,
    status: FamilySyncStatus,
    zone: ZoneId,
    onRetry: () -> Unit,
) {
    val state = syncDisplayState(family, pending, status)
    Text(
        state.title,
        style = MaterialTheme.typography.titleSmall,
        color =
            if (state == SyncDisplayState.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
    )
    Text(
        buildString {
            append(
                when (state) {
                    SyncDisplayState.LOCAL ->
                        "Записи сохраняются на этом устройстве. Для общего журнала подключите семью."
                    SyncDisplayState.SYNCING -> "Обмениваемся записями с семейным журналом."
                    SyncDisplayState.PENDING ->
                        "Изменения сохранены на устройстве и будут отправлены при подключении."
                    SyncDisplayState.FAILED ->
                        "Локальные записи сохранены. Повторите попытку при подключении к интернету."
                    SyncDisplayState.SYNCED -> "Последний обмен с семейным журналом завершён."
                    SyncDisplayState.READY -> "Ожидаем первый успешный обмен с семейным журналом."
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
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (family && !status.running) TextButton(onClick = onRetry) { Text("Синхронизировать сейчас") }
}
