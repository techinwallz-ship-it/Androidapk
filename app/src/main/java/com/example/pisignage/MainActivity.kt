package com.example.pisignage

import android.app.ActivityManager
import android.content.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * MainActivity - stable behavior for signage:
 *  - Always load the packaged SPA (file:///android_asset/index.html) so app reliably shows
 *    the last saved playlist on cold start and works offline.
 *  - When network becomes available (or on startup if online), trigger a background
 *    sync to fetch updated playlist & media. PlaylistRepository will save the updated
 *    playlist and broadcast PlaylistUpdateBus.ACTION_PLAYLIST_UPDATED, which is injected
 *    into the running WebView by the registered receiver.
 *
 * How it works:
 *  - Use useRemoteDevServer=true only while actively developing the frontend (dev only).
 *  - Offline-first: show last_playlist from SharedPreferences/localStorage immediately.
 *  - When online and pairing_code exists, call PlaylistRepository.fetchAndSave(...) in IO coroutine.
 *  - Also register a network callback to trigger sync when network becomes available.
 */
class MainActivity : ComponentActivity() {
    private var backPressCount = 0
    private var lastPressTime = 0L
    private var adminUnlocked = false
    private lateinit var webView: WebView
    private var webContainer: FrameLayout? = null
    private var videoController: VideoController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nightlyRunnable: Runnable? = null

    // Renderer-recovery backoff state. A single renderer kill (~every 90s on weak GPUs) recovers
    // INSTANTLY (no delay). Only a rapid burst of kills — which is what wedges the WebView white —
    // triggers backoff, and after a few rapid kills we escalate to a full recreate() to clear the
    // GPU surface instead of looping. Normal playback is never affected.
    private var lastRenderGoneAt = 0L
    private var rapidCrashCount = 0
    private var recoveryRunnable: Runnable? = null
    private val rapidWindowMs = 10_000L   // kills closer than this = a cascade
    private val backoffStepMs = 1_500L    // added wait per consecutive rapid kill
    private val backoffMaxMs = 8_000L
    private val escalateAfter = 4         // rapid kills before a full recreate()
    private val escalateSettleMs = 8_000L // let the GPU settle before the heavy reset
    @Volatile
    private var wasOffline = false
    @Volatile
    private var webViewReady = false




    // Debug/dev toggle: set true only when actively developing frontend on remote dev server.
    // Keep false for production / stable offline behavior.
    private val useRemoteDevServer = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var usedDefaultRegister = false

    /** PLAYLIST RECEIVER — inject the new playlist into the running WebView (no reload) */
    private val playlistReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logx.d("PLAYLIST", "🔥 Playlist changed → inject into WebView")

            val prefs = getSharedPreferences(AppConfig.PREFS_NAME, MODE_PRIVATE)
            val playlistJson = prefs.getString(AppConfig.KEY_PLAYLIST, null) ?: return
            val pairingCode = prefs.getString(AppConfig.KEY_PAIRING, null)

            webView.post {
                try {
                    val base64 = Base64.encodeToString(playlistJson.toByteArray(), Base64.NO_WRAP)
                    val pairingBase64 = pairingCode?.let { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) } ?: ""
                    val js = """
(function() {
  try {
    var data = JSON.parse(atob("$base64"));
    localStorage.setItem('lastPlaylist', JSON.stringify(data));
    window.__PLAYLIST__ = data;
    window.dispatchEvent(new Event('playlist-updated'));

    if ("$pairingBase64".length > 0) {
      localStorage.setItem('pairingCode', atob("$pairingBase64"));
    }
  } catch (e) {
    console.error('playlist inject failed', e);
  }

  // Navigate ONLY if paired
  (function () {
    try {
      var paired = localStorage.getItem("pairingCode");
      if (paired && paired.length > 0) {
        if (window.location.hash !== "#/display") {
          window.location.hash = "#/display";
        }
      }
    } catch (e) {}
  })();
})();
""".trimIndent()

                    webView.evaluateJavascript(js, null)
                    Logx.d("PLAYLIST", "✅ Injected playlist into WebView")
                } catch (e: Exception) {
                    Log.e("PLAYLIST", "Failed to inject playlist", e)
                }
            }
        }
    }




    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)


        enableEdgeToEdge()

        // Register playlist receiver (non-exported)
        ContextCompat.registerReceiver(
            this,
            playlistReceiver,
            IntentFilter(PlaylistUpdateBus.ACTION_PLAYLIST_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Keep the process alive (foreground service) and arm the recovery watchdog so an
        // OS kill relaunches the player instead of leaving the box on the home screen.
        SignageService.start(this)
        Watchdog.schedule(this)

        // Schedule periodic sync worker (keeps running when network is available)
        schedulePlaylistSync()

        // Do an immediate one-time sync at startup (Worker) — will return quickly if no pairing_code
        WorkManager.getInstance(this)
            .enqueue(OneTimeWorkRequestBuilder<PlaylistSyncWorker>().build())

        // Register for network availability to trigger an immediate sync when online
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {

            override fun onLost(network: Network) {
                Log.d("NETWORK", "Network lost → marking offline")
                wasOffline = true
            }

            override fun onAvailable(network: Network) {
                Log.d("NETWORK", "Network available → trigger sync only")
                wasOffline = false
                triggerImmediateSyncIfOnline()
            }

        }


        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // API 24+: simplest API to register default network callback
                connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
                usedDefaultRegister = true
                Log.d("NETWORK", "Registered default network callback (API >= 24)")
            } else {
                // API 21-23: register using a NetworkRequest for internet capability
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(request, networkCallback!!)
                usedDefaultRegister = false
                Log.d("NETWORK", "Registered network callback via NetworkRequest (API < 24)")
            }
        } catch (e: Exception) {
            // Some OEMs can throw; fail gracefully and continue without callback
            Log.w("NETWORK", "Failed to register network callback", e)
            networkCallback = null
        }

        setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    // A stable container we own. The WebView lives INSIDE it so we can swap in a
                    // fresh WebView on a renderer kill without recreate()-ing the whole activity.
                    val container = FrameLayout(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    webContainer = container
                    // Native video player (ExoPlayer) lives in this same container, ON TOP of the
                    // WebView, shown only while a video plays. Created before the WebView so the
                    // AndroidVideo JS bridge can reference it.
                    videoController = VideoController(
                        this@MainActivity,
                        container,
                        onEnded = { notifyJs("window.__onNativeVideoEnded") },
                        onError = { notifyJs("window.__onNativeVideoError") }
                    )
                    webView = buildWebView(context)
                    container.addView(webView)
                    container
                }
            )
        }

        // NOTE: no explicit cold-start sync here. registerDefaultNetworkCallback() fires
        // onAvailable() immediately when already online, and the one-time WorkManager job above
        // also runs — calling triggerImmediateSyncIfOnline() here as well caused 2 concurrent
        // syncs on every cold start. (AUDIT P1 #6)
    }

    /** Creates and fully configures a fresh WebView (settings, JS bridges, client, initial load). */
    private fun buildWebView(context: Context): WebView {
        val wv = WebView(context)
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
            }
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        wv.addJavascriptInterface(AndroidMedia(context), "AndroidMedia")
        wv.addJavascriptInterface(AndroidBridge(context), "AndroidBridge")
        wv.addJavascriptInterface(AndroidApp(context), "AndroidApp")
        videoController?.let { wv.addJavascriptInterface(AndroidVideo(this, it), "AndroidVideo") }

        wv.webViewClient = object : WebViewClient() {

            /**
             * The WebView renderer runs in a separate process. When the system kills it (common on
             * memory-tight boxes) we MUST handle this and return true, or Android terminates the
             * whole app (the original "crash → home screen").
             *
             * Recovery is IN-PLACE: swap a fresh WebView into our container — NOT recreate(). The
             * old recreate() re-ran onCreate (sync storm), reloaded/reset the playlist, and leaked
             * the activity+WebView, which spiralled into a renderer-kill loop every ~90s.
             */
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    detail?.didCrash() else null
                Log.e("WEBVIEW", "Render process gone (didCrash=$crashed) → scheduling recovery")
                // Detach the dead view now so it isn't shown; the actual rebuild is scheduled with
                // backoff (instant for a normal single kill, throttled for a crash burst).
                try { (view.parent as? ViewGroup)?.removeView(view) } catch (e: Exception) {}
                scheduleRecovery()
                return true // handled — do NOT let the OS kill the app process
            }

            override fun onPageFinished(view: WebView, url: String) {
                webViewReady = true

                // Preventive memory reset once a night (in-place rebuild, see scheduleNightlyRefresh).
                scheduleNightlyRefresh()

                // Re-inject the last playlist so the SPA shows content immediately (offline-first).
                val prefs = getSharedPreferences(AppConfig.PREFS_NAME, MODE_PRIVATE)
                prefs.getString(AppConfig.KEY_PLAYLIST, null)?.let { lastPlaylist ->
                    try {
                        val base64 = Base64.encodeToString(lastPlaylist.toByteArray(), Base64.NO_WRAP)
                        val pairingCode = prefs.getString(AppConfig.KEY_PAIRING, null)
                        val pairingBase64 = pairingCode?.let { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) } ?: ""
                        val js = """
                        (function() {
                          try {
                            var data = JSON.parse(atob("$base64"));
                            localStorage.setItem('lastPlaylist', JSON.stringify(data));
                            window.__PLAYLIST__ = data;
                            window.dispatchEvent(new Event('playlist-updated'));
                            try {
                              if ("$pairingBase64".length > 0) {
                                localStorage.setItem('pairingCode', atob("$pairingBase64"));
                              }
                            } catch(e) {}
                          } catch(e) {
                            console.error('playlist inject failed', e);
                          }
                          (function () {
                            try {
                              var paired = localStorage.getItem("pairingCode");
                              if (paired && paired.length > 0) {
                                if (window.location.hash !== "#/display") {
                                  window.location.hash = "#/display";
                                }
                              }
                            } catch (e) {}
                          })();
                        })();
                        """.trimIndent()
                        view.evaluateJavascript(js, null)
                    } catch (e: Exception) {
                        Log.e("PLAYLIST", "inject failed", e)
                    }
                }

                // Start socket if pairing code present (socket handles real-time updates)
                prefs.getString(AppConfig.KEY_PAIRING, null)?.let {
                    SocketManager.connect(applicationContext, it)
                }
            }
        }

        // Always use packaged SPA for reliable offline startup.
        if (useRemoteDevServer && isOnline()) {
            val bust = System.currentTimeMillis()
            wv.loadUrl("https://tv.inwallz.in/?init=$bust")
        } else {
            wv.postDelayed({ wv.loadUrl("file:///android_asset/index.html") }, 500)
        }
        return wv
    }

    /**
     * Decides HOW to recover from a renderer kill, with backoff so a crash burst can't wedge the
     * WebView white:
     *  - normal single kill (spaced > rapidWindowMs)  → rebuild immediately (0 delay)
     *  - a few rapid kills (a cascade)                → short, increasing backoff before rebuild
     *  - too many rapid kills                         → escalate to a full recreate() after a
     *                                                   settle delay, to clear the wedged GPU surface
     */
    private fun scheduleRecovery() {
        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastRenderGoneAt
        lastRenderGoneAt = now
        rapidCrashCount = if (sinceLast < rapidWindowMs) rapidCrashCount + 1 else 0

        // Replace any pending recovery with a fresh decision based on the current burst state.
        recoveryRunnable?.let { mainHandler.removeCallbacks(it) }

        val escalate = rapidCrashCount >= escalateAfter
        val delay = when {
            rapidCrashCount == 0 -> 0L                                       // normal: instant
            escalate -> escalateSettleMs
            else -> (rapidCrashCount * backoffStepMs).coerceAtMost(backoffMaxMs)
        }

        val r = Runnable {
            recoveryRunnable = null
            if (isFinishing || isDestroyed) return@Runnable
            if (escalate) {
                Log.w("WEBVIEW", "Rapid renderer kills ($rapidCrashCount) → full recreate() to clear GPU")
                rapidCrashCount = 0
                recreate()
            } else {
                rebuildWebViewInPlace()
            }
        }
        recoveryRunnable = r
        mainHandler.postDelayed(r, delay)
        Log.d("WEBVIEW", "Recovery in ${delay}ms (rapid=$rapidCrashCount, escalate=$escalate)")
    }

    /**
     * Recovers from a renderer kill (or nightly reset) WITHOUT recreate(): swap a fresh WebView
     * into the same container. No onCreate re-run, no sync storm, no activity/WebView leak.
     */
    private fun rebuildWebViewInPlace() {
        val container = webContainer ?: return
        try {
            if (::webView.isInitialized) {
                val old = webView
                (old.parent as? ViewGroup)?.removeView(old)
                try { old.destroy() } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.w("WEBVIEW", "old WebView cleanup failed", e)
        }
        webView = buildWebView(this)
        // Add the WebView BELOW the native video overlay (index 0). Do NOT removeAllViews() — that
        // would drop the ExoPlayer TextureView the VideoController added.
        container.addView(webView, 0)
        Log.d("WEBVIEW", "WebView rebuilt in place")
    }

    /** Calls a JS function in the WebView if it exists — used for native→SPA video callbacks. */
    private fun notifyJs(fn: String) {
        runOnUiThread {
            if (::webView.isInitialized) {
                try {
                    webView.evaluateJavascript("if (typeof $fn === 'function') { $fn(); }", null)
                } catch (e: Exception) {
                    Log.w("VIDEO", "notifyJs failed", e)
                }
            }
        }
    }

    override fun onDestroy() {
        nightlyRunnable?.let { mainHandler.removeCallbacks(it) }
        recoveryRunnable?.let { mainHandler.removeCallbacks(it) }
        try { unregisterReceiver(playlistReceiver) } catch (e: Exception) {}
        try {
            networkCallback?.let {
                try {
                    // unregisterNetworkCallback works for both registration methods; keep safe call
                    connectivityManager?.unregisterNetworkCallback(it)
                } catch (e: Exception) {
                    Log.w("NETWORK", "unregisterNetworkCallback failed", e)
                }
            }
        } catch (e: Exception) {}
        SocketManager.disconnect()
        try { videoController?.release() } catch (e: Exception) {}
        videoController = null
        if (::webView.isInitialized) {
            try { webView.destroy() } catch (e: Exception) {}
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Under memory pressure, drop the WebView's caches (safe — just frees caches, not state).
        // Helps the RAM-tight 2 GB boxes that hit LOW_MEMORY running WebView + ExoPlayer together.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            try { if (::webView.isInitialized) webView.freeMemory() } catch (e: Exception) {}
        }
    }

    /**
     * Schedules a preventive in-place WebView rebuild for the next 03:00 local time, repeating
     * daily. Uses a removable handler (cleared in onDestroy) — NOT webView.postDelayed, whose
     * pending message used to hold the whole activity for ~19h and leaked one per recreate().
     * Guarded so repeated page loads don't stack timers.
     */
    private fun scheduleNightlyRefresh() {
        if (nightlyRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                if (!isFinishing && !isDestroyed) {
                    Log.d("MEMRESET", "Nightly WebView refresh → in-place rebuild")
                    rebuildWebViewInPlace()
                }
                mainHandler.postDelayed(this, 24L * 60 * 60 * 1000) // next day
            }
        }
        nightlyRunnable = r
        val delay = millisUntilNext3am()
        mainHandler.postDelayed(r, delay)
        Log.d("MEMRESET", "Nightly refresh scheduled in ${delay / 60000} min")
    }

    private fun millisUntilNext3am(): Long {
        val now = java.util.Calendar.getInstance()
        val next = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 3)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (!after(now)) add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun schedulePlaylistSync() {
        val work = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(
            15,
            TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "playlist_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                work
            )
    }

    /**
     * If device is online and we have a pairing code, trigger an immediate PlaylistRepository.sync.
     * Runs on the lifecycleScope IO dispatcher to avoid blocking main thread.
     */
    private fun triggerImmediateSyncIfOnline() {
        val prefs = getSharedPreferences(AppConfig.PREFS_NAME, MODE_PRIVATE)
        val pairingCode = prefs.getString(AppConfig.KEY_PAIRING, null) ?: return
        if (!isOnline()) return

        // Launch a background coroutine to fetch & save playlist
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Logx.d("PLAYLIST", "Immediate sync triggered (pairing:$pairingCode)")
                PlaylistRepository.fetchAndSave(applicationContext, pairingCode)
            } catch (e: Exception) {
                Log.e("PLAYLIST", "Immediate sync failed", e)
            }
        }
    }
    override fun onStart() {
        super.onStart()

        try {
            startLockTask()
            Log.d("KIOSK", "Kiosk mode started")
        } catch (e: Exception) {
            Log.e("KIOSK", "Failed to start kiosk mode", e)
        }

        // Verify the lock actually engaged. Without Device Owner this is best-effort
        // screen-pinning and may NOT be active — log the real state so we can tell from
        // the field whether a box is truly locked. (AUDIT P2-1)
        val am = getSystemService(ActivityManager::class.java)
        val state = am?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE
        Log.d("KIOSK", "Lock task active=${state != ActivityManager.LOCK_TASK_MODE_NONE} (state=$state)")
    }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {

        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {

            val time = System.currentTimeMillis()

            if (time - lastPressTime < 2000) {
                backPressCount++
            } else {
                backPressCount = 1
            }

            lastPressTime = time

            if (backPressCount >= 5) {
                exitKioskMode()
                return false
            }

            return true
        }

        return super.onKeyDown(keyCode, event)
    }
    private fun exitKioskMode() {
        try {
            adminUnlocked = true
            backPressCount = 0
            stopLockTask()
            Log.d("KIOSK", "Admin mode enabled")
        } catch (e: Exception) {
            Log.e("KIOSK", "Failed to stop kiosk", e)
        }
    }
    override fun onPause() {
        super.onPause()

        // Keep the kiosk in the foreground WITHOUT launching a new activity. The old code called
        // startActivity(MainActivity) here, which — combined with the HOME-launcher filter and
        // singleTask — spawned a 2nd MainActivity/WebView in another task and made the two
        // instances ping-pong (the duplicate-WebView leak behind the video lag). moveTaskToFront
        // re-fronts our existing task and, with singleInstance, can never create a duplicate.
        if (!adminUnlocked) {
            try {
                val am = getSystemService(ActivityManager::class.java)
                am?.moveTaskToFront(taskId, 0)
            } catch (e: Exception) {
                Log.w("KIOSK", "moveTaskToFront failed", e)
            }
        }
    }
}