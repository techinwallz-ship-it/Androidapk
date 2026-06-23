package com.example.pisignage

import android.content.Context
import android.util.Log
import java.io.File

class MediaCleaner(private val context: Context) {

    fun cleanUnusedFiles(needed: Set<String>) {
        val mediaDir = File(context.filesDir, "media")
        if (!mediaDir.exists()) return

        mediaDir.listFiles()?.forEach { file ->
            if (!needed.contains(file.name)) {
                Log.d("MEDIA", "🗑️ Deleting unused ${file.name}")
                file.delete()
            }
        }
    }
}
