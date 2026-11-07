package com.mark.babylog

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mark.babylog.data.*
import com.mark.babylog.sleep.*
import java.time.*

@Composable
internal fun ProfileDialog(
    profile: BabyProfile?,
    onDismiss: () -> Unit,
    onSave: (String, String?, String) -> Unit,
) {
    val context = LocalContext.current
    var birthday by remember(profile) { mutableStateOf(profile?.birthday?.let(LocalDate::parse)) }
    var due by remember(profile) { mutableStateOf(profile?.dueDate?.let(LocalDate::parse)) }
    var rhythmZone by
        remember(profile) { mutableStateOf(profile?.rhythmZoneId ?: ZoneId.systemDefault().id) }
    val validZone = remember(rhythmZone) { runCatching { ZoneId.of(rhythmZone) }.isSuccess }
    fun pick(value: LocalDate?, maximum: LocalDate?, set: (LocalDate) -> Unit) {
        val initial = value ?: LocalDate.now()
        DatePickerDialog(
                context,
                { _, year, month, day -> set(LocalDate.of(year, month + 1, day)) },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth,
            )
            .apply {
                maximum?.let {
                    datePicker.maxDate =
                        it.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            }
            .show()
    }
    AlertDialog(
        onDismissRequest = { if (profile != null) onDismiss() },
        title = { Text("Когда родилась малышка?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Возраст помогает учитывать, как меняется сон. Дата будет общей для вашей семьи."
                )
                OutlinedButton(onClick = { pick(birthday, LocalDate.now()) { birthday = it } }) {
                    Text(birthday?.format(dateFormat) ?: "Выбрать дату рождения")
                }
                Text(
                    "Если родилась раньше срока, можно указать предполагаемую дату родов.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { pick(due, null) { due = it } }) {
                    Text(due?.format(dateFormat) ?: "Предполагаемая дата родов (необязательно)")
                }
                OutlinedTextField(
                    value = rhythmZone,
                    onValueChange = { rhythmZone = it.trim() },
                    label = { Text("Домашний часовой пояс") },
                    placeholder = { Text("Europe/Amsterdam") },
                    isError = !validZone,
                    singleLine = true,
                )
                Text(
                    "В поездке оставьте домашний пояс: ритм сна меняется постепенно. Время на графике — местное.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (due != null) TextButton(onClick = { due = null }) { Text("Убрать дату родов") }
                if (birthday != null && due != null && due!! < birthday!!)
                    Text(
                        "Дата родов должна быть не раньше рождения",
                        color = MaterialTheme.colorScheme.error,
                    )
            }
        },
        confirmButton = {
            Button(
                enabled = birthday != null && (due == null || due!! >= birthday!!) && validZone,
                onClick = { onSave(birthday.toString(), due?.toString(), rhythmZone) },
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = { if (profile != null) TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun SleepEntryDialog(
    event: BabyEvent?,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (Long, Long?) -> Unit,
    onDelete: () -> Unit,
    endFirst: Boolean = false,
) {
    val now = System.currentTimeMillis()
    var start by remember { mutableLongStateOf(event?.startedAt ?: now - 3_600_000) }
    var end by remember {
        mutableStateOf(if (event == null || event.sleepRecordVersion == 0) now else event.endedAt)
    }
    val valid = start <= now && (end == null || (end!! > start && end!! <= now))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (event == null) "Добавить сон" else "Изменить сон") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Укажите фактическое время засыпания и пробуждения.")
                if (!endFirst) SleepEntryTimeField("Начало", start, zone) { start = it }
                if (end != null) {
                    SleepEntryTimeField("Конец", end!!, zone) { end = it }
                } else {
                    OutlinedButton(onClick = { end = System.currentTimeMillis() }) {
                        Text("Ещё спит · указать пробуждение")
                    }
                }
                if (endFirst) SleepEntryTimeField("Начало", start, zone) { start = it }
                if (!valid)
                    Text(
                        "Пробуждение должно быть после начала сна, не в будущем.",
                        color = MaterialTheme.colorScheme.error,
                    )
                if (event != null)
                    TextButton(onClick = onDelete) {
                        Text("Удалить запись", color = MaterialTheme.colorScheme.error)
                    }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = { onSave(start, end) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun SleepEntryTimeField(label: String, time: Long, zone: ZoneId, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    val selected = Instant.ofEpochMilli(time).atZone(zone)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                onChange(
                                    resolveSleepLocalTime(
                                        selected.toLocalDate().atTime(hour, minute),
                                        zone,
                                        selected.offset,
                                    )
                                )
                            },
                            selected.hour,
                            selected.minute,
                            true,
                        )
                        .show()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(clock(time, zone), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                onChange(
                                    resolveSleepLocalTime(
                                        LocalDate.of(year, month + 1, day)
                                            .atTime(selected.toLocalTime()),
                                        zone,
                                        selected.offset,
                                    )
                                )
                            },
                            selected.year,
                            selected.monthValue - 1,
                            selected.dayOfMonth,
                        )
                        .apply { datePicker.maxDate = System.currentTimeMillis() }
                        .show()
                }
            ) {
                Text(selected.format(dateFormat))
            }
        }
        val offsets = zone.rules.getValidOffsets(selected.toLocalDateTime())
        if (offsets.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offsets.forEach { offset ->
                    FilterChip(
                        selected = offset == selected.offset,
                        onClick = {
                            onChange(
                                resolveSleepLocalTime(selected.toLocalDateTime(), zone, offset)
                            )
                        },
                        label = { Text("UTC$offset") },
                    )
                }
            }
        }
        MinuteShortcuts { onChange(moveMinutes(time, it).coerceAtLeast(0)) }
    }
}
