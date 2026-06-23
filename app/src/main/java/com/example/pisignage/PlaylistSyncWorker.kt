package com.example.pisignage

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class PlaylistSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val prefs =
                applicationContext.getSharedPreferences("signage", Context.MODE_PRIVATE)

            val pairingCode = prefs.getString("pairing_code", null)
                ?: run {
                    Log.w("PLAYLIST", "No pairing code — skipping sync")
                    return Result.success()
                }

            // Use central repository which fetches, saves, downloads media and notifies UI
            PlaylistRepository.fetchAndSave(applicationContext, pairingCode)

            Log.d("PLAYLIST", "✅ PlaylistSyncWorker completed")
            return Result.success()

        } catch (e: Exception) {
            Log.e("PLAYLIST", "❌ Error in PlaylistSyncWorker", e)
            return Result.retry()
        }
    }
}