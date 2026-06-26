package com.example.pisignage

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Logs a memory sample every 5 minutes (tag MEMSTAT) so a slow climb is visible in the field:
 *   adb logcat -s MEMSTAT
 *
 * Watch `pss` (total memory the process actually occupies) and `low` (system low-memory flag).
 * A steadily rising pss across hours = a leak the recovery is masking; a sudden low=true before a
 * restart = an OOM kill. This is how you spot the NEXT problem before a customer reports it.
 */
object MemoryMonitor {

    private const val TAG = "MEMSTAT"
    private const val INTERVAL_MS = 5 * 60 * 1000L // 5 min
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        val tick = object : Runnable {
            override fun run() {
                sample(appContext)
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
        sample(appContext)                       // first sample immediately
        handler.postDelayed(tick, INTERVAL_MS)
    }

    private fun sample(context: Context) {
        try {
            val rt = Runtime.getRuntime()
            val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val heapMaxMb = rt.maxMemory() / (1024 * 1024)

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val sysAvailMb = mi.availMem / (1024 * 1024)
            val sysTotalMb = mi.totalMem / (1024 * 1024)

            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            val pssMb = info.totalPss / 1024

            Log.d(
                TAG,
                "heap=${heapUsedMb}/${heapMaxMb}MB pss=${pssMb}MB " +
                    "sysAvail=${sysAvailMb}/${sysTotalMb}MB low=${mi.lowMemory}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "memory sample failed", e)
        }
    }
}
