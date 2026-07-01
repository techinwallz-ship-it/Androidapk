package com.example.pisignage

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Alarm-based recovery watchdog.
 *
 * The PendingIntent is held by the system AlarmManager, so the alarm keeps firing even after the
 * app process has been killed by the Low-Memory-Killer. When it fires, [WatchdogReceiver] makes
 * sure [SignageService] (and therefore the player) is running again.
 *
 * We use setAndAllowWhileIdle: it survives Doze AND grants a temporary exemption that lets the
 * receiver legally start a foreground service / activity from the background on Android 12+
 * without the SCHEDULE_EXACT_ALARM permission.
 */
object Watchdog {

    private const val INTERVAL_MS = 5 * 60 * 1000L // 5 minutes (faster recovery if the app dies)
    private const val REQUEST_CODE = 7710

    /** Arms (or re-arms) the next watchdog tick. Self-rescheduled from the receiver each fire. */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent(context)
                )
            } else {
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent(context)
                )
            }
            Log.d("WATCHDOG", "Next tick armed in ${INTERVAL_MS / 1000}s")
        } catch (e: Exception) {
            Log.e("WATCHDOG", "Failed to schedule watchdog", e)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java).apply {
            action = WatchdogReceiver.ACTION_TICK
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
