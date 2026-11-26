package io.github.tohrutodo.tohru.debug

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.tohrutodo.tohru.io.AtomicFiles
import io.github.tohrutodo.tohru.sync.SyncSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// Local-only diagnostics for bug reports. No task bodies or credentials are
// included; the crash handler writes stack traces under files/debug.
object Diagnostics {
    private const val MAX_LOG_CHARS = 200_000
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val directory = crashDirectory(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                directory.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                File(directory, "crash-$stamp.txt").writeText(
                    buildString {
                        appendLine("thread: ${thread.name}")
                        appendLine("time: ${Date()}")
                        appendLine(error.stackTraceToString())
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun export(context: Context, prefs: SharedPreferences): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val target = File(directory, "tohru-log-$stamp.txt")
        AtomicFiles.writeText(
            target,
            buildString {
                appendLine("Tohru diagnostics")
                appendLine("time: ${Date()}")
                appendLine("package: ${context.packageName}")
                appendLine("version: ${version(context)}")
                appendLine("android: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("repo: ${SyncSettings.readRepoPath(prefs, context.filesDir)}")
                appendLine("remote configured: ${!prefs.getString(SyncSettings.KEY_REMOTE, "").isNullOrBlank()}")
                appendLine("task files: ${taskCount(prefs, context)}")
                appendLine()
                appendLine("===== logcat =====")
                appendLine(readLogcat())
                appendLine()
                appendLine("===== crashes =====")
                val crashes = crashDirectory(context).listFiles().orEmpty().sortedBy { it.name }
                if (crashes.isEmpty()) appendLine("(none)")
                crashes.forEach { file ->
                    appendLine("--- ${file.name} ---")
                    appendLine(runCatching { file.readText() }.getOrDefault("(unreadable)"))
                }
            },
        )
        return target
    }

    private fun crashDirectory(context: Context): File = File(context.filesDir, "debug")

    private fun taskCount(prefs: SharedPreferences, context: Context): Int {
        val tasks = File(SyncSettings.readRepoPath(prefs, context.filesDir), "tasks")
        return tasks.walkTopDown().count { file -> file.isFile && file.extension == "md" }
    }

    private fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun readLogcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "1500")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(3, TimeUnit.SECONDS)
        output.take(MAX_LOG_CHARS)
    }.getOrElse { "logcat unavailable: ${it.message}" }
}
