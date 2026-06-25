# PiSignage Android — Full Audit & Fix Tracker

**Repo:** `techinwallz-ship-it/Androidapk`
**Working branch:** `next` (P0 crash-recovery work) · prior: `mem_leak`
**Last audited:** June 2026 · **Last updated:** 2026-06-25

---

## What This App Does

Android digital signage player for assembled Android TV boxes.

- Loads a React SPA from bundled assets into a **kiosk-mode WebView** (lock task, always-on screen)
- Pairs with `api.inwallz.in` server using a pairing code entered in the web UI
- Fetches playlists (media collections), downloads media files locally, loops them in WebView
- Syncs playlist every 15 min via WorkManager + on network reconnect + on real-time Socket.IO events
- Auto-starts on device boot via `BootReceiver` → `SplashActivity` → `MainActivity`

---

## Code Structure (17 Kotlin files)

| File | Role | Status |
|---|---|---|
| `SplashActivity.kt` | Lottie animation → MainActivity | OK |
| `BootReceiver.kt` | Auto-start on boot → SplashActivity | OK |
| `MainActivity.kt` | WebView, kiosk, network callback, playlist injection | Has issues |
| `AndroidBridge.kt` | JS interface: `savePairingCode()` | OK |
| `AndroidMedia.kt` | JS interface: media path/data lookup | Fixed |
| `AndriodApp.kt` | JS interface: `onAppReady()` hook | Dead hook — filename typo |
| `SocketManager.kt` | Socket.IO connection | Fixed |
| `PlaylistRepository.kt` | HTTP fetch + download + save + notify | Has concurrency issue |
| `MediaDownloader.kt` | Downloads individual media files with retry | Fixed |
| `MediaCleaner.kt` | Deletes unused media from disk | Race condition |
| `PlaylistSyncWorker.kt` | WorkManager 15-min periodic worker | OK |
| `PlaylistStorage.kt` | SharedPrefs wrapper | **Dead code — never called** |
| `PlaylistUpdateBus.kt` | LocalBroadcast sender | OK |
| `PlaylistBroadcaster.kt` | Sends same broadcast | **Dead code — duplicates PlaylistUpdateBus** |
| `HttpClient.kt` | Shared OkHttpClient singleton | New (added in mem_leak) |
| `SignageService.kt` | Keep-alive foreground service (START_STICKY) | **New (added in next)** |
| `Watchdog.kt` | AlarmManager recovery scheduler | **New (added in next)** |
| `WatchdogReceiver.kt` | Relaunches player when alarm fires | **New (added in next)** |

---

## Application Flow

### Boot Sequence
```
Device Boot
  └─ BootReceiver.onReceive()
       └─ startActivity(SplashActivity)
            └─ Lottie animation
                 └─ startActivity(MainActivity)
                      ├─ registerReceiver(playlistReceiver)
                      ├─ WorkManager: schedule 15-min periodic sync
                      ├─ WorkManager: enqueue one-time sync NOW
                      ├─ registerDefaultNetworkCallback()
                      ├─ [500ms delay] webView.loadUrl("file:///android_asset/index.html")
                      └─ triggerImmediateSyncIfOnline()   ← called TWICE on startup (bug)
```

### WebView Ready (onPageFinished)
```
onPageFinished
  ├─ Read SharedPrefs → "last_playlist" → evaluateJavascript(inject playlist)
  └─ Read SharedPrefs → "pairing_code" → SocketManager.connect()
                                               └─ emit("join-tv", pairingCode)
```

### Three Sync Triggers (run concurrently — no mutex — root crash cause)
```
WorkManager (15 min)         ─┐
NetworkCallback.onAvailable() ─┼─► PlaylistRepository.fetchAndSave()
Socket "start-playlist"      ─┘        │
                                        ├─ GET api.inwallz.in/api/devices/{code}/playlist
                                        ├─ MediaDownloader.syncMediaAndReturnMap()
                                        │    ├─ Per asset: file exists? → reuse
                                        │    ├─ Else: download → .tmp → rename
                                        │    └─ MediaCleaner.cleanUnusedFiles()  ← race condition
                                        ├─ SharedPrefs.putString("last_playlist", json)
                                        └─ PlaylistUpdateBus.send()
                                               └─ playlistReceiver.onReceive()
                                                    └─ webView.evaluateJavascript(inject)
                                                         └─ JS: playlist-updated + navigate #/display
```

### JS ↔ Android Bridge
```
AndroidBridge.savePairingCode(code)       → SharedPrefs "pairing_code"
AndroidMedia.getLocalMediaPath(filePath)  → returns file:// URI
AndroidMedia.getLocalMediaData(filePath)  → returns base64 string (≤4MB cap, fixed)
AndroidApp.onAppReady()                   → Log.d only, no action
```

### Data Stores
| Store | Keys | Written by |
|---|---|---|
| SharedPreferences ("signage") | `pairing_code`, `last_playlist` | AndroidBridge, PlaylistRepository |
| Local files (`filesDir/media/`) | Downloaded media files | MediaDownloader |
| WebView localStorage (JS) | `lastPlaylist`, `pairingCode` | evaluateJavascript injection |

---

## Already Fixed in `mem_leak` Branch

| # | Fix | Files Changed |
|---|---|---|
| 1 | `OkHttpClient` singleton — was created per `MediaDownloader` instance (thread pool leak) | `HttpClient.kt` (new), `MediaDownloader.kt`, `PlaylistRepository.kt` |
| 2 | HTTP response body now closed on error — was leaked on non-2xx, exhausted connection pool | `PlaylistRepository.kt` |
| 3 | `getLocalMediaData()` size guard — files >4MB return `""` instead of loading full file as base64 (OOM) | `AndroidMedia.kt` |
| 4 | `SocketManager` managed scope — was creating new `CoroutineScope` per socket event, never cancelled | `SocketManager.kt` |
| 5 | `webView.destroy()` in `onDestroy()` — WebView native resources now released properly | `MainActivity.kt` |

---

## Done in `next` Branch — P0 Crash-to-Home Recovery

> **Root-cause refinement (2026-06-25):** the "crash → home screen after 3–16 hrs" symptom is a
> **complete process death** (reopening shows the splash screen = cold start). The trigger is
> RAM-dependent (less free RAM = dies sooner = classic OOM signature). The primary mechanism is the
> **WebView renderer process** being OOM-killed: when `onRenderProcessGone()` is not handled,
> Android terminates the **whole app**. This was NOT identified in the original audit. The fixes
> below stop the symptom by **recovering** from the kill; they do not yet reduce the memory climb
> that causes it (see P1 below).

| # | Fix | Files Changed | Status |
|---|---|---|---|
| P0-1 | `onRenderProcessGone()` — catch renderer death, destroy dead WebView, `recreate()` instead of letting the OS kill the app (return `true`). **Primary fix for the home-screen symptom.** | `MainActivity.kt` | ✅ Built |
| P0-2 | Foreground service (`START_STICKY`, `specialUse` FGS type) — keeps the process alive and recreates it after a kill; brings `MainActivity` to front on restart | `SignageService.kt` (new), `AndroidManifest.xml` | ✅ Built |
| P0-3 | Recovery watchdog — `setAndAllowWhileIdle` alarm (~10 min) held by the system survives full process death and relaunches the service + UI | `Watchdog.kt` (new), `WatchdogReceiver.kt` (new), `AndroidManifest.xml` | ✅ Built |

**Manifest additions:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` permissions; `<service .SignageService>` (specialUse + justification property); `<receiver .WatchdogReceiver>`.

**Verification:** `:app:assembleDebug` builds clean. **Not yet field-tested on hardware.**

**Known limits:** recovery is reliable, but guaranteed *background* relaunch of the UI on Android 12+
needs Device Owner / lock task (see P2 #9–10). The memory leak itself is unaddressed until P1.

---

## Open Issues — Needs Fixing (Priority Order)

### CRITICAL

#### 1. Concurrent Sync Race Condition — ROOT CAUSE of 1–16hr crash
**Problem:**
`PlaylistRepository.fetchAndSave()` has no mutex. All 3 sync paths (WorkManager, network callback, socket) call it simultaneously. When two syncs run at the same time:
- Both write `filename.tmp` → overwrite each other
- Sync A finishes → `MediaCleaner` deletes files Sync B is still writing
- WebView tries to load a `file://` URI that was just deleted → media fails / JS crashes

**Fix:**
```kotlin
// PlaylistRepository.kt
private val mutex = Mutex()

suspend fun fetchAndSave(context: Context, pairingCode: String) = withContext(Dispatchers.IO) {
    mutex.withLock {
        // ... existing fetch logic
    }
}
```
Add `import kotlinx.coroutines.sync.Mutex` and `import kotlinx.coroutines.sync.withLock`.

---

#### 2. No Foreground Service — App Killed by OS with No Restart  ✅ ADDRESSED in `next` (P0-2/P0-3)
**Problem:**
App runs entirely as an Activity. When Android's Low Memory Killer kills it (very common on assembled budget boxes with 1–2GB RAM), the process dies and nothing restarts it until the next device reboot. `BootReceiver` only fires on boot, not on app kill.

**Fix:**
Create `SignageService.kt` as a `ForegroundService` with a persistent notification. Bind it to `MainActivity`. The service keeps the process alive and auto-restarts via `START_STICKY`.

```kotlin
class SignageService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PiSignage Running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(1, notification)
        return START_STICKY  // auto-restart if killed
    }
    override fun onBind(intent: Intent?) = null
}
```

Start it from `MainActivity.onCreate()`:
```kotlin
ContextCompat.startForegroundService(this, Intent(this, SignageService::class.java))
```

Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<service android:name=".SignageService" android:foregroundServiceType="mediaPlayback" />
```

---

#### 3. `startLockTask()` Silently Fails — Kiosk Mode Never Active
**Problem:**
`startLockTask()` requires the app to be whitelisted as **Device Owner** via `DevicePolicyManager`. On most assembled Android TV boxes this is not configured. The call throws silently (caught by try-catch) and kiosk mode is never active — any system event, OEM overlay, or accidental home press exits to home screen.

**Fix:**
```kotlin
// MainActivity.kt — in onStart()
override fun onStart() {
    super.onStart()
    try {
        startLockTask()
        Log.d("KIOSK", "Kiosk mode started")
    } catch (e: Exception) {
        Log.e("KIOSK", "Failed to start kiosk mode — Device Owner not set", e)
    }

    // Verify kiosk is actually active
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val am = getSystemService(ActivityManager::class.java)
        val inKiosk = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        Log.d("KIOSK", "Lock task active: $inKiosk")
        if (!inKiosk) {
            // Show overlay or take fallback action
        }
    }
}
```

**Device Owner setup (run once via ADB on each box):**
```bash
adb shell dpm set-device-owner com.example.pisignage/.AdminReceiver
```
This requires adding a `DeviceAdminReceiver` to the project and declaring it in the manifest.

---

### MAJOR

#### 4. `onPause()` Activity Restart — Unreliable on TV Boxes
**Problem:**
`MainActivity.onPause()` calls `startActivity(MainActivity)` every time the activity pauses. No `launchMode` is set so multiple instances can stack. On many TV boxes this causes an activity loop.

**Fix:**
Add to `AndroidManifest.xml`:
```xml
<activity
    android:name=".MainActivity"
    android:launchMode="singleTask"
    android:exported="true"
    android:screenOrientation="sensorLandscape" />
```

Change `onPause()` to use `onStop()` and `moveTaskToFront()` instead of `startActivity()`:
```kotlin
override fun onStop() {
    super.onStop()
    if (!adminUnlocked) {
        val am = getSystemService(ActivityManager::class.java)
        am.moveTaskToFront(taskId, 0)
    }
}
```

---

#### 5. Duplicate Socket on Reconnect
**Problem:**
`SocketManager.connect()` is called from `onPageFinished()`. Guard `if (socket?.connected() == true) return` passes when socket is in `CONNECTING` state (not yet `CONNECTED`) — creates duplicate socket connections.

**Fix:**
```kotlin
// SocketManager.kt
fun connect(context: Context, pairingCode: String) {
    if (socket != null) return  // already connecting or connected
    // ... rest of connect logic
}
```

---

#### 6. `triggerImmediateSyncIfOnline()` Called Twice on Startup
**Problem:**
Called explicitly at `MainActivity.onCreate()` line 300 AND fired again immediately by `networkCallback.onAvailable()` if device is already online. Results in 2 concurrent syncs on every cold start.

**Fix:**
Remove the explicit call at line 300. Let the network callback be the sole trigger:
```kotlin
// Remove this line from onCreate():
// triggerImmediateSyncIfOnline()

// The networkCallback.onAvailable() will fire immediately if online
// and trigger the sync — no need to call it twice
```

---

### MODERATE

#### 7. No Hash-Based Sync — Re-downloads Unchanged Media
**Problem:**
`MediaDownloader` only checks `file.exists() && file.length() > 0`. It cannot detect when a file's content changed without re-downloading, and cannot skip downloads when content is identical. Causes unnecessary bandwidth usage and download time.

**Proposed fix (client side — requires server to add `sha256` field):**

Server playlist JSON should include per-asset hash:
```json
{ "file_name": "video.mp4", "file_path": "...", "sha256": "abc123..." }
```

Client-side check in `MediaDownloader`:
```kotlin
// If file exists and server provides a hash, compare before downloading
val serverHash = asset.optString("sha256", "")
if (file.exists() && file.length() > 0 && serverHash.isNotBlank()) {
    val localHash = file.sha256()
    if (localHash == serverHash) {
        result[fileName] = file.toURI().toString()
        continue  // skip download — file unchanged
    }
}

// Helper extension
fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { fis ->
        val buffer = ByteArray(8192)
        var read: Int
        while (fis.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
```

Also store `playlist_hash` in SharedPrefs and send as `If-None-Match` header to skip entire sync when playlist is unchanged.

---

#### 8. All URLs and Keys Hardcoded Across Multiple Files

**Problem:** `"https://api.inwallz.in"` appears in 3 files. SharedPrefs keys (`"signage"`, `"last_playlist"`, `"pairing_code"`) scattered across 6 files.

**Fix:** Create `AppConfig.kt`:
```kotlin
object AppConfig {
    const val API_BASE_URL   = "https://api.inwallz.in"
    const val SOCKET_URL     = "https://api.inwallz.in"
    const val PREFS_NAME     = "signage"
    const val KEY_PAIRING    = "pairing_code"
    const val KEY_PLAYLIST   = "last_playlist"
    const val SYNC_INTERVAL_MINUTES = 15L
    const val WEBVIEW_LOAD_DELAY_MS = 500L
    const val MAX_DOWNLOAD_RETRIES  = 3
    const val MAX_DATA_URI_BYTES    = 4 * 1024 * 1024L
}
```

---

#### 9. `WebContentsDebuggingEnabled(true)` in Production

**Fix:**
```kotlin
// MainActivity.kt
WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
```

---

#### 10. `LOAD_NO_CACHE` Bypasses Cache for Local SPA Assets

**Fix:**
```kotlin
// MainActivity.kt
cacheMode = WebSettings.LOAD_DEFAULT  // local file:// assets don't need cache busting
```

---

### LOW

#### 11. Dead Code to Remove
- Delete `PlaylistStorage.kt` — never called; `PlaylistRepository` writes directly to SharedPrefs
- Delete `PlaylistBroadcaster.kt` — duplicates `PlaylistUpdateBus.send()`, never called
- Rename `AndriodApp.kt` → `AndroidApp.kt` (filename typo)

#### 12. Manifest Issues
```xml
<!-- Add to AndroidManifest.xml -->
android:allowBackup="false"          <!-- prevent ADB extraction of pairing code -->
android:launchMode="singleTask"      <!-- on MainActivity — prevent instance stacking -->

<!-- Remove exported=true from MainActivity (no intent-filter needs it) -->
android:exported="false"
```

#### 13. Enable R8 Minification in Release
```kotlin
// app/build.gradle.kts
buildTypes {
    release {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}
```

---

## Fix Priority Order for Next Session

| Priority | Fix | Estimated Effort |
|---|---|---|
| 1 | Add `Mutex` to `PlaylistRepository.fetchAndSave()` | 15 min |
| 2 | Add `ForegroundService` (`SignageService.kt`) | 1 hr |
| 3 | Fix `onPause()` → `singleTask` + `moveTaskToFront` | 30 min |
| 4 | Fix duplicate socket guard | 5 min |
| 5 | Remove double `triggerImmediateSyncIfOnline()` call | 5 min |
| 6 | Create `AppConfig.kt` constants file | 20 min |
| 7 | Hash-based sync (client side) | 1 hr |
| 8 | `WebContentsDebuggingEnabled` → `BuildConfig.DEBUG` | 2 min |
| 9 | `LOAD_NO_CACHE` → `LOAD_DEFAULT` | 2 min |
| 10 | Delete dead code + rename typo file | 10 min |
| 11 | Manifest fixes (allowBackup, launchMode, exported) | 10 min |
| 12 | Enable R8 minification | 10 min |

---

## Hardware / Android Box Checklist

These are box-level settings to verify on each device — not code fixes:

| Check | How | Expected |
|---|---|---|
| Sleep timer | Android Settings → Display → Sleep | Set to **Never** |
| Kiosk mode active | Press back button after launch | Completely blocked |
| Device Owner set | `adb shell dpm list-owners` | App package listed |
| OOM kill evidence | `adb logcat \| grep -E "Killed\|OutOfMemory"` | None during operation |
| Memory stable | `adb shell dumpsys meminfo com.example.pisignage` | Not growing over hours |
| Playlist syncing | `adb logcat \| grep PLAYLIST` | Sync logs every 15 min |

---

## Branches

| Branch | Description |
|---|---|
| `main` | Original upload — all memory leaks present |
| `mem_leak` | Memory leaks fixed (5 issues) — **current working branch** |
| `next` (suggested) | Create this for the remaining fixes above |

---

*Generated by Claude Code · June 2026*
