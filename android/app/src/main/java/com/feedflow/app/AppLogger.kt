package com.feedflow.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lightweight file logger. Writes to a configurable directory asynchronously
 * so it never blocks the UI thread. Also forwards to Logcat in debug builds.
 */
object AppLogger {
    private const val TAG = "FeedFlow"
    private var logDir: File? = null
    private var enabled = true
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("feedflow_prefs", Context.MODE_PRIVATE)
        val customDir = prefs.getString("log_dir", null)
        logDir = if (customDir != null) {
            File(customDir).also { it.mkdirs() }
        } else {
            // Write to external files dir: /storage/emulated/0/Android/data/<pkg>/files/logs/
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                File(extDir, "logs").also { it.mkdirs() }
            } else {
                File(context.filesDir, "logs").also { it.mkdirs() }
            }
        }
        enabled = prefs.getBoolean("log_enabled", true)
        // Clean logs older than 7 days
        executor.execute { cleanOldLogs(7) }
    }

    fun setLogDir(path: String) {
        logDir = File(path).also { it.mkdirs() }
    }

    fun getLogDir(): String = logDir?.absolutePath ?: ""

    fun setEnabled(on: Boolean) { enabled = on }
    fun isEnabled(): Boolean = enabled

    fun d(msg: String) = log("D", msg)
    fun i(msg: String) = log("I", msg)
    fun w(msg: String) = log("W", msg)
    fun e(msg: String, t: Throwable? = null) {
        log("E", if (t != null) "$msg: ${t.message}" else msg)
        t?.let { Log.e(TAG, msg, it) }
    }

    private fun log(level: String, msg: String) {
        if (!enabled) return
        // Logcat
        when (level) {
            "D" -> Log.d(TAG, msg)
            "I" -> Log.i(TAG, msg)
            "W" -> Log.w(TAG, msg)
        }
        // Async file write
        val dir = logDir ?: return
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp [$level] $msg\n"
        executor.execute {
            try {
                val file = File(dir, "feedflow-${fileDateFormat.format(Date())}.log")
                FileWriter(file, true).use { it.write(line) }
            } catch (_: Exception) { /* never crash for logging */ }
        }
    }

    private fun cleanOldLogs(keepDays: Int) {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - keepDays * 86400000L
        dir.listFiles()?.filter { it.name.startsWith("feedflow-") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }
}
