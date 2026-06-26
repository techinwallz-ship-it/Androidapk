package com.example.pisignage

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global uncaught-exception handler. Logs the full stack trace to Logcat (tag CRASH) and to a
 * rolling file in filesDir so a crash that happened overnight survives the restart and can be
 * pulled later via:  adb shell run-as com.example.pisignage cat files/crash.log
 *
 * It chains to the previous handler so the process still dies — the foreground service
 * (START_STICKY) + watchdog then restart the player.
 */
object CrashReporter {

    private const val TAG = "CRASH"
    private const val FILE = "crash.log"
    private const val MAX_BYTES = 256 * 1024L

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val entry = "===== $ts | thread=${thread.name} | " +
                    "${Build.MODEL} (API ${Build.VERSION.SDK_INT}) =====\n$sw\n"
                Log.e(TAG, entry)
                appendCapped(appContext, entry)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record crash", e)
            }
            // Let the system handle it → process dies → service/watchdog restart it.
            previous?.uncaughtException(thread, throwable)
        }
        Log.d(TAG, "Crash reporter installed")
    }

    private fun appendCapped(context: Context, text: String) {
        try {
            val f = File(context.filesDir, FILE)
            if (f.exists() && f.length() > MAX_BYTES) f.delete() // roll when too big
            f.appendText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash.log", e)
        }
    }
}
