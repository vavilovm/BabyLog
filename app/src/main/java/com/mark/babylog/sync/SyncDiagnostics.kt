package com.mark.babylog.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.mark.babylog.BuildConfig
import java.io.File
import java.time.Instant

/** Bounded local sync trace. Never includes tokens, names, or full payloads. */
object SyncDiagnostics {
    private var directory: File? = null

    @Synchronized
    fun initialize(context: Context) {
        directory = File(context.filesDir, "sync-diagnostics").apply { mkdirs() }
        record("app_start version=${BuildConfig.VERSION_NAME}")
    }

    @Synchronized
    fun record(event: String) {
        Log.i("BabyLogSync", event)
        val root = directory ?: return
        try {
            val current = File(root, "current.log")
            if (current.length() >= 256 * 1024) {
                val previous = File(root, "previous.log")
                previous.delete()
                current.renameTo(previous)
            }
            current.appendText("${Instant.now()} $event\n")
        } catch (error: Exception) {
            Log.w("BabyLogSync", "Cannot write diagnostic trace", error)
        }
    }

    @Synchronized
    fun share(context: Context) {
        val output = File(context.cacheDir, "sync-diagnostics.txt")
        output.writeText(
            buildString {
                appendLine("BabyLog ${BuildConfig.VERSION_NAME}")
                directory?.let { root ->
                    listOf("previous.log", "current.log").forEach { name ->
                        val file = File(root, name)
                        if (file.exists()) append(file.readText())
                    }
                }
            }
        )
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", output)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Диагностика синхронизации",
            )
        )
    }
}
