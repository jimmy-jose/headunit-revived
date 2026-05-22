package org.xs.headunitlauncher.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
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
    private const val KEY_FOREGROUND_SESSION_ACTIVE = "crash-foreground-session-active"
    private const val KEY_FOREGROUND_SESSION_SINCE = "crash-foreground-session-since"
    private const val MAX_CRASH_REPORTS = 5

    @Volatile
    private var isInstalled = false
    private var startedActivityCount = 0

    fun install(context: Context) {
        if (isInstalled) return

        val appContext = context.applicationContext
        maybeCreateUnexpectedShutdownReport(appContext)
        if (appContext is Application) {
            registerActivityCallbacks(appContext)
        }
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

            val appLogs = AppLog.readRecentLogs()
            if (appLogs.isNotBlank()) {
                appendLine()
                appendLine("Recent HUL app logs:")
                appendLine(appLogs.trimEnd())
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

    private fun maybeCreateUnexpectedShutdownReport(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wasForegroundSessionActive = prefs.getBoolean(KEY_FOREGROUND_SESSION_ACTIVE, false)
        if (!wasForegroundSessionActive || getPendingReport(context) != null) {
            return
        }

        val sessionStartedAt = prefs.getLong(KEY_FOREGROUND_SESSION_SINCE, 0L)
        persistUnexpectedShutdown(context, sessionStartedAt)
        prefs.edit()
            .putBoolean(KEY_FOREGROUND_SESSION_ACTIVE, false)
            .remove(KEY_FOREGROUND_SESSION_SINCE)
            .commit()
    }

    private fun registerActivityCallbacks(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                startedActivityCount += 1
                if (startedActivityCount == 1) {
                    val now = System.currentTimeMillis()
                    application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_FOREGROUND_SESSION_ACTIVE, true)
                        .putLong(KEY_FOREGROUND_SESSION_SINCE, now)
                        .commit()
                }
            }

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) {
                    application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_FOREGROUND_SESSION_ACTIVE, false)
                        .remove(KEY_FOREGROUND_SESSION_SINCE)
                        .commit()
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun persistUnexpectedShutdown(context: Context, sessionStartedAt: Long) {
        val reportDir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!reportDir.exists()) {
            reportDir.mkdirs()
        }
        rotateReports(reportDir)

        val timestamp = System.currentTimeMillis()
        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
        val reportFile = File(reportDir, "HUL_Crash_$fileStamp.txt")
        reportFile.writeText(buildUnexpectedShutdownReport(context, timestamp, sessionStartedAt))

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_CRASH_PATH, reportFile.absolutePath)
            .putLong(KEY_PENDING_CRASH_TIME, timestamp)
            .putString(
                KEY_PENDING_CRASH_SUMMARY,
                "Unexpected shutdown${if (sessionStartedAt > 0L) " after active session" else ""}"
            )
            .commit()
    }

    private fun buildUnexpectedShutdownReport(
        context: Context,
        timestamp: Long,
        sessionStartedAt: Long
    ): String {
        return buildString {
            appendLine("HeadUnitLauncher crash report")
            appendLine("Generated: ${formatTimestamp(timestamp)}")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Package: ${context.packageName}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            appendLine("Exception summary:")
            appendLine("The previous foreground session ended unexpectedly before Android reported a clean stop.")
            if (sessionStartedAt > 0L) {
                appendLine("Last foreground session started: ${formatTimestamp(sessionStartedAt)}")
            }
            appendLine()
            appendLine("Notes:")
            appendLine("This usually means a native crash, watchdog kill, system restart, or another abrupt process death that bypassed the Java uncaught exception handler.")

            val logcat = readRecentLogcat()
            if (logcat.isNotBlank()) {
                appendLine()
                appendLine("Recent logcat:")
                appendLine(logcat.trimEnd())
            }

            val appLogs = AppLog.readRecentLogs()
            if (appLogs.isNotBlank()) {
                appendLine()
                appendLine("Recent HUL app logs:")
                appendLine(appLogs.trimEnd())
            }
        }
    }
}
