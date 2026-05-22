package org.xs.headunitlauncher.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.IllegalFormatException
import java.util.Locale
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object AppLog {

    interface Logger {
        fun println(priority: Int, tag: String, msg: String)

        class Android : Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                Log.println(priority, TAG, msg)
            }
        }

        class StdOut : Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                println("[$tag:$priority] $msg")
            }
        }
    }

    private var settings: Settings? = null
    private var recentLogFile: File? = null
    private var recentLogArchiveFile: File? = null
    private val fileLock = ReentrantLock()
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private const val MAX_RECENT_LOG_BYTES = 512 * 1024L

    fun init(context: Context?, settings: Settings?) {
        this.settings = settings
        if (context != null) {
            recentLogFile = File(context.filesDir, "hul_recent.log")
            recentLogArchiveFile = File(context.filesDir, "hul_recent_prev.log")
        } else {
            recentLogFile = null
            recentLogArchiveFile = null
        }
    }

    var LOGGER: Logger = Logger.Android()
    private val LOG_LEVEL get() = settings?.logLevel ?: Log.INFO

    const val TAG = "HUREV"
    // LOG_LEVEL constants should not longer be needed because we check the setting directly.
    val LOG_VERBOSE get() = LOG_LEVEL <= Log.VERBOSE
    val LOG_DEBUG get() = LOG_LEVEL <= Log.DEBUG

    fun i(msg: String) {
        log(Log.INFO, format(msg))
    }

    fun i(msg: String, vararg params: Any) {
        log(Log.INFO, format(msg, *params))
    }

    fun e(msg: String?) {
        loge(format(msg ?: "Unknown error"), null)
    }

    fun e(msg: String, tr: Throwable) {
        loge(format(msg), tr)
    }

    fun e(tr: Throwable) {
        loge(tr.message ?: "Unknown error", tr)
    }


    fun e(msg: String?, vararg params: Any) {
        loge(format(msg ?: "Unknown error", *params), null)
    }

    fun v(msg: String, vararg params: Any) {
        log(Log.VERBOSE, format(msg, *params))
    }

    fun d(msg: String, vararg params: Any) {
        log(Log.DEBUG, format(msg, *params))
    }

    fun d(msg: String) {
        log(Log.DEBUG, format(msg))
    }

    fun w(msg: String) {
        log(Log.WARN, format(msg))
    }

    fun w(msg: String, vararg params: Any) {
        log(Log.WARN, format(msg, *params))
    }

    private fun log(priority: Int, msg: String) {
        if (priority >= LOG_LEVEL) {
            LOGGER.println(priority, TAG, msg)
            appendToRecentLog(priority, msg)
        }
    }

    private fun loge(message: String, tr: Throwable?) {
        if (LOG_LEVEL > Log.ERROR) {
            return
        }
        val trace = if (LOGGER is Logger.Android) Log.getStackTraceString(tr) else ""
        val composed = message + '\n' + trace
        LOGGER.println(Log.ERROR, TAG, composed)
        appendToRecentLog(Log.ERROR, composed)
    }


    private fun format(msg: String, vararg array: Any): String {
        var formatted: String
        if (array.isEmpty()) {
            formatted = msg
        } else try {
            formatted = String.format(Locale.US, msg, *array)
        } catch (ex: IllegalFormatException) {
            e("IllegalFormatException: formatString='%s' numArgs=%d", msg, array.size)
            formatted = "$msg (An error occurred while formatting the message.)"
        }
        val stackTrace = Throwable().fillInStackTrace().stackTrace
        var string = "<unknown>"
        for (i in 2 until stackTrace.size) {
            val className = stackTrace[i].className
            if (className != AppLog::class.java.name) {
                val substring = className.substring(1 + className.indexOfLast { a -> a == 46.toChar() })
                string = substring.substring(1 + substring.indexOfLast { a -> a == 36.toChar() }) + "." + stackTrace[i].methodName
                break
            }
        }
        return String.format(Locale.US, "[%d] %s | %s", Thread.currentThread().id, string, formatted)
    }

    fun i(intent: Intent) {
        i(intent.toString())
        val ex = intent.extras
        if (ex != null) {
            i(ex.toString())
        }
    }

    fun readRecentLogs(maxChars: Int = 120_000): String {
        val files = listOfNotNull(recentLogArchiveFile, recentLogFile)
        val content = buildString {
            files.forEach { file ->
                if (file.exists()) {
                    append(file.readText())
                }
            }
        }
        return if (content.length <= maxChars) content else content.takeLast(maxChars)
    }

    private fun appendToRecentLog(priority: Int, msg: String) {
        val file = recentLogFile ?: return
        val archive = recentLogArchiveFile
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> priority.toString()
        }
        val line = "${timestampFormatter.format(Date())} $level/$TAG: $msg\n"

        fileLock.withLock {
            try {
                if (file.exists() && file.length() > MAX_RECENT_LOG_BYTES) {
                    if (archive != null) {
                        if (archive.exists()) archive.delete()
                        file.renameTo(archive)
                    } else {
                        file.delete()
                    }
                }
                file.parentFile?.mkdirs()
                file.appendText(line)
            } catch (_: Exception) {
                // Best effort only; logging should never crash the app.
            }
        }
    }
}
