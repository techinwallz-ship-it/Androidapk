package com.example.pisignage

import android.content.Context

object PlaylistStorage {

    fun save(context: Context, json: String) {
        context.getSharedPreferences("signage", Context.MODE_PRIVATE)
            .edit()
            .putString("last_playlist", json)
            .apply()
    }

    fun load(context: Context): String? {
        return context
            .getSharedPreferences("signage", Context.MODE_PRIVATE)
            .getString("last_playlist", null)
    }
}
