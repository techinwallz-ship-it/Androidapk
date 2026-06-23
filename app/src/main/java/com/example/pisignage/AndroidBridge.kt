package com.example.pisignage

import android.content.Context
import android.webkit.JavascriptInterface

class AndroidBridge(private val context: Context) {

    @JavascriptInterface
    fun savePairingCode(code: String) {
        context.getSharedPreferences("signage", Context.MODE_PRIVATE)
            .edit()
            .putString("pairing_code", code)
            .apply()
    }
}
