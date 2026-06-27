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
| `AndroidApp.kt` | JS interface: `onAppReady()` hook | OK (renamed from `AndriodApp.kt`) |
| `SocketManager.kt` | Socket.IO connection | Fixed |
| `PlaylistRepository.kt` | HTTP fetch + download + save + notify | Has concurrency issue |
| `MediaDownloader.kt` | Downloads individual media files with retry | Fixed |
| `MediaCleaner.kt` | Deletes unused media from disk | Race condition |
| `PlaylistSyncWorker.kt` | WorkManager 15-min periodic worker | OK |
| ~~`PlaylistStorage.kt`~~ | SharedPrefs wrapper | **Deleted (dead code)** |
| `PlaylistUpdateBus.kt` | LocalBroadcast sender | OK |
| ~~`PlaylistBroadcaster.kt`~~ | Sends same broadcast | **Deleted (duplicated PlaylistUpdateBus)** |
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

**Verification:** `:app:assembleDebug` builds clean. **Field-validated: ran 20+ hrs without
crashing on a real box (2026-06-26), clearing the previous 3–16 hr crash-to-home window.**
(Single box / single run — keep monitoring across more boxes.)

**Known limits:** recovery is reliable, but guaranteed *background* relaunch of the UI on Android 12+
would be strongest with Device Owner / lock task — see the P2 decision below. The memory leak itself
is reduced (not eliminated) by P1.

---

## P1 — Memory Pressure Reducers (DONE in `next`)

| # | Fix | File | Status |
|---|---|---|---|
| P1-1 | `Mutex` serializes all sync paths (no concurrent .tmp writes / MediaCleaner races) | `PlaylistRepository.kt` | ✅ Done |
| P1-6 | Removed duplicate cold-start sync (network callback + WorkManager already cover it) | `MainActivity.kt` | ✅ Done |
| P1-4 | `cacheMode` LOAD_NO_CACHE → LOAD_DEFAULT for local SPA assets | `MainActivity.kt` | ✅ Done |
| P1-5 | Nightly 03:00 WebView `recreate()` — preventive memory reset | `MainActivity.kt` | ✅ Done |
| P1-3 | base64 → file:// (lower 4 MB cap) | `AndroidMedia.kt` | ⏸️ **Deliberately left at 4 MB** — touches SPA media contract; risk of broken images on screen outweighs the (now non-critical) memory gain. Revisit only if `logcat \| grep WEBVIEW` shows frequent render-gone recoveries. |

---

## P2 — Kiosk Reliability (REVISED — Device Owner dropped)

> **Decision (2026-06-26): Device Owner is NOT viable for this fleet.**
> `adb shell dpm set-device-owner` only works on a factory-fresh device with no accounts and cannot
> be set remotely or via an app update. The boxes are already deployed in the field, so per-device
> ADB re-provisioning is impractical. **It is also not required** — the boxes survived 20+ hrs with
> no Device Owner because P0's recovery already works without it. Device Owner would only have made
> background relaunch *bulletproof*; the field test shows the existing ROMs are permissive enough.
>
> Therefore P2 is reduced to changes that ship as a **normal APK update** (no per-device touch):

| # | Task | File | Ships via app update? | Status |
|---|---|---|---|---|
| P2-1 | Verify/log `startLockTask()` state (informational; lock task without Device Owner = best-effort screen pinning) | `MainActivity.kt` | ✅ Yes | ✅ Done |
| P2-3 | `singleTask` launchMode on MainActivity (prevent instance stacking) | `AndroidManifest.xml` | ✅ Yes | ✅ Done |
| P2-4 | Fix duplicate socket guard (`socket != null`) — CONNECTING state bypasses current guard | `SocketManager.kt` | ✅ Yes | ✅ Done |
| P2-2 | ~~Device Owner / DeviceAdminReceiver~~ | — | ❌ No | ❌ **Dropped** |
| P2-5 | Add HOME-launcher intent-filter (`CATEGORY_HOME`) so "drop to home" = return to our app | `AndroidManifest.xml` | ✅ Yes (filter) | ✅ Done (filter; set as default Home per-device to activate) |

**HOME-launcher caveat:** adding the intent-filter is harmless and ships via update, but becoming the
*default* home needs a one-time "select Home app" tap per device — only helps boxes someone can reach
once (new deployments / RMA / on-site), not silently on existing remote boxes.

---

## P3 — Observability (DONE in `next`) + remaining cleanup

| # | Task | File | Status |
|---|---|---|---|
| P3-5 | Global crash handler → Logcat (`CRASH`) + rolling `filesDir/crash.log` (survives restart, pull via `adb shell run-as ... cat files/crash.log`) | `CrashReporter.kt` (new), `SignageApp.kt` (new), manifest | ✅ Done |
| P3-5 | 15-min memory telemetry → Logcat (`MEMSTAT`): heap / avail / low flag — `adb logcat -s MEMSTAT` | `MemoryMonitor.kt` (new), `SignageApp.kt` | ✅ Done |

> **How to use it:** watch `avail`/`heap` over hours. Steady climb = a leak the recovery is masking
> (revisit P1-3 base64). `low=true` right before a restart = an OOM kill.

> **⚠️ Renderer-kill DEATH SPIRAL found & fixed (2026-06-27):** on a memory-tight box, `logcat`
> showed the renderer being **system-killed every ~90s** (`didCrash=false`), and my
> `onRenderProcessGone → recreate()` turned each kill into a full activity rebuild: it re-ran
> `onCreate` (sync storm), **reloaded the SPA → playlist restarted from asset 1** (the "after 6
> assets it refreshes" bug), and **leaked the activity+WebView** (`dumpsys` showed Activities/WebViews
> climbing to **150**, via the `webView.postDelayed(recreate, ~19h)` nightly timer holding each
> activity). The leak kept memory pegged → next renderer kill ~90s later → spiral.
> **Fix:** recovery is now **in-place** — `onRenderProcessGone` and the nightly reset swap a fresh
> WebView into a persistent `FrameLayout` container via `rebuildWebViewInPlace()` instead of
> `recreate()`. No `onCreate` re-run, no sync storm, no playlist reset, no leak. Nightly reset moved
> off `webView.postDelayed` onto a removable `mainHandler` (cleared in `onDestroy`). Verify:
> Activities/WebViews stay at **1**, and `WEBVIEW: Render process gone` becomes rare (only on real
> memory kills, recovered silently).
> **✅ VERIFIED (2026-06-27):** after ~2.1 hrs running, `dumpsys` Objects shows **Activities: 1,
> WebViews: 1, Views: 10** (was 150/150/1201). Leak + spiral confirmed gone. `logcat` over 30 min
> shows **zero `Render process gone`** (was every ~90s) — only the normal 15-min playlist syncs —
> and the playlist **plays all assets** without restarting. Confirms the 150-WebView leak WAS the
> memory pressure killing the renderer; removing it made the renderer stable. No native-player
> rework needed for this hardware.

> **⚠️ Video-lag root cause FOUND & FIXED (2026-06-26):** the lag was NOT memory size in the Java
> heap (that stayed at ~6 MB) and NOT the telemetry/cache changes. `dumpsys meminfo` showed
> **WebViews: 2, Activities: 2–3 (churning), ViewRootImpl 2→1**, and the single in-process app RSS
> climbing 91 → 131 → 178 MB. Root cause: **duplicate, churning MainActivity + WebView instances.**
> P2-5's HOME-launcher filter + `singleTask` let MainActivity spawn in a 2nd task; the old
> `onPause→startActivity(self)` loop + the 10-min watchdog then kept the instances ping-ponging,
> bloating the in-process native heap (alloc spiked to 184 MB) → video decoder starved → lag at
> ~30 min (reopening the app, which drops the duplicates, fixed it — process pid unchanged).
> **Fix:** `launchMode` → `singleInstance` (exactly one MainActivity, ever, regardless of
> launcher/HOME/watchdog); `onPause` now `moveTaskToFront(taskId)` instead of `startActivity` (can
> never create a duplicate); watchdog `startActivity` is safe under singleInstance (re-fronts or
> cold-relaunches the single instance). Verify with `dumpsys meminfo` → must show **WebViews: 1,
> Activities: 1** after hours.

> **⚠️ Earlier regression fixed (2026-06-26):** the first telemetry version ran on the **main thread** and
> called `Debug.getMemoryInfo()` (walks /proc/self/smaps; cost grows with process size). Every 5 min
> it froze the UI thread for hundreds of ms — increasingly as memory grew — causing **video to lag
> after ~30 min** (smooth again on restart). Fixed: moved to a background `HandlerThread`, 15-min
> interval, dropped the expensive `Debug.getMemoryInfo()` for the cheap `ActivityManager` snapshot.
> Also reverted P1-4 `cacheMode` back to `LOAD_NO_CACHE` (LOAD_DEFAULT retained remote resources and
> compounded the growth). **Never sample memory on the UI thread.**

**P3 hygiene — DONE in `next`:** `WebContentsDebuggingEnabled` → `BuildConfig.DEBUG`; `AppConfig.kt`
created + URLs/keys refactored; deleted `PlaylistStorage.kt` + `PlaylistBroadcaster.kt`; renamed
`AndriodApp.kt` → `AndroidApp.kt`; `allowBackup=false`; **R8 minification enabled** with keep rules for
`@JavascriptInterface` / Socket.IO / OkHttp (release builds clean).

**P3 hygiene — intentionally NOT done:**
- `MainActivity exported=false` — **obsolete:** it now carries a HOME-launcher intent-filter, so it
  must stay `exported=true` (externally launchable). The original audit advice predates P2-5.
- Hash-based sync — needs a server `sha256` field; bandwidth optimization, not crash-related.

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

#### 3. `startLockTask()` Silently Fails — Kiosk Mode Never Active  ⚠️ SUPERSEDED — see "P2 — REVISED" above (Device Owner dropped; only the logging/fallback part survives as P2-1)
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
