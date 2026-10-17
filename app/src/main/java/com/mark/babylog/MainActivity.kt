package com.mark.babylog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mark.babylog.data.*
import com.mark.babylog.sync.AppSurfaceSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var inviteRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptInvite(intent)
        enableEdgeToEdge()
        setContent {
            BabyTheme {
                NotificationPermission()
                val vm: MainViewModel = viewModel()
                SleepScreen(
                    vm,
                    initialInvite = inviteRequest,
                    onInviteConsumed = { inviteRequest = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptInvite(intent)
    }

    private fun acceptInvite(intent: Intent?) {
        inviteRequest =
            intent
                ?.data
                ?.takeIf { it.scheme == "babylog" && it.host == "join" }
                ?.getQueryParameter("code") ?: inviteRequest
    }

    companion object {
        const val EXTRA_OPEN_BOTTLE = "com.mark.babylog.OPEN_BOTTLE"

        fun bottleIntent(context: Context) =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_BOTTLE, true)
    }
}

@Composable
private fun NotificationPermission() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) scope.launch { AppSurfaceSync.refresh(context) }
        }
    LaunchedEffect(Unit) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
internal fun FamilyDialog(
    membership: FamilyMembership?,
    vm: MainViewModel,
    initialCode: String,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var code by remember {
        mutableStateOf(initialCode.ifBlank { membership?.inviteCode.orEmpty() })
    }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val members = vm.familyMembers.collectAsStateWithLifecycle().value
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (membership == null) "Семейная синхронизация" else "Ваша семья") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (membership == null) {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("Ваше имя") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        code,
                        { code = it.uppercase() },
                        label = { Text("Код приглашения") },
                        singleLine = true,
                    )
                    Text(
                        "Оставьте код пустым, чтобы создать новую семью.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "Участники: ${members.joinToString{it.displayName}.ifBlank{membership.displayName}}"
                    )
                    if (code.isNotBlank()) {
                        QrCode(code)
                        Text("Код: $code", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            busy = true
                            vm.createInvite { result ->
                                busy = false
                                result.onSuccess { code = it }.onFailure { error = it.message }
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text("Создать приглашение")
                    }
                    OutlinedButton(onClick = { vm.retrySync() }) { Text("Синхронизировать сейчас") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (membership == null)
                Button(
                    onClick = {
                        busy = true
                        if (code.isBlank())
                            vm.createFamily(name) { result ->
                                busy = false
                                result.onSuccess { code = it }.onFailure { error = it.message }
                            }
                        else
                            vm.joinFamily(code, name) { result ->
                                busy = false
                                result.onSuccess { onDismiss() }.onFailure { error = it.message }
                            }
                    },
                    enabled = !busy && name.isNotBlank(),
                ) {
                    Text(if (code.isBlank()) "Создать семью" else "Подключиться")
                }
            else TextButton(onClick = onDismiss) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun QrCode(inviteCode: String) {
    var bitmap by remember(inviteCode) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(inviteCode) {
        bitmap =
            withContext(Dispatchers.Default) {
                val matrix =
                    com.google.zxing
                        .MultiFormatWriter()
                        .encode(
                            "babylog://join?code=$inviteCode",
                            com.google.zxing.BarcodeFormat.QR_CODE,
                            420,
                            420,
                        )
                android.graphics.Bitmap.createBitmap(
                        420,
                        420,
                        android.graphics.Bitmap.Config.RGB_565,
                    )
                    .apply {
                        for (y in 0 until 420) for (x in 0 until 420) setPixel(
                            x,
                            y,
                            if (matrix[x, y]) android.graphics.Color.BLACK
                            else android.graphics.Color.WHITE,
                        )
                    }
            }
    }
    if (bitmap == null)
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    else Image(bitmap!!.asImageBitmap(), "QR-код приглашения", Modifier.size(180.dp))
}

@Composable
internal fun SleepSideImage(position: SleepPosition, modifier: Modifier) {
    Image(
        painterResource(
            if (position == SleepPosition.LEFT) R.drawable.duck_sleep_left
            else R.drawable.duck_sleep_right
        ),
        if (position == SleepPosition.LEFT) "Левый бок" else "Правый бок",
        modifier,
    )
}

@Composable
internal fun MinuteShortcuts(onMove: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(-5L, -1L, 1L, 5L).forEach { delta ->
            AssistChip(
                onClick = { onMove(delta) },
                label = {
                    Text(if (delta > 0) "+$delta" else "$delta", fontWeight = FontWeight.Bold)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

internal fun moveMinutes(time: Long, delta: Long) = time + delta * 60_000
