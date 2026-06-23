package com.example.pisignage

import android.content.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
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
            Log.d("PLAYLIST", "🔥 Playlist changed → inject into WebView")

            val prefs = getSharedPreferences("signage", MODE_PRIVATE)
            val playlistJson = prefs.getString("last_playlist", null) ?: return
            val pairingCode = prefs.getString("pairing_code", null)

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
                    Log.d("PLAYLIST", "✅ Injected playlist into WebView")
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
                    webView = WebView(context)

                    webView.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    webView.settings.apply {
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

                    WebView.setWebContentsDebuggingEnabled(true)

                    // Register JS bridges expected by the web UI
                    webView.addJavascriptInterface(AndroidMedia(context), "AndroidMedia")
                    webView.addJavascriptInterface(AndroidBridge(context), "AndroidBridge")
                    webView.addJavascriptInterface(AndroidApp(context), "AndroidApp")

                    webView.webViewClient = object : WebViewClient() {


                        override fun onPageFinished(view: WebView, url: String) {

                            webViewReady = true

                            // ✅ FIX: Online reboot media recovery (ONE TIME)

                            // 🔁 CRITICAL: fix white screen after reboot
                            val prefs = getSharedPreferences("signage", MODE_PRIVATE)
                            prefs.getString("last_playlist", null)?.let { lastPlaylist ->
                                try {
                                    val base64 = Base64.encodeToString(lastPlaylist.toByteArray(), Base64.NO_WRAP)
                                    val pairingCode = prefs.getString("pairing_code", null)
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
                            prefs.getString("pairing_code", null)?.let {
                                SocketManager.connect(applicationContext, it)
                            }
                        }
                    }

                    // Always use packaged SPA for reliable offline startup.
                    // Toggle useRemoteDevServer ONLY during frontend development.
                    if (useRemoteDevServer && isOnline()) {
                        val bust = System.currentTimeMillis()
                        webView.loadUrl("https://tv.inwallz.in/?init=$bust")
                    } else {
                        webView.postDelayed({
                            webView.loadUrl("file:///android_asset/index.html")
                        }, 500)

                    }

                    webView
                }
            )
        }

        // Trigger immediate sync at cold start if network available and pairing_code exists
        triggerImmediateSyncIfOnline()
    }

    override fun onDestroy() {
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
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
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
        val prefs = getSharedPreferences("signage", MODE_PRIVATE)
        val pairingCode = prefs.getString("pairing_code", null) ?: return
        if (!isOnline()) return

        // Launch a background coroutine to fetch & save playlist
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("PLAYLIST", "Immediate sync triggered (pairing:$pairingCode)")
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

        if (!adminUnlocked) {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
    }
}