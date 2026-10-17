package com.mark.babylog

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import org.json.JSONObject

/** Explicit, user-initiated updates. Android performs final install approval. */
@Composable
fun UpdateButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val russian = context.resources.configuration.locales[0].language == "ru"
    fun label(en: String, ru: String) = if (russian) ru else en
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }
    fun install(file: File) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    FileProvider.getUriForFile(context, context.packageName + ".files", file),
                    "application/vnd.android.package-archive",
                )
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            ready?.let { file ->
                if (context.packageManager.canRequestPackageInstalls())
                    runCatching { install(file) }.onFailure { message = it.localizedMessage }
                else
                    message =
                        label(
                            "Allow installation, then tap Update again.",
                            "Разрешите установку и нажмите обновление снова.",
                        )
            }
        }
    TextButton(
        enabled = !running,
        onClick = {
            running = true
            scope.launch {
                try {
                    val file =
                        withContext(Dispatchers.IO) {
                            fun download(url: String, consume: (java.io.InputStream) -> Unit) {
                                val connection = URL(url).openConnection() as HttpURLConnection
                                connection.connectTimeout = 15000
                                connection.readTimeout = 30000
                                connection.setRequestProperty(
                                    "Accept",
                                    "application/vnd.github+json",
                                )
                                connection.setRequestProperty("User-Agent", context.packageName)
                                try {
                                    check(connection.responseCode == 200) {
                                        "GitHub download failed (${connection.responseCode})"
                                    }
                                    connection.inputStream.use(consume)
                                } finally {
                                    connection.disconnect()
                                }
                            }
                            var json = ""
                            download(
                                "https://api.github.com/repos/vavilovm/BabyLog/releases/latest"
                            ) {
                                json = it.bufferedReader().readText()
                            }
                            val assets = JSONObject(json).getJSONArray("assets")
                            val asset =
                                (0 until assets.length())
                                    .map { assets.getJSONObject(it) }
                                    .firstOrNull { it.getString("name") == "app-release.apk" }
                                    ?: error("No release APK found")
                            val url = asset.getString("browser_download_url")
                            check(
                                url.startsWith(
                                    "https://github.com/vavilovm/BabyLog/releases/download/"
                                )
                            ) {
                                "Unexpected download URL"
                            }
                            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
                            val target = File(directory, "latest.apk")
                            try {
                                download(url) { input ->
                                    target.outputStream().use { output ->
                                        val buffer = ByteArray(16384)
                                        var total = 0L
                                        while (true) {
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            total += count
                                            check(total <= 150L * 1024 * 1024) {
                                                "Update exceeds download limit"
                                            }
                                            output.write(buffer, 0, count)
                                        }
                                        check(total == asset.getLong("size")) {
                                            "Incomplete APK download"
                                        }
                                    }
                                }
                                @Suppress("DEPRECATION")
                                val signingFlags =
                                    if (Build.VERSION.SDK_INT >= 28)
                                        PackageManager.GET_SIGNING_CERTIFICATES
                                    else PackageManager.GET_SIGNATURES
                                @Suppress("DEPRECATION")
                                val archive =
                                    context.packageManager.getPackageArchiveInfo(
                                        target.path,
                                        signingFlags,
                                    ) ?: error("Invalid APK")
                                @Suppress("DEPRECATION")
                                val installed =
                                    context.packageManager.getPackageInfo(
                                        context.packageName,
                                        signingFlags,
                                    )
                                check(archive.packageName == installed.packageName) {
                                    "Wrong application"
                                }
                                @Suppress("DEPRECATION")
                                fun signers(info: android.content.pm.PackageInfo): Set<String> =
                                    (if (Build.VERSION.SDK_INT >= 28)
                                            info.signingInfo?.apkContentsSigners
                                        else info.signatures)
                                        .orEmpty()
                                        .map { it.toCharsString() }
                                        .toSet()
                                val certificates = signers(archive)
                                val trusted = signers(installed)
                                check(certificates.isNotEmpty() && certificates == trusted) {
                                    "Update signing key does not match the installed app"
                                }
                                check(
                                    PackageInfoCompat.getLongVersionCode(archive) >
                                        PackageInfoCompat.getLongVersionCode(installed)
                                ) {
                                    label(
                                        "You already have this version or a newer one.",
                                        "У вас уже установлена эта или более новая версия.",
                                    )
                                }
                                target
                            } catch (e: Exception) {
                                target.delete()
                                throw e
                            }
                        }
                    ready = file
                    if (context.packageManager.canRequestPackageInstalls()) install(file)
                    else
                        permission.launch(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + context.packageName),
                            )
                        )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = e.localizedMessage ?: "Update failed"
                } finally {
                    running = false
                }
            }
        },
    ) {
        Text(if (running) label("Downloading…", "Загрузка…") else label("Update app", "Обновить"))
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(label("App update", "Обновление")) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}
