package com.example.pisignage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fired by the [Watchdog] alarm (~every 10 min). Because the alarm is held by the system, this
 * runs even if the app process was killed — making it the recovery path that brings the player
 * back without waiting for a device reboot.
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TICK = "com.example.pisignage.WATCHDOG_TICK"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("WATCHDOG", "Tick → ensuring player is alive")

        // 1) Make sure the keep-alive service (and process) is up.
        SignageService.start(context)

        // 2) Bring the player UI back to the front.
        try {
            val i = Intent(context, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(i)
        } catch (e: Exception) {
            Log.w("WATCHDOG", "Could not start MainActivity (background-start may be blocked)", e)
        }

        // 3) Re-arm the next tick.
        Watchdog.schedule(context)
    }
}
