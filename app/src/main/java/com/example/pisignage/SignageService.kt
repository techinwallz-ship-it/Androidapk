package com.example.pisignage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service whose only job is to keep the signage process alive.
 *
 * Why this exists:
 *  - Budget Android TV boxes (1–2 GB RAM) aggressively kill background/foreground apps via the
 *    Low-Memory-Killer. An Activity alone is an easy target — when killed, nothing restarts it and
 *    the box drops to the home screen until the next reboot.
 *  - A foreground service is one of the LAST things the OS kills, and START_STICKY tells the system
 *    to recreate the service (and therefore the process) after a kill.
 *  - On recreation we bring [MainActivity] back to the front so the player resumes unattended.
 *
 * Started from [MainActivity.onCreate] and re-armed periodically by [WatchdogReceiver].
 */
class SignageService : Service() {

    companion object {
        private const val CHANNEL_ID = "signage_service"
        private const val NOTIF_ID = 1001

        /** Safe to call from any context; handles the O+ startForegroundService requirement. */
        fun start(context: Context) {
            val intent = Intent(context, SignageService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ can block starting an FGS from the background unless exempt
                // (the watchdog uses an allow-while-idle alarm, which grants the exemption).
                Log.e("SIGNAGE_SVC", "start() failed", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        Log.d("SIGNAGE_SVC", "Foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the OS restarted us after a kill — make sure the UI is back.
        if (intent == null) {
            Log.d("SIGNAGE_SVC", "Restarted by OS after kill → bringing player to front")
            ensurePlayerRunning()
        }
        // Keep the watchdog armed so we recover even if the whole process dies.
        Watchdog.schedule(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Task swiped from recents / removed — try to relaunch the player.
        ensurePlayerRunning()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensurePlayerRunning() {
        try {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(i)
        } catch (e: Exception) {
            // Background-activity-start may be blocked on non-kiosk boxes; Device Owner /
            // lock task removes this restriction (see P2 in AUDIT.md).
            Log.w("SIGNAGE_SVC", "Could not bring MainActivity to front", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "PiSignage Player",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Keeps the signage player running"
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PiSignage")
            .setContentText("Player running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
