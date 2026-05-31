package org.xs.headunitlauncher.utils

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import org.xs.headunitlauncher.App
import org.xs.headunitlauncher.BuildConfig
import org.xs.headunitlauncher.R
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

object CrashReportStore {

    private data class LaunchMetadata(
        val versionCode: Long,
        val lastUpdateTime: Long
    )

    data class PendingCrashReport(
        val file: File,
        val capturedAtMillis: Long,
        val summary: String
    )

    private const val PREFS_NAME = "settings"
    private const val KEY_PENDING_CRASH_PATH = "pending-crash-path"
    private const val KEY_PENDING_CRASH_TIME = "pending-crash-time"
    private const val KEY_PENDING_CRASH_SUMMARY = "pending-crash-summary"
    private const val KEY_PENDING_CRASH_UPDATE_TIME = "pending-crash-update-time"
    private const val KEY_HELPER_UPLOAD_PATH = "crash-helper-upload-path"
    private const val KEY_HELPER_UPLOAD_TIME = "crash-helper-upload-time"
    private const val KEY_FOREGROUND_SESSION_ACTIVE = "crash-foreground-session-active"
    private const val KEY_FOREGROUND_SESSION_SINCE = "crash-foreground-session-since"
    private const val KEY_EXPECTED_SHUTDOWN_ARMED = "crash-expected-shutdown-armed"
    private const val KEY_EXPECTED_SHUTDOWN_AT = "crash-expected-shutdown-at"
    private const val KEY_EXPECTED_SHUTDOWN_REASON = "crash-expected-shutdown-reason"
    private const val KEY_LAST_LAUNCH_UPDATE_TIME = "crash-last-launch-update-time"
    private const val KEY_LAST_LAUNCH_VERSION_CODE = "crash-last-launch-version-code"
    private const val PUBLIC_CRASH_DIR = "HUL"
    private const val AGGREGATE_REPORT_FILE = "HUL_Crash_History.txt"
    private const val MAX_BREADCRUMBS = 250
    private const val MAX_STATE_ENTRIES = 32
    private const val MAX_THREAD_FRAMES = 20
    private const val MAX_THREAD_COUNT = 24
    private const val EXPECTED_SHUTDOWN_TTL_MS = 2 * 60 * 1000L

    @Volatile
    private var isInstalled = false
    private var startedActivityCount = 0

    fun install(context: Context) {
        if (isInstalled) return

        val appContext = context.applicationContext
        val launchMetadata = readLaunchMetadata(appContext)
        maybeCreateUnexpectedShutdownReport(appContext, launchMetadata)
        persistLaunchMetadata(appContext, launchMetadata)
        mirrorPendingReportToHelperAsync(appContext)
        if (appContext is Application) {
            registerActivityCallbacks(appContext)
            registerComponentCallbacks(appContext)
        }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        appendBreadcrumb(appContext, "CrashReportStore.install")

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                appendBreadcrumb(appContext, "Uncaught exception on ${thread.name}: ${summarizeThrowable(throwable)}")
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
        val storedUpdateTime = prefs.getLong(KEY_PENDING_CRASH_UPDATE_TIME, -1L)
        if (storedUpdateTime == -1L || storedUpdateTime != readLaunchMetadata(context.applicationContext).lastUpdateTime) {
            clearPendingReport(context)
            return null
        }
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

    /**
     * Called by [ProcessDeathWatchdog] to create a shareable crash report when it detects
     * a process death that the normal foreground-session tracking missed.
     */
    fun persistWatchdogReport(context: Context, contents: String, summary: String) {
        val reportDir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!reportDir.exists()) reportDir.mkdirs()

        val timestamp = System.currentTimeMillis()
        val reportFile = reportFile(reportDir)
        appendReportEntry(reportFile, contents)
        exportPublicCopy(context, reportFile.name, reportFile.readText())

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_CRASH_PATH, reportFile.absolutePath)
            .putLong(KEY_PENDING_CRASH_TIME, timestamp)
            .putString(KEY_PENDING_CRASH_SUMMARY, summary)
            .putLong(KEY_PENDING_CRASH_UPDATE_TIME, readLaunchMetadata(context).lastUpdateTime)
            .commit()
        mirrorPendingReportToHelperAsync(context)
    }

    fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    fun noteBreadcrumb(context: Context, message: String) {
        appendBreadcrumb(context.applicationContext, message)
    }

    fun updateState(context: Context, key: String, value: String?) {
        persistStateValue(context.applicationContext, key, value)
    }

    fun markExpectedShutdown(context: Context, reason: String) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EXPECTED_SHUTDOWN_ARMED, true)
            .putLong(KEY_EXPECTED_SHUTDOWN_AT, now)
            .putString(KEY_EXPECTED_SHUTDOWN_REASON, reason)
            .commit()
        appendBreadcrumb(appContext, "CrashReportStore.expectedShutdown armed reason=$reason")
        persistStateValue(appContext, "expected_shutdown", "armed:$reason")
    }

    fun clearExpectedShutdown(context: Context, reason: String) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_EXPECTED_SHUTDOWN_ARMED)
            .remove(KEY_EXPECTED_SHUTDOWN_AT)
            .remove(KEY_EXPECTED_SHUTDOWN_REASON)
            .commit()
        appendBreadcrumb(appContext, "CrashReportStore.expectedShutdown cleared reason=$reason")
        persistStateValue(appContext, "expected_shutdown", "cleared:$reason")
    }

    private fun clearPendingReport(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_CRASH_PATH)
            .remove(KEY_PENDING_CRASH_TIME)
            .remove(KEY_PENDING_CRASH_SUMMARY)
            .remove(KEY_PENDING_CRASH_UPDATE_TIME)
            .remove(KEY_HELPER_UPLOAD_PATH)
            .remove(KEY_HELPER_UPLOAD_TIME)
            .commit()
    }

    private fun persistCrash(context: Context, thread: Thread, throwable: Throwable) {
        val reportDir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!reportDir.exists()) {
            reportDir.mkdirs()
        }

        val timestamp = System.currentTimeMillis()
        val reportFile = reportFile(reportDir)
        val reportContents = buildReport(context, thread, throwable, timestamp)
        appendReportEntry(reportFile, reportContents)
        exportPublicCopy(context, reportFile.name, reportFile.readText())

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_CRASH_PATH, reportFile.absolutePath)
            .putLong(KEY_PENDING_CRASH_TIME, timestamp)
            .putString(KEY_PENDING_CRASH_SUMMARY, summarizeThrowable(throwable))
            .putLong(KEY_PENDING_CRASH_UPDATE_TIME, readLaunchMetadata(context).lastUpdateTime)
            .commit()
        mirrorPendingReportToHelperAsync(context)
    }

    private fun reportFile(reportDir: File): File {
        return File(reportDir, AGGREGATE_REPORT_FILE)
    }

    private fun appendReportEntry(reportFile: File, entry: String) {
        reportFile.parentFile?.mkdirs()
        if (reportFile.exists() && reportFile.length() > 0L) {
            reportFile.appendText("\n\n==================== NEXT CRASH ====================\n\n")
        }
        reportFile.appendText(entry.trimEnd() + "\n")
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
            appendLine("PID: ${android.os.Process.myPid()}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("App uptime: ${formatDuration(SystemClock.elapsedRealtime() - App.appStartTime)}")
            appendLine()
            appendLine("Exception summary:")
            appendLine(summarizeThrowable(throwable))
            appendLine()
            appendLine("Memory snapshot:")
            appendLine(buildMemorySnapshot(context))
            appendLine()
            appendLine("Last known app state:")
            appendLine(readStateSnapshot(context).ifBlank { "<none>" })
            appendLine()
            appendLine("Recent breadcrumbs:")
            appendLine(readBreadcrumbs(context).ifBlank { "<none>" })
            appendLine()
            appendLine("Stack trace:")
            appendLine(stackTrace.trimEnd())
            appendLine()
            appendLine("Thread dump:")
            appendLine(buildThreadDump())

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

    private fun maybeCreateUnexpectedShutdownReport(context: Context, launchMetadata: LaunchMetadata) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wasForegroundSessionActive = prefs.getBoolean(KEY_FOREGROUND_SESSION_ACTIVE, false)
        if (!wasForegroundSessionActive || getPendingReport(context) != null) {
            return
        }

        if (prefs.getBoolean(KEY_EXPECTED_SHUTDOWN_ARMED, false)) {
            appendBreadcrumb(
                context,
                "Unexpected shutdown suppressed after expected shutdown reason=${prefs.getString(KEY_EXPECTED_SHUTDOWN_REASON, "<unknown>")}"
            )
            clearExpectedShutdown(context, "launch-suppressed")
            prefs.edit()
                .putBoolean(KEY_FOREGROUND_SESSION_ACTIVE, false)
                .remove(KEY_FOREGROUND_SESSION_SINCE)
                .commit()
            return
        }

        val previousUpdateTime = prefs.getLong(KEY_LAST_LAUNCH_UPDATE_TIME, -1L)
        val previousVersionCode = prefs.getLong(KEY_LAST_LAUNCH_VERSION_CODE, -1L)
        val isFirstInstall = previousUpdateTime == -1L || previousVersionCode == -1L
        if (isFirstInstall) {
            // First-install crash warning intentionally suppressed for now.
            prefs.edit()
                .putBoolean(KEY_FOREGROUND_SESSION_ACTIVE, false)
                .remove(KEY_FOREGROUND_SESSION_SINCE)
                .commit()
            return
        }
        val isSameInstalledBuild =
            previousUpdateTime == launchMetadata.lastUpdateTime &&
                previousVersionCode == launchMetadata.versionCode
        if (!isSameInstalledBuild) {
            prefs.edit()
                .putBoolean(KEY_FOREGROUND_SESSION_ACTIVE, false)
                .remove(KEY_FOREGROUND_SESSION_SINCE)
                .commit()
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
                clearExpectedShutdownIfExpired(application)
                if (startedActivityCount == 1) {
                    val now = System.currentTimeMillis()
                    application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_FOREGROUND_SESSION_ACTIVE, true)
                        .putLong(KEY_FOREGROUND_SESSION_SINCE, now)
                        .commit()
                }
                appendBreadcrumb(application, "${activity.localClassName}:onStart")
                persistStateValue(application, "top_activity", activity.localClassName)
                persistStateValue(application, "activity_count", startedActivityCount.toString())
            }

            override fun onActivityResumed(activity: Activity) {
                appendBreadcrumb(application, "${activity.localClassName}:onResume")
                persistStateValue(application, "resumed_activity", activity.localClassName)
            }

            override fun onActivityPaused(activity: Activity) {
                appendBreadcrumb(application, "${activity.localClassName}:onPause")
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) {
                    application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_FOREGROUND_SESSION_ACTIVE, false)
                        .remove(KEY_FOREGROUND_SESSION_SINCE)
                        .commit()
                }
                appendBreadcrumb(application, "${activity.localClassName}:onStop")
                persistStateValue(application, "activity_count", startedActivityCount.toString())
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                appendBreadcrumb(application, "${activity.localClassName}:onDestroy")
            }
        })
    }

    private fun clearExpectedShutdownIfExpired(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val armedAt = prefs.getLong(KEY_EXPECTED_SHUTDOWN_AT, 0L)
        val isArmed = prefs.getBoolean(KEY_EXPECTED_SHUTDOWN_ARMED, false)
        if (!isArmed || armedAt <= 0L) return
        if (System.currentTimeMillis() - armedAt < EXPECTED_SHUTDOWN_TTL_MS) return
        clearExpectedShutdown(context, "expired")
    }

    private fun registerComponentCallbacks(application: Application) {
        application.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit

            override fun onLowMemory() {
                appendBreadcrumb(application, "ComponentCallbacks2.onLowMemory")
                persistStateValue(application, "last_trim_level", "LOW_MEMORY")
            }

            override fun onTrimMemory(level: Int) {
                appendBreadcrumb(application, "ComponentCallbacks2.onTrimMemory($level)")
                persistStateValue(application, "last_trim_level", level.toString())
            }
        })
    }

    private fun persistUnexpectedShutdown(context: Context, sessionStartedAt: Long) {
        val reportDir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!reportDir.exists()) {
            reportDir.mkdirs()
        }

        val timestamp = System.currentTimeMillis()
        val reportFile = reportFile(reportDir)
        val reportContents = buildUnexpectedShutdownReport(context, timestamp, sessionStartedAt)
        appendReportEntry(reportFile, reportContents)
        exportPublicCopy(context, reportFile.name, reportFile.readText())

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_CRASH_PATH, reportFile.absolutePath)
            .putLong(KEY_PENDING_CRASH_TIME, timestamp)
            .putString(
                KEY_PENDING_CRASH_SUMMARY,
                "Unexpected shutdown${if (sessionStartedAt > 0L) " after active session" else ""}"
            )
            .putLong(KEY_PENDING_CRASH_UPDATE_TIME, readLaunchMetadata(context).lastUpdateTime)
            .commit()
        mirrorPendingReportToHelperAsync(context)
    }

    private fun persistLaunchMetadata(context: Context, metadata: LaunchMetadata) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_LAUNCH_VERSION_CODE, metadata.versionCode)
            .putLong(KEY_LAST_LAUNCH_UPDATE_TIME, metadata.lastUpdateTime)
            .commit()
    }

    private fun readLaunchMetadata(context: Context): LaunchMetadata {
        val packageInfo = context.packageManager.getPackageInfoCompat(context.packageName)
        return LaunchMetadata(
            versionCode = packageInfo.longVersionCodeCompat(),
            lastUpdateTime = packageInfo.lastUpdateTime
        )
    }

    private fun android.content.pm.PackageManager.getPackageInfoCompat(packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, 0)
        }
    }

    private fun PackageInfo.longVersionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
    }

    private fun mirrorPendingReportToHelperAsync(context: Context) {
        val appContext = context.applicationContext
        Thread {
            try {
                mirrorPendingReportToHelper(appContext)
            } catch (_: Throwable) {
                // Best effort only.
            }
        }.start()
    }

    private fun mirrorPendingReportToHelper(context: Context) {
        val report = getPendingReport(context) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alreadyUploaded =
            prefs.getString(KEY_HELPER_UPLOAD_PATH, null) == report.file.absolutePath &&
                prefs.getLong(KEY_HELPER_UPLOAD_TIME, -1L) == report.file.lastModified()
        if (alreadyUploaded) return

        val helperBaseUrl = resolveHelperTransferBaseUrl(context) ?: return
        val uploadName = buildHelperUploadName(report.file)
        val encodedName = URLEncoder.encode(uploadName, "UTF-8")
        val connection = (URL("${helperBaseUrl}upload?name=$encodedName").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "text/plain")
            setFixedLengthStreamingMode(report.file.length())
        }

        try {
            connection.outputStream.use { output ->
                report.file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                appendBreadcrumb(context, "Crash report mirrored to helper transfer as $uploadName")
                persistStateValue(context, "helper_crash_upload", uploadName)
                prefs.edit()
                    .putString(KEY_HELPER_UPLOAD_PATH, report.file.absolutePath)
                    .putLong(KEY_HELPER_UPLOAD_TIME, report.file.lastModified())
                    .commit()
            } else {
                appendBreadcrumb(context, "Crash report helper upload failed code=$responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveHelperTransferBaseUrl(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        @Suppress("DEPRECATION")
        val connectionInfo = wifiManager.connectionInfo ?: return null
        if (connectionInfo.networkId == -1) return null
        @Suppress("DEPRECATION")
        val gateway = wifiManager.dhcpInfo?.gateway ?: 0
        if (gateway == 0) return null
        return "http://${formatIpv4(gateway)}:8787/"
    }

    private fun formatIpv4(address: Int): String {
        return listOf(
            address and 0xff,
            address shr 8 and 0xff,
            address shr 16 and 0xff,
            address shr 24 and 0xff
        ).joinToString(".")
    }

    private fun buildHelperUploadName(file: File): String {
        val device = "${Build.MANUFACTURER}_${Build.MODEL}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${device}_${file.name}"
    }

    private fun exportPublicCopy(context: Context, fileName: String, contents: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_CRASH_DIR"
                    )
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream).use { writer ->
                        writer.write(contents)
                    }
                }
                return
            }

            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val publicDir = File(downloadsDir, PUBLIC_CRASH_DIR)
            if (!publicDir.exists()) {
                publicDir.mkdirs()
            }
            File(publicDir, fileName).writeText(contents)
        } catch (_: Exception) {
            // Best effort only. App-private sharing still remains available.
        }
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
            appendLine("App uptime: ${formatDuration(SystemClock.elapsedRealtime() - App.appStartTime)}")
            appendLine()
            appendLine("Exception summary:")
            appendLine("The previous foreground session ended unexpectedly before Android reported a clean stop.")
            if (sessionStartedAt > 0L) {
                appendLine("Last foreground session started: ${formatTimestamp(sessionStartedAt)}")
            }
            appendLine()
            appendLine("Notes:")
            appendLine("This usually means a native crash, watchdog kill, system restart, or another abrupt process death that bypassed the Java uncaught exception handler.")
            appendLine()
i
            // Include watchdog death info
            val watchdogInfo = ProcessDeathWatchdog.getPreviousDeathInfo(context)
            if (watchdogInfo != null) {
                appendLine("Watchdog info:")
                appendLine(watchdogInfo)
                appendLine()
            }

            // Include watchdog death log file if present
            val watchdogLog = try {
                val f = File(context.filesDir, "watchdog_deaths.log")
                if (f.exists()) f.readText().take(2000) else null
            } catch (_: Exception) { null }
            if (!watchdogLog.isNullOrBlank()) {
                appendLine("Watchdog death history:")
                appendLine(watchdogLog.trimEnd())
                appendLine()
            }

            appendLine("Memory snapshot:")
            appendLine(buildMemorySnapshot(context))
            appendLine()
            appendLine("Last known app state:")
            appendLine(readStateSnapshot(context).ifBlank { "<none>" })
            appendLine()
            appendLine("Recent breadcrumbs:")
            appendLine(readBreadcrumbs(context).ifBlank { "<none>" })

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

    private fun breadcrumbFile(context: Context): File = File(context.filesDir, "hul_breadcrumbs.log")

    private fun stateFile(context: Context): File = File(context.filesDir, "hul_state_snapshot.txt")

    private fun appendBreadcrumb(context: Context, message: String) {
        val line = "${formatTimestamp(System.currentTimeMillis())} | $message\n"
        try {
            val file = breadcrumbFile(context)
            val lines = if (file.exists()) file.readLines().takeLast(MAX_BREADCRUMBS - 1) else emptyList()
            file.parentFile?.mkdirs()
            file.writeText((lines + line.trimEnd()).joinToString(separator = "\n", postfix = "\n"))
        } catch (_: Exception) {
            // Best effort only.
        }
    }

    private fun readBreadcrumbs(context: Context): String {
        return try {
            val file = breadcrumbFile(context)
            if (!file.exists()) "" else file.readText().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun persistStateValue(context: Context, key: String, value: String?) {
        try {
            val file = stateFile(context)
            val state = linkedMapOf<String, String>()
            if (file.exists()) {
                file.forEachLine { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) {
                        state[line.substring(0, idx)] = line.substring(idx + 1)
                    }
                }
            }
            if (value == null) {
                state.remove(key)
            } else {
                state[key] = value
            }
            val trimmed = state.entries.toList().takeLast(MAX_STATE_ENTRIES)
            file.parentFile?.mkdirs()
            file.writeText(trimmed.joinToString(separator = "\n") { "${it.key}=${it.value}" } + "\n")
        } catch (_: Exception) {
            // Best effort only.
        }
    }

    private fun readStateSnapshot(context: Context): String {
        return try {
            val file = stateFile(context)
            if (!file.exists()) "" else file.readText().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun buildMemorySnapshot(context: Context): String {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { info ->
            activityManager?.getMemoryInfo(info)
        }
        val runtime = Runtime.getRuntime()
        val usedJavaMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxJavaMb = runtime.maxMemory() / (1024 * 1024)
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val pssKb = try {
            Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        } catch (_: Throwable) {
            -1
        }
        return buildString {
            append("java=${usedJavaMb}MB/${maxJavaMb}MB")
            append(", native=${nativeHeapMb}MB")
            if (pssKb >= 0) append(", pss=${pssKb}KB")
            append(", avail=${memoryInfo.availMem / (1024 * 1024)}MB")
            append(", lowMemory=${memoryInfo.lowMemory}")
            append(", threshold=${memoryInfo.threshold / (1024 * 1024)}MB")
        }
    }

    private fun buildThreadDump(): String {
        return buildString {
            Thread.getAllStackTraces()
                .entries
                .sortedBy { it.key.name }
                .take(MAX_THREAD_COUNT)
                .forEach { (thread, stackTrace) ->
                    appendLine("\"${thread.name}\" state=${thread.state} id=${thread.id}")
                    stackTrace.take(MAX_THREAD_FRAMES).forEach { frame ->
                        appendLine("  at $frame")
                    }
                    if (stackTrace.size > MAX_THREAD_FRAMES) {
                        appendLine("  ... ${stackTrace.size - MAX_THREAD_FRAMES} more")
                    }
                }
        }.trimEnd()
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02dh %02dm %02ds", hours, minutes, seconds)
    }
}
