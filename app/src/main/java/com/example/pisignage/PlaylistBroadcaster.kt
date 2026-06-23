package com.example.pisignage

import android.content.Context
import android.content.Intent

object PlaylistBroadcaster {

    fun notifyUpdated(context: Context) {
        context.sendBroadcast(
            Intent(PlaylistUpdateBus.ACTION_PLAYLIST_UPDATED)
        )
    }
}
