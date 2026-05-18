package org.xs.headunitlauncher.utils

import android.content.Context
import android.os.Build
import org.xs.headunitlauncher.BuildConfig
import org.xs.headunitlauncher.R
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

object CrashReportStore {

    data class PendingCrashReport(
        val file: File,
        val capturedAtMillis: Long,
        val summary: String
    )

    private const val PREFS_NAME = "settings"
    private const val KEY_PENDING_CRASH_PATH = "pending-crash-path"
    private const val KEY_PENDING_CRASH_TIME = "pending-crash-time"
    private const val KEY_PENDING_CRASH_SUMMARY = "pending-crash-summary"
    private const val MAX_CRASH_REPORTS = 5

    @Volatile
    private var isInstalled = false

    fun install(context: Context) {
        if (isInstalled) return

        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                persistCrash(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Best effort only. Never block the system crash flow if reporting fails.
            }

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }

        isInstalled = true
    }

    fun getPendingReport(context: Context): PendingCrashReport? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_PENDING_CRASH_PATH, null) ?: return null
        val file = File(path)
        if (!file.exists()) {
            clearPendingReport(context)
            return null
        }

        return PendingCrashReport(
            file = file,
            capturedAtMillis = prefs.getLong(KEY_PENDING_CRASH_TIME, file.lastModified()),
            summary = prefs.getString(KEY_PENDING_CRASH_SUMMARY, file.name).orEmpty()
        )
    }

    fun sharePendingReport(context: Context): Boolean {
        val report = getPendingReport(context) ?: return false
        LogExporter.shareLogFile(
            context = context,
            file = report.file,
            chooserTitle = context.getString(R.string.share_crash_report)
        )
        return true
    }

    fun ignorePendingReport(context: Context) {
        val report = getPendingReport(context)
        report?.file?.delete()
        clearPendingReport(context)
    }

    fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    private fun clearPendingReport(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_CRASH_PATH)
            .remove(KEY_PENDING_CRASH_TIME)
            .remove(KEY_PENDING_CRASH_SUMMARY)
            .commit()
    }

    private fun persistCrash(context: Context, thread: Thread, throwable: Throwable) {
        val reportDir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!reportDir.exists()) {
            reportDir.mkdirs()
        }
        rotateReports(reportDir)

        val timestamp = System.currentTimeMillis()
        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
        val reportFile = File(reportDir, "HUL_Crash_$fileStamp.txt")
        reportFile.writeText(buildReport(context, thread, throwable, timestamp))

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_CRASH_PATH, reportFile.absolutePath)
            .putLong(KEY_PENDING_CRASH_TIME, timestamp)
            .putString(KEY_PENDING_CRASH_SUMMARY, summarizeThrowable(throwable))
            .commit()
    }

    private fun rotateReports(reportDir: File) {
        val files = reportDir.listFiles { _, name -> name.startsWith("HUL_Crash_") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        files.drop(MAX_CRASH_REPORTS - 1).forEach { it.delete() }
    }

    private fun buildReport(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        timestamp: Long
    ): String {
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use { printWriter ->
                throwable.printStackTrace(printWriter)
            }
        }.toString()

        return buildString {
            appendLine("HeadUnitLauncher crash report")
            appendLine("Generated: ${formatTimestamp(timestamp)}")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Package: ${context.packageName}")
            appendLine("Thread: ${thread.name}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            appendLine("Exception summary:")
            appendLine(summarizeThrowable(throwable))
            appendLine()
            appendLine("Stack trace:")
            appendLine(stackTrace.trimEnd())

            val logcat = readRecentLogcat()
            if (logcat.isNotBlank()) {
                appendLine()
                appendLine("Recent logcat:")
                appendLine(logcat.trimEnd())
            }
        }
    }

    private fun summarizeThrowable(throwable: Throwable): String {
        val type = throwable::class.java.simpleName.ifBlank { "Throwable" }
        val message = throwable.message?.trim().orEmpty()
        val summary = if (message.isBlank()) type else "$type: $message"
        return summary.take(180)
    }

    private fun readRecentLogcat(): String {
        val commands = listOf(
            arrayOf("logcat", "-d", "-t", "200", "-v", "threadtime"),
            arrayOf("logcat", "-d", "-v", "threadtime")
        )

        commands.forEach { command ->
            try {
                val process = Runtime.getRuntime().exec(command)
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor(2, TimeUnit.SECONDS)
                if (output.isNotBlank()) {
                    return output
                }
            } catch (_: Exception) {
                // Fall through to the next strategy.
            }
        }

        return ""
    }
}
