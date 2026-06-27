package com.example.pisignage

import android.util.Log

/**
 * Verbose, high-frequency logging that should be silent in production. Compiles to a near no-op in
 * release (BuildConfig.DEBUG=false) so deployed boxes don't spam logcat with per-sync chatter.
 *
 * Use Logx.d() for routine "happened normally" traces. Keep Log.e()/Log.w() (real problems) and the
 * low-frequency field diagnostics (MEMSTAT, CRASH, WEBVIEW render-gone, KIOSK) on the normal Log API
 * so they remain available on deployed boxes.
 */
object Logx {
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }
}
