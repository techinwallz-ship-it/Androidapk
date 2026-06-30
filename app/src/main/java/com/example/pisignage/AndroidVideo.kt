package com.example.pisignage

import android.app.Activity
import android.webkit.JavascriptInterface

/**
 * JS bridge the SPA calls to play playlist videos natively (ExoPlayer) instead of in a WebView
 * <video> element. @JavascriptInterface methods arrive on a binder thread, so every call is
 * marshalled onto the UI thread where the player lives.
 *
 * Contract (see code.txt / DisplayPage.jsx):
 *   JS → Android:  AndroidVideo.play(src) | AndroidVideo.prepare(src) | AndroidVideo.stop()
 *   Android → JS:  window.__onNativeVideoEnded() | window.__onNativeVideoError()
 */
class AndroidVideo(
    private val activity: Activity,
    private val controller: VideoController,
) {
    @JavascriptInterface
    fun play(src: String) {
        activity.runOnUiThread { controller.play(src) }
    }

    @JavascriptInterface
    fun prepare(src: String) {
        activity.runOnUiThread { controller.prepare(src) }
    }

    @JavascriptInterface
    fun stop() {
        activity.runOnUiThread { controller.stop() }
    }
}
