# Running DPC Optimization Plan

> 📌 **Status: backlog (partially done).** Items #3 (dead `GpsFetchService` start), #4 (no-op
> MethodChannel stubs), #5 (reuse `OkHttpClient`), #8, #10 were addressed during 2026-07 work. The
> watchdog-consolidation / WakeLock items remain open and should be done **after** a test net exists
> (see [`flavor_productionization_plan.md`](flavor_productionization_plan.md) §4 P2). Project context:
> [`../info/project_context.md`](../info/project_context.md).

Based on a full review of the codebase — no code changes, analysis only.

---

## 1. Redundant Watchdog Systems → Consolidate into One

**Current state:** Three separate mechanisms all do the same job (restart services if killed):

| Watchdog | Trigger | What it does |
|----------|---------|--------------|
| `PreventiveService` Timer | Every 1 hour (1s initial) | Posts `startForegroundService(PMS + AlwaysAliveService)` to main thread |
| `MyAccessibilityService.ensureServicesAlive` | Every accessibility event (30s throttle) | Starts AlwaysAliveService, PMS, optionally KioskEnforcementService |
| `ScreenActionReceiver.onReceive` | Every screen unlock/on | Starts PMS + AlwaysAliveService (600ms delay) |

**Problem:** All three overlap. On a typical unlock, all three fire within seconds of each other → 6+ redundant `startForegroundService` calls hit the main thread in a burst.

**Plan:**
- Make `ScreenActionReceiver` the **primary** screen-event watchdog — it already has good guard logic (`PolicyMonitoringService.isRunning` check)
- Make `MyAccessibilityService` watchdog only start services **not already running** (already partly done) and increase throttle to 60s
- Consider removing `PreventiveService` entirely or increasing its interval to 6 hours — the other two watchdogs already cover restarts more responsively

---

## 2. `startMonitoringService` Called from Too Many Places

**Current state:** `startMonitoringService()` is called from:
1. `MainActivity.onCreate()` (directly)
2. `applyAdminStartup` coroutine (via MethodChannel)
3. `MyAccessibilityService.onServiceConnected` delayed block

This causes `PolicyMonitoringService.onStartCommand` to fire **6+ times** on every cold start.

**Problem:** Even though each call is fast, they all dispatch to the main looper, creating a noisy burst. Each call also triggers `onStartCommand` which re-runs `startForeground()` repeatedly.

**Plan:**
- Add a **global "services already started" flag** (similar to `PolicyMonitoringService.isRunning`) checked before each of these 3 call sites
- The `applyAdminStartup` coroutine path already calls `startMonitoringService` — the `MainActivity.onCreate` path and `AccessibilityService` path could check this flag and skip if `PolicyMonitoringService.isRunning == true`

---

## 3. Dead Code: `startLocationMonitoring` → `GpsFetchService`

**Current state:** The `startLocationMonitoring` MethodChannel handler does:
```kotlin
intent.setClassName(this, "com.renew.jss.service.GpsFetchService")
startForegroundService(intent)
```
But `GpsFetchService` is a **Kotlin `object`** (singleton utility), not a `Service`. Android silently fails to start it.

**Impact:** GPS location is never actively fetched via this path. Any GPS functionality relying on this service start is silently broken.

**Plan:**
- Either convert `GpsFetchService` into a proper `Service` subclass with a foreground notification, OR
- Change the handler to call `GpsFetchService.fetchLocation(context)` directly on a background thread if it's just a utility

---

## 4. Redundant MethodChannel Stubs → Remove 4 No-op Round Trips

**Current state:** Flutter calls 8-9 platform methods in parallel on startup. Four of them are **instant stubs** that just log and return `true`:
- `initializeFCM` — Firebase auto-initializes, no code needed
- `initializeDeviceAdmin` — No actual work done
- `startAppMonitoring` — Comment says "handled by PolicyMonitoringService"
- `startCommunicationMonitoring` — Same comment

**Impact:** Each `invokeMethod` call involves Dart→JNI→Java→JNI→Dart round-trip overhead. Four wasted round trips on every startup.

**Plan:**
- Remove these 4 calls from the Flutter `Future.wait([...])` list
- Remove the corresponding handler cases from `configureFlutterEngine`
- Net result: startup `Future.wait` reduces from 8–9 calls to 4–5 meaningful ones

---

## 5. `OkHttpClient` Created Fresh on Every `sendSavedFcmToken` Call

**Current state:** `sendSavedFcmToken()` creates a **new** `OkHttpClient` with custom timeouts on every invocation:
```kotlin
val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    ...
    .build()
```

But `MainActivity` already has a class-level `private val client = OkHttpClient()` at line 52.

**Impact:** Creating a new `OkHttpClient` allocates a new thread pool and connection pool each time. These are expensive objects meant to be shared. The existing class-level `client` goes unused in this method.

**Plan:**
- Reuse the class-level `client` in `sendSavedFcmToken()` (just add the timeout config to the class-level instance if needed, or use `.newBuilder()` to extend it with timeouts)

---

## 6. WakeLock Strategy — Always-On vs. Demand-Based

**Current state:** `PolicyMonitoringService` acquires a `PARTIAL_WAKE_LOCK` in `onCreate()` and **never releases it** until `onDestroy()`. The service runs forever (`START_STICKY`).

**Impact:** A permanent partial wake lock prevents the CPU from sleeping — significant battery drain, especially noticeable on budget devices (Samsung M12 with aggressive battery management).

**Plan (two options):**
- **Option A (conservative):** Keep the wake lock but make it **timeout-based** — acquire it for 60 seconds at a time, re-acquire only when processing an FCM command or running policy enforcement
- **Option B (aggressive):** Remove the wake lock from `PolicyMonitoringService` entirely — rely on FCM (which holds its own wake lock during `onMessageReceived`) and `WorkManager` (which acquires a wake lock for the duration of worker execution). This is the modern Android pattern

---

## 7. PreventiveService Timer Fires on Main Thread

**Current state:** `PreventiveService.reviveServices()` posts to `Handler(Looper.getMainLooper())` inside a background `Timer` thread:
```kotlin
Handler(Looper.getMainLooper()).post {
    startForegroundService(PolicyMonitoringService)
    startForegroundService(AlwaysAliveService)
}
```

**Impact:** Every hour, two `startForegroundService` calls land on the main thread. While these are individually fast, if they coincide with user interaction, they add latency to that frame.

**Plan:**
- Use `context.startForegroundService()` directly from the Timer thread — `startForegroundService` is thread-safe and doesn't need to be called from the main thread
- Or use a `HandlerThread` + `Handler` for the timer (avoid `java.util.Timer` which uses a daemon thread that can be killed)

---

## 8. `PolicyMonitoringService.onStartCommand` — Redundant `startForeground()` on Every Call

**Current state:** Every time `onStartCommand` is called (6+ times on cold start), it calls `startForeground()` unconditionally. The notification is identical each time.

**Impact:** Redundant `startForeground()` calls are noisy and can cause notification flicker on some devices.

**Plan:**
- Add a flag `private var foregroundStarted = false` in the service
- Only call `startForeground()` when `!foregroundStarted` → set flag after first call
- All subsequent `onStartCommand` calls skip the `startForeground()` call

---

## 9. `KioskEnforcementService` — Verify HandlerThread is Cleaned Up

**Current state:** `KioskEnforcementService` uses a `HandlerThread` for the enforcement loop. On `onDestroy()`, this thread needs to be properly quit.

**Plan:** Audit `KioskEnforcementService.onDestroy()` to ensure `handlerThread.quitSafely()` is called. If not, the thread leaks on every kiosk mode toggle.

---

## 10. Flutter `didChangeAppLifecycleState` — SharedPreferences on Every Resume

**Current state:** On every `AppLifecycleState.resumed`, the app calls:
```dart
SharedPreferences.getInstance().then((prefs) {
    final setupDone = prefs.getBool('permissions_setup_completed') ?? false;
    ...
});
```

**Impact:** `SharedPreferences.getInstance()` on every resume re-reads from disk (first call) or cache (subsequent). Better to cache the result.

**Plan:**
- Cache `setupDone` in a `bool` class variable after first read
- Set it to `true` when `_onAllPermissionsGranted()` completes
- The `didChangeAppLifecycleState` check just reads the cached bool — no async needed

---

## Priority Order

| Priority | Item | Effort | Impact |
|----------|------|--------|--------|
| 🔴 High | #3 Fix dead GpsFetchService integration | Low | GPS feature broken |
| 🔴 High | #5 Reuse OkHttpClient | Low | Memory/thread leak |
| 🟠 Medium | #4 Remove 4 no-op MethodChannel calls | Low | Cleaner startup |
| 🟠 Medium | #2 Deduplicate startMonitoringService | Medium | Fewer service restarts |
| 🟠 Medium | #8 Skip redundant startForeground() | Low | Less noise |
| 🟡 Low | #1 Consolidate watchdogs | Medium | Battery improvement |
| 🟡 Low | #6 WakeLock demand-based | Medium | Battery improvement |
| 🟡 Low | #7 Move PreventiveService off main thread | Low | Frame timing |
| 🟡 Low | #10 Cache setupDone in Dart | Low | Minor |
| ⚪ Nice | #9 Audit KioskEnforcementService cleanup | Low | Memory safety |
