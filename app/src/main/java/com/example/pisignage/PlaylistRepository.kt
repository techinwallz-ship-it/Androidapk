package com.example.pisignage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object PlaylistRepository {

    /**
     * Suspend: fetch playlist, download assets, rewrite asset.file_path to local URIs where available,
     * save final playlist to SharedPreferences and notify UI.
     */
    suspend fun fetchAndSave(context: Context, pairingCode: String) = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.inwallz.in/api/devices/$pairingCode/playlist"
            val request = Request.Builder().url(url).build()
            val body = HttpClient.instance.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("PLAYLIST", "HTTP ${response.code()}")
                    return@withContext
                }
                response.body()?.string() ?: return@withContext
            }

            // Parse JSON so we can mutate assets
            val json = JSONObject(body)

            // Force a revision so client sees change
            json.put("revision", System.currentTimeMillis())

            // Download media and get local mappings
            val mediaMap = try {
                MediaDownloader(context).syncMediaAndReturnMap(json.toString())
            } catch (e: Exception) {
                Log.e("PLAYLIST", "Media sync failed", e)
                emptyMap<String, String>()
            }

            // If we have assets, rewrite file_path to local URIs where present
            val assets: JSONArray? = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    // Determine file name used by downloader
                    var fileName = asset.optString("file_name", "")
                    val originalPath = asset.optString("file_path", "")
                    if (fileName.isBlank()) {
                        // derive from file_path
                        fileName = originalPath.substringAfterLast("/")
                    }

                    val localUri = mediaMap[fileName]
                    if (localUri != null && localUri.isNotBlank()) {
                        // set file_path to a local file URI
                        asset.put("file_path", localUri)
                        asset.put("is_local", true)
                    } else {
                        // keep remote path (no change)
                        asset.put("is_local", false)
                    }
                }
            }

            val finalJson = json.toString()

            val prefs = context.getSharedPreferences("signage", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("last_playlist", finalJson)
                .apply()

            Log.d("PLAYLIST", "✅ Saved playlist (with local paths where available) to prefs")

            // Notify UI to inject playlist (MainActivity listens to this)
            PlaylistUpdateBus.send(context)
            Log.d("PLAYLIST", "✅ Broadcasted playlist update")

        } catch (e: Exception) {
            Log.e("PLAYLIST", "fetchAndSave failed", e)
        }
    }
}