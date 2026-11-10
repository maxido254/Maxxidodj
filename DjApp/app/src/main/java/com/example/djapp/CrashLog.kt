package com.example.djapp

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Remembers why the app last closed and shows it on the next launch, so a crash can be reported
 * from a screenshot (no computer / logcat needed). Java crashes save their stack trace; on Android 11+
 * the system's own exit reason also catches low-memory kills and native crashes.
 */
object CrashLog {
    var last by mutableStateOf<String?>(null)
        private set

    fun dismiss() { last = null }

    fun install(ctx: Context) {
        val f = File(ctx.filesDir, "last_crash.txt")
        val parts = ArrayList<String>()
        if (f.exists()) {
            try { parts.add(f.readText().take(1500)) } catch (_: Exception) { }
            f.delete()
        }
        if (Build.VERSION.SDK_INT >= 30) try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
            val seenFile = File(ctx.filesDir, "last_exit_seen.txt")
            val seen = if (seenFile.exists()) seenFile.readText().trim().toLongOrNull() ?: 0L else 0L
            if (info != null && info.timestamp > seen) {
                seenFile.writeText(info.timestamp.toString())
                val bad = info.reason in intArrayOf(
                    ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
                    ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                )
                if (bad) parts.add("System exit reason ${info.reason} (3 = out of memory, 4 = app error, 5 = native crash, 6 = not responding, 2 = killed): ${info.description}")
            }
        } catch (_: Exception) { }
        if (parts.isNotEmpty()) last = parts.joinToString("\n\n")

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { f.writeText("thread=${t.name}\n" + e.stackTraceToString()) } catch (_: Exception) { }
            prev?.uncaughtException(t, e)
        }
    }
}
