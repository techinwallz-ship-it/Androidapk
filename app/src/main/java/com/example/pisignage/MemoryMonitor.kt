package com.example.pisignage

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Logs a memory sample every 15 minutes (tag MEMSTAT) so a slow climb is visible in the field:
 *   adb logcat -s MEMSTAT
 *
 * IMPORTANT: runs on a dedicated BACKGROUND thread and uses only the cheap ActivityManager +
 * Runtime numbers. The earlier version ran on the main thread and called Debug.getMemoryInfo()
 * (which walks /proc/self/smaps and gets slower as the process grows) — that froze the UI thread
 * for hundreds of ms every few minutes and made video stutter worse over time. Never sample memory
 * on the UI thread.
 *
 * Watch `avail` (system free memory) and `low`. A steadily falling avail / heap climbing across
 * hours = a leak the recovery is masking; low=true before a restart = an OOM kill.
 */
object MemoryMonitor {

    private const val TAG = "MEMSTAT"
    private const val INTERVAL_MS = 15 * 60 * 1000L // 15 min
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext

        val ht = HandlerThread("mem-monitor").also { it.start() }
        thread = ht
        val h = Handler(ht.looper)
        handler = h

        val tick = object : Runnable {
            override fun run() {
                sample(appContext)
                h.postDelayed(this, INTERVAL_MS)
            }
        }
        h.postDelayed(tick, INTERVAL_MS) // first sample after one interval; no startup cost
    }

    private fun sample(context: Context) {
        try {
            val rt = Runtime.getRuntime()
            val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val heapMaxMb = rt.maxMemory() / (1024 * 1024)

            // Cheap: a quick system snapshot, no smaps walk.
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val availMb = mi.availMem / (1024 * 1024)
            val totalMb = mi.totalMem / (1024 * 1024)

            Log.d(
                TAG,
                "heap=${heapUsedMb}/${heapMaxMb}MB avail=${availMb}/${totalMb}MB low=${mi.lowMemory}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "memory sample failed", e)
        }
    }
}
