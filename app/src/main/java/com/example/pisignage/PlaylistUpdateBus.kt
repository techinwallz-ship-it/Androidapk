package com.example.pisignage

import android.content.Context
import android.content.Intent

object PlaylistUpdateBus {
    const val ACTION_PLAYLIST_UPDATED = "com.example.pisignage.ACTION_PLAYLIST_UPDATED"

    fun send(context: Context) {
        context.sendBroadcast(Intent(ACTION_PLAYLIST_UPDATED))
    }
}