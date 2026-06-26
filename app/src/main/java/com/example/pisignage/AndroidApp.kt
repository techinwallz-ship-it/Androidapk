package com.example.pisignage

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface

class AndroidApp(private val context: Context) {

    @JavascriptInterface
    fun onAppReady() {
        // optional hook: the JS UI can notify the Android host it is ready.
        // You can use this to immediately trigger things from Android if needed.
        Log.d("AndroidApp", "Web UI reported ready")
    }
}