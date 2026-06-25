package com.example.pisignage

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets


class AndroidMedia(private val context: Context) {

    companion object {
        private const val MAX_DATA_URI_BYTES = 4 * 1024 * 1024L // 4 MB
    }

    private fun L(tag: String, msg: String) { Log.d("AndroidMedia", "$tag: $msg") }

    private fun decodeName(name: String): String {
        return try {
            URLDecoder.decode(name, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            name
        }
    }

    private fun extractFileName(input: String): String {
        try {
            if (input.startsWith("data:")) return input // data: remains as-is
            if (input.startsWith("http://") || input.startsWith("https://")) {
                val uri = URI(input)
                val raw = File(uri.path).name
                return decodeName(raw)
            }
            if (input.startsWith("file://")) {
                val uri = URI(input)
                val raw = File(uri.path).name
                return decodeName(raw)
            }
            if (input.startsWith("content:")) {
                // best effort: take last segment and decode
                return decodeName(input.substringAfterLast('/'))
            }
            return decodeName(input.substringAfterLast('/'))
        } catch (e: Exception) {
            return input.substringAfterLast('/')
        }
    }

    /**
     * Ensure we return canonical URIs usable by WebView/media elements:
     *  - data: and content: are returned unchanged
     *  - local files -> "file://<absolutePath>" (e.g. file:///data/...)
     *  - packaged assets -> "file:///android_asset/<name>"
     *  - otherwise return empty string
     */
    private fun normalizeFileUri(file: File): String {
        // Use absolutePath so result is "file:///<abs path>"
        val abs = file.absolutePath // starts with "/"
        return "file://$abs"
    }

    @JavascriptInterface
    fun getLocalMediaPath(filePath: String): String {
        try {
            if (filePath.isBlank()) return ""
            // If caller already passed a data: URI, return as-is
            if (filePath.startsWith("data:")) {
                L("getLocalMediaPath", "data URI provided -> (len ${filePath.length})")
                return filePath
            }

            // If content: provided, return unchanged (WebView handles content: URIs)
            if (filePath.startsWith("content:")) {
                L("getLocalMediaPath", "content uri provided -> $filePath")
                return filePath
            }

            // If file:// provided, try to resolve and normalize to file://<absPath>
            if (filePath.startsWith("file://")) {
                try {
                    val f = File(URI(filePath))
                    if (f.exists()) {
                        val uri = normalizeFileUri(f)
                        L("getLocalMediaPath", "found fileUri -> $uri")
                        return uri
                    } else {
                        // If URI parsing fails, fallback to raw path handling below
                        L("getLocalMediaPath", "file:// provided but file does not exist -> $filePath")
                    }
                } catch (e: Exception) {
                    L("getLocalMediaPath", "file:// parsing failed -> $filePath")
                    // fall through to filename lookup
                }
            }

            // If an absolute path (starts with "/"), normalize directly
            if (filePath.startsWith("/")) {
                val f = File(filePath)
                if (f.exists()) {
                    val uri = normalizeFileUri(f)
                    L("getLocalMediaPath", "found absolute path -> $uri")
                    return uri
                }
            }

            // File name or remote URL: try to find local copy in files/media
            val name = extractFileName(filePath)
            val mediaDir = File(context.filesDir, "media")
            val f = File(mediaDir, name)
            if (f.exists()) {
                val uri = normalizeFileUri(f)
                L("getLocalMediaPath", "found local media -> $uri")
                return uri
            }

            // Fallback to packaged assets
            try {
                context.assets.open(name).close()
                val assetUri = "file:///android_asset/$name"
                L("getLocalMediaPath", "found packaged asset -> $assetUri")
                return assetUri
            } catch (e: Exception) {
                L("getLocalMediaPath", "not found locally -> $filePath")
                return ""
            }
        } catch (e: Exception) {
            Log.e("AndroidMedia", "getLocalMediaPath failed", e)
            return ""
        }
    }

    /**
     * Returns a data:[mime];base64,... string if the file exists and is a supported image.
     */
    @JavascriptInterface
    fun getLocalMediaData(filePath: String): String {
        try {
            if (filePath.isBlank()) return ""
            if (filePath.startsWith("data:")) return filePath

            val name = extractFileName(filePath)
            val mediaDir = File(context.filesDir, "media")
            val file = File(mediaDir, name)

            if (file.exists() && file.length() > MAX_DATA_URI_BYTES) {
                L("getLocalMediaData", "file too large (${file.length()} bytes), use getLocalMediaPath instead")
                return ""
            }

            val bytes = when {
                file.exists() -> FileInputStream(file).use { it.readBytes() }
                else -> {
                    try {
                        context.assets.open(name).use { it.readBytes() }
                    } catch (e: Exception) {
                        null
                    }
                }
            } ?: run {
                L("getLocalMediaData", "not found -> $filePath (extracted:$name)")
                return ""
            }

            val lower = name.toLowerCase()
            val mime = when {
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".gif") -> "image/gif"
                else -> null
            } ?: return ""

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUri = "data:$mime;base64,$base64"
            L("getLocalMediaData", "returning data uri for $name (len=${base64.length})")
            return dataUri
        } catch (e: Exception) {
            Log.e("AndroidMedia", "getLocalMediaData failed", e)
            return ""
        }
    }

}