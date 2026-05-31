package org.xs.headunitlauncher.utils

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A lightweight watchdog that periodically writes a heartbeat timestamp to SharedPreferences.
 * If the process dies unexpectedly (native crash, OOM kill, etc.), the last heartbeat tells us
 * exactly when the process was last alive. This gives us sub-second accuracy on crash timing
 * even for native crashes that bypass the Java uncaught exception handler.
 *
 * Also captures thread dumps and memory state periodically so the crash report has
 * more context about what was happening right before death.
 */
object ProcessDeathWatchdog {

    private const val PREFS_NAME = "process_watchdog"
    private const val KEY_LAST_HEARTBEAT = "last_heartbeat_ms"
    private const val KEY_LAST_HEARTBEAT_WALL = "last_heartbeat_wall"
    private const val KEY_LAST_STATE_DUMP = "last_state_dump"
    private const val KEY_PROCESS_START_TIME = "process_start_time"
    private const val KEY_LAST_ACTIVITY_STATE = "last_activity_state"
    private const val KEY_DEATH_COUNT = "death_count"
    private const val KEY_LAST_DEATH_WALL = "last_death_wall"

    private const val HEARTBEAT_INTERVAL_MS = 5_000L // 5 seconds
    private const val STATE_DUMP_INTERVAL_MS = 30_000L // 30 seconds

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var appContext: Context? = null
    @Volatile private var isRunning = false
    private var lastStateDumpElapsed = 0L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun start(context: Context) {
        if (isRunning) return
        appContext = context.applicationContext

        // Check if previous session ended cleanly
        detectPreviousDeath(context.applicationContext)

        val thread = HandlerThread("ProcessDeathWatchdog").apply { start() }
        handlerThread = thread
        handler = Handler(thread.looper)
        isRunning = true

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_PROCESS_START_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_ACTIVITY_STATE, "started")
            .apply()

        handler?.post(heartbeatRunnable)
    }

    fun stop() {
        isRunning = false
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quit()
        handlerThread = null
        handler = null

        // Mark clean shutdown
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LAST_ACTIVITY_STATE, "clean-shutdown")
                .putLong(KEY_LAST_HEARTBEAT, 0L) // Clear so we don't false-detect death
                .apply()
        }
    }

    fun noteActivityState(state: String) {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LAST_ACTIVITY_STATE, state)
                .apply()
        }
    }

    /**
     * Returns info about the previous process death, or null if it shut down cleanly.
     */
    fun getPreviousDeathInfo(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deathCount = prefs.getInt(KEY_DEATH_COUNT, 0)
        val lastDeathWall = prefs.getLong(KEY_LAST_DEATH_WALL, 0L)
        if (deathCount == 0 || lastDeathWall == 0L) return null
        return "Previous deaths=$deathCount, last=${dateFormat.format(Date(lastDeathWall))}"
    }

    private fun detectPreviousDeath(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)
        val lastState = prefs.getString(KEY_LAST_ACTIVITY_STATE, null)

        // If last heartbeat is non-zero and state wasn't "clean-shutdown", process died unexpectedly
        if (lastHeartbeat > 0L && lastState != "clean-shutdown") {
            val lastHeartbeatWall = prefs.getString(KEY_LAST_HEARTBEAT_WALL, "unknown") ?: "unknown"
            val lastDump = prefs.getString(KEY_LAST_STATE_DUMP, "none") ?: "none"
            val deathCount = prefs.getInt(KEY_DEATH_COUNT, 0) + 1

            AppLog.e("[WATCHDOG] Previous process died unexpectedly!")
            AppLog.e("[WATCHDOG] Last heartbeat: $lastHeartbeatWall")
            AppLog.e("[WATCHDOG] Last activity state: $lastState")
            AppLog.e("[WATCHDOG] Last state dump: $lastDump")
            AppLog.e("[WATCHDOG] Total unexpected deaths: $deathCount")

            CrashReportStore.noteBreadcrumb(
                context,
                "WATCHDOG: Previous death detected! lastHeartbeat=$lastHeartbeatWall state=$lastState deaths=$deathCount"
            )
            CrashReportStore.updateState(context, "watchdog_prev_death", "heartbeat=$lastHeartbeatWall state=$lastState count=$deathCount")
            CrashReportStore.updateState(context, "watchdog_prev_dump", lastDump)

            prefs.edit()
                .putInt(KEY_DEATH_COUNT, deathCount)
                .putLong(KEY_LAST_DEATH_WALL, System.currentTimeMillis())
                .apply()

            // Write to a persistent file for debugging
            writeDeathLog(context, lastHeartbeatWall, lastState, lastDump, deathCount)

            // If CrashReportStore doesn't already have a pending report, create one
            // so the user sees the banner and can share the watchdog death info.
            if (CrashReportStore.getPendingReport(context) == null) {
                createWatchdogCrashReport(context, lastHeartbeatWall, lastState, lastDump, deathCount)
            }
        } else {
            // Clean start - reset death count tracking
            prefs.edit().remove(KEY_DEATH_COUNT).apply()
        }
    }

    private fun writeDeathLog(context: Context, heartbeat: String, state: String?, dump: String, deathCount: Int) {
        try {
            val file = File(context.filesDir, "watchdog_deaths.log")
            val entry = buildString {
                appendLine("=== Unexpected death #$deathCount at ${dateFormat.format(Date())} ===")
                appendLine("Last heartbeat: $heartbeat")
                appendLine("Last activity state: $state")
                appendLine("Last state dump: $dump")
                appendLine()
            }
            // Keep only last 10 entries (approx)
            val existing = if (file.exists() && file.length() < 50_000) file.readText() else ""
            file.writeText(entry + existing)
        } catch (e: Exception) {
            AppLog.w("[WATCHDOG] Failed to write death log: ${e.message}")
        }
    }

    /**
     * Creates a crash report via CrashReportStore so the user sees the share banner
     * on the home screen. This is called when the watchdog detects a death that
     * CrashReportStore's own detection missed (e.g., process killed while no
     * foreground activity session was active).
     */
    private fun createWatchdogCrashReport(
        context: Context,
        lastHeartbeat: String,
        lastState: String?,
        lastDump: String,
        deathCount: Int
    ) {
        try {
            CrashReportStore.persistWatchdogReport(
                context,
                buildString {
                    appendLine("HeadUnitLauncher watchdog death report")
                    appendLine("Generated: ${dateFormat.format(Date())}")
                    appendLine("Death count: $deathCount")
                    appendLine()
                    appendLine("Process died unexpectedly (detected by heartbeat watchdog).")
                    appendLine("Last heartbeat: $lastHeartbeat")
                    appendLine("Last activity state: $lastState")
                    appendLine()
                    appendLine("Last state dump:")
                    appendLine(lastDump)
                    appendLine()
                    // Include death history
                    val historyFile = File(context.filesDir, "watchdog_deaths.log")
                    if (historyFile.exists()) {
                        appendLine("Death history:")
                        appendLine(historyFile.readText().take(3000))
                    }
                },
                summary = "Process killed (watchdog #$deathCount, state=$lastState)"
            )
        } catch (e: Exception) {
            AppLog.w("[WATCHDOG] Failed to create crash report: ${e.message}")
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val ctx = appContext ?: return

            try {
                val now = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                val wallStr = dateFormat.format(Date(now))

                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                    .putLong(KEY_LAST_HEARTBEAT, now)
                    .putString(KEY_LAST_HEARTBEAT_WALL, wallStr)

                // Periodically capture more detailed state
                if (elapsed - lastStateDumpElapsed > STATE_DUMP_INTERVAL_MS) {
                    lastStateDumpElapsed = elapsed
                    val stateDump = captureStateDump()
                    editor.putString(KEY_LAST_STATE_DUMP, stateDump)
                }

                editor.apply()
            } catch (e: Exception) {
                // Watchdog must never crash the app
            }

            handler?.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun captureStateDump(): String {
        return try {
            val runtime = Runtime.getRuntime()
            val totalMem = runtime.totalMemory()
            val freeMem = runtime.freeMemory()
            val usedMem = totalMem - freeMem
            val threadCount = Thread.activeCount()

            val threads = Thread.getAllStackTraces()
            val interestingThreads = threads.entries
                .filter { (t, _) ->
                    val name = t.name.lowercase()
                    name.contains("decoder") || name.contains("codec") ||
                    name.contains("video") || name.contains("audio") ||
                    name.contains("aap") || name.contains("ssl") ||
                    name.contains("comm") || name.contains("main")
                }
                .take(8)
                .map { (t, stack) ->
                    val top3 = stack.take(3).joinToString(" > ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    "${t.name}[${t.state}]: $top3"
                }

            "mem=${usedMem / 1024}KB threads=$threadCount | ${interestingThreads.joinToString(" || ")}"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
}




