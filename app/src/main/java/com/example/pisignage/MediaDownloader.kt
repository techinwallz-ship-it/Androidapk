package com.example.pisignage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import okhttp3.Request
import okio.BufferedSink
import okio.Okio

class MediaDownloader(private val context: Context) {

    /**
     * Downloads media referenced by playlistJson and returns a map:
     *   filename -> local file URI (file:///...)
     *
     * Behavior:
     * - If a file already exists on disk it will be used immediately (no download).
     * - Otherwise we attempt up to maxRetries to download the file.
     * - Downloads write to a .tmp file first, then atomically rename to final file.
     * - On repeated failure, if an older file exists it will be used as a fallback.
     */
    fun syncMediaAndReturnMap(playlistJson: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        try {
            val json = JSONObject(playlistJson)
            val assets: JSONArray = json.optJSONArray("assets") ?: JSONArray()

            val mediaDir = File(context.filesDir, "media")
            if (!mediaDir.exists()) mediaDir.mkdirs()

            val neededFiles = mutableSetOf<String>()

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val filePath = asset.optString("file_path", "")
                var fileName = asset.optString("file_name", "")
                if (fileName.isBlank()) {
                    // fallback to last segment of file_path
                    fileName = filePath.substringAfterLast("/")
                    if (fileName.isBlank()) {
                        Log.w("MEDIA", "Skipping asset with no filename and no file_path")
                        continue
                    }
                }

                neededFiles.add(fileName)
                val file = File(mediaDir, fileName)

                // If file is already present and appears valid, use it (fast path)
                if (file.exists() && file.length() > 0) {
                    result[fileName] = file.toURI().toString()
                    Log.d("MEDIA", "Using existing file for $fileName -> ${file.toURI()}")
                    continue
                }

                // If there's no remote path, we can't download — skip
                if (filePath.isBlank()) {
                    Log.w("MEDIA", "No remote path for $fileName and no local file -> skipping")
                    continue
                }

                // Attempt downloads with retries
                val remoteUrl = buildRemoteUrl(filePath)
                val maxRetries = 3
                var attempt = 0
                var downloaded = false

                while (attempt < maxRetries && !downloaded) {
                    attempt++
                    try {
                        Log.d("MEDIA", "Attempt $attempt: downloading $fileName from $remoteUrl")

                        // Download into a temp file first
                        val tmpFile = File(mediaDir, "$fileName.tmp")
                        if (tmpFile.exists()) tmpFile.delete()

                        val request = Request.Builder().url(remoteUrl).build()
                        HttpClient.instance.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                throw IOException("HTTP ${resp.code()}")
                            }

                            val body = resp.body() ?: throw IOException("Empty body")
                            val sink: BufferedSink = Okio.buffer(Okio.sink(tmpFile))
                            body.source().use { src ->
                                sink.writeAll(src)
                            }
                            sink.close()
                        }

                        // Validate tmp file
                        if (tmpFile.exists() && tmpFile.length() > 0) {
                            // Move/rename atomically to final file
                            if (file.exists()) file.delete()
                            val moved = tmpFile.renameTo(file)
                            if (!moved) {
                                // fallback: copy and delete tmp
                                tmpFile.copyTo(file, overwrite = true)
                                tmpFile.delete()
                            }
                            result[fileName] = file.toURI().toString()
                            Log.d("MEDIA", "✅ Downloaded and saved $fileName -> ${file.toURI()}")
                            downloaded = true
                        } else {
                            tmpFile.delete()
                            throw IOException("Downloaded file invalid (empty) for $fileName")
                        }
                    } catch (e: Exception) {
                        Log.e("MEDIA", "Failed to download $fileName (attempt $attempt)", e)
                        // small backoff before retrying
                        try {
                            Thread.sleep((attempt * 500).toLong())
                        } catch (ignore: InterruptedException) {}
                    }
                }

                // If not downloaded but an older file exists, use it
                if (!downloaded) {
                    if (file.exists() && file.length() > 0) {
                        result[fileName] = file.toURI().toString()
                        Log.w("MEDIA", "Using previous local copy for $fileName after failed downloads -> ${file.toURI()}")
                    } else {
                        Log.e("MEDIA", "No local copy available for $fileName after $maxRetries attempts")
                    }
                }
            }

            // Clean unused files (keeps only neededFiles)
            MediaCleaner(context).cleanUnusedFiles(neededFiles)

        } catch (e: Exception) {
            Log.e("MEDIA", "syncMediaAndReturnMap failed", e)
        }

        return result
    }

    private fun buildRemoteUrl(filePath: String): String {
        return if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            filePath
        } else {
            val path = if (filePath.startsWith("/")) filePath else "/$filePath"
            "${AppConfig.API_BASE_URL}$path"
        }
    }
}