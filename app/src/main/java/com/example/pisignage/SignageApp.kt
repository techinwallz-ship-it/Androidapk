package com.example.pisignage

import android.app.Application

/**
 * Application entry point — installed before any Activity/Service so telemetry covers the whole
 * process (including crashes in workers and the foreground service).
 */
class SignageApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        MemoryMonitor.start(this)
    }
}
