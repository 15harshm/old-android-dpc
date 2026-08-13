# Forward Roadmap — `com.renew.jss` Device-Admin EMI Locker

## 1. Where it stands

This is a **Device-Admin** (not Device-Owner) locker, so every strong OS containment primitive — lock-task/kiosk, status-bar/keyguard control, user restrictions, uninstall-block, FRP — is unavailable, and the entire lock is a **soft, app-level loop** (relaunched `KioskActivity` + `MyAccessibilityService` bounce + polling foreground services) governed by a plaintext SharedPreferences boolean. The security core is worse than the enforcement core: a **fleet-wide HMAC master key** (`AuthEghrigwsibfBDsfrrgujrtnbi`) is hardcoded in `KioskActivity.kt` with minify off, the unlock PIN **replays daily** and has **no lockout**, all four remote command channels are **unauthenticated**, and the release **signing password is in git history**. Reliability is fragile-by-design: a dozen overlapping watchdogs all live in **one process** (one kill drops everything), and nearly every restart path calls a **background `startForegroundService()`/`startActivity()` that throws or is BAL-blocked** on Android 12+, silently swallowed. The good news: a large, high-value set of fixes is genuinely achievable under plain Device Admin, and none require touching the backend.

**Two irreducible truths to design around:** (a) a **force-stopped / cleared-data** package receives no broadcasts and cannot self-restart until the user manually relaunches — only Device Owner closes this; (b) **safe mode** disables the app entirely. Everything below either works *despite* these or degrades to server-observable detection, never to a false claim of prevention.

---

## 2. Roadmap by theme

### 2.1 Enforcement & anti-tamper (within Device-Admin limits)

| Improvement | Impact | Effort | DA | Notes |
|---|---|---|---|---|
| Fail-closed cold-start default (`BootReceiver`, `MainActivity`, `PolicyMonitoringService.onCreate`, `KioskActivity.onResume:145`) | High | Med | partial | Genuinely re-locks on **normal reboot & app-update** (`MY_PACKAGE_REPLACED` *is* delivered). Cannot self-heal clear-data/force-stop (stopped-state gets no broadcast). **Alt:** make detection server-authoritative — treat a missed heartbeat past a timeout as "went dark → locked" so the next manual launch re-locks; keep a signed, expiring local unlock receipt as the offline honor path. |
| Persist enrollment/lock marker outside app-private storage | High | Med | partial | MediaStore blob (Documents) + `AccountManager` account both survive clear-data under DA. **Alt to the broken HMAC-over-IMEI design:** `getImei()` throws for non-DO on API 29+ and the Keystore key is wiped by clear-data — instead bind the marker to `Settings.Secure.ANDROID_ID` (no permission) and verify with an **APK-embedded public key** (survives clear-data, lives in the APK). Treat as **presence⇒stay-locked, absence⇒never-unlock**; it is a deterrent/detector, user-deletable, not an OS block. |
| `onDisableRequested` deterrent text + `onDisabled` escalation (`DeviceOwnerReceiver.kt:37-42`) | Med | Low | yes | The one built-in DA hook: return a warning `CharSequence`, set `admin_removed` flag, keep accessibility+kiosk alive (a11y grant is independent of admin), loop `PermissionEnforcementActivity` re-enroll nag. Deterrence + telemetry only — cannot block deactivation; safe-mode bypasses it. |
| Active watchdog for admin/accessibility loss | Med | Med | yes | Poll `dpm.isAdminActive()` + parse `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` in existing loops; on loss render a **full-screen `TYPE_APPLICATION_OVERLAY`** (already-held SAW) — sidesteps BAL when a11y is the thing that got disabled. Deep-link `ACTION_ACCESSIBILITY_SETTINGS` (list only; no guaranteed exact-toggle). |
| Recents/overview hardening | Low | Low | yes | `excludeFromRecents` already set (`AndroidManifest.xml:87`); real delta is `taskAffinity=""` + re-assert-immersive-on-focus. Cannot suppress system overview (DO-only). |
| High-priority FCM wake-and-relock (`MyFirebaseMessagingService.onMessageReceived`) | High | Med | partial | Strongest revival lever a non-DO app has (temp FGS-BG-start exemption under Doze). Recovers Doze/standby/cached; **not** force-stopped state. **Alt for the relaunch step:** don't rely on an "FCM BAL grant" (doesn't cover activity starts on 14/15) — relaunch via SAW overlay or a full-screen-intent notification (`USE_FULL_SCREEN_INTENT`). |
| Safe-mode / enforcement-gap detection | Med | Low | partial | Prevention needs DO (`DISALLOW_SAFE_BOOT`). **Alt:** drop `isSafeMode()` (never runs in safe mode) and use a **heartbeat-gap vs server-trusted time** heuristic in `BootReceiver` → re-lock + require online re-verify. Detects safe-mode/force-stop/power-off generically. |

### 2.2 Reliability & OEM survival (Android 12–16)

| Improvement | Impact | Effort | DA | Notes |
|---|---|---|---|---|
| Route ALL `KioskActivity` relaunches through `MyAccessibilityService` (BAL-exempt) | High | Med | yes | Single `KioskLauncher.relaunch()` prefers `MyAccessibilityService.instance?.startActivity(...)` (the known-good path, `MyAccessibilityService.kt:333-337`), falls back to SAW. Fixes the BAL-blocked bg starts in `KioskEnforcementService.kt:361`, `PolicyCheckWorker.kt:250`, `BootReceiver.kt:97`. **Boot gap:** a11y not bound yet → use full-screen-intent notification. |
| Make Doze/battery-opt exemption real & verified | High | Med | yes | Fire `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (already works `MainActivity.kt:286`), gate setup on `isIgnoringBatteryOptimizations()`, and **fix the fake-grant** in `HardeningPermissionManager.kt:36-40` (currently "granted" on prompt-shown). OEM autostart stays best-effort/unverifiable. |
| Fix WorkManager: `KEEP→UPDATE`, expedited one-shot, drop `NetworkType.CONNECTED` on offline enforce (`FcmHeartbeatWorker.kt:68`, `PolicyCheckWorker.kt:43,57`) | High | Low | yes | KEEP freezes stale schedules; the "network-independent" enforce path is self-contradictorily gated on network. **Note:** running offline doesn't exempt the actions inside — hand the relaunch/FGS off to the accessibility channel, not a bare `startActivity`. |
| Fix AlarmManager backstop | Med | Med | yes | Replace `set(...getService)` (inexact + illegal bg FGS start) with **`setAndAllowWhileIdle()`** (pierces Doze, **no permission** — avoid the Play-restricted exact-alarm entirely) → `PendingIntent.getBroadcast` → `BootReceiver` enqueues a job. **Gaps to close:** `BootReceiver` doesn't handle `com.renew.jss.RESTART_SERVICE` (declared `AndroidManifest.xml:211`); the job service doesn't exist yet. |
| Persisted JobScheduler guardian (replace `onDestroy`/`onTaskRemoved` restart storms) | High | Med | partial | `setPersisted(true)` survives reboot/process-death. **Correction to the proposal's false premise:** a plain job is **not** an FGS-BG-start exemption — do the work inside the job window or an **expedited** worker, and gate any `startForegroundService` on `isIgnoringBatteryOptimizations()`. On MIUI/ColorOS the same autostart block also suppresses jobs. |
| Consolidate 4 services + Timer into one `GuardianService`/`EnforcementCoordinator` + health record | High | High | yes | Collapse `PolicyMonitoringService`/`AlwaysAliveService`/`PreventiveService`/`KioskEnforcementService` (enforce body at `KioskEnforcementService.kt:235`) behind one throttled loop; workers/receivers call `enforceNow()`. Removes store races, cuts the wakeup/ANR storm that *invites* OEM kills, and gives one "is enforcement healthy?" answer (→ Crashlytics keys). Behavioral benefit unproven vs a single force-stop — stage behind the test net. |
| Direct-boot-readable lock state | Med | Med | yes | Mirror the boolean into `createDeviceProtectedStorageContext()` so `LOCKED_BOOT_COMPLETED` isn't a no-op (`LockedStateStore` reads CE storage, unavailable pre-unlock). Read side only; enforcement *actions* pre-unlock still face FGS/BAL limits. |
| Replace `getRunningTasks` with self-tracked foreground flag + calmer cadence (`KioskEnforcementService.kt:431-449`) | Med | Low | yes | Pattern already exists (`KioskActivity.isLockScreenVisibleStatic`, `:74-76`); make it `@Volatile`, read it instead of the deprecated API, demote the 5s loop to a slow backstop and lean on a11y leave-events. Prefer `onStop` over `onPause` to avoid PIN-dialog flicker relaunches. |
| Split minimal reviver into `:watchdog` process + `stopWithTask=false` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` | Med | Med | yes | Add `stopWithTask=false` to `AlwaysAliveService`/`PreventiveService` (`AndroidManifest.xml:174-187`) and the FGS subtype property (Play 14+ review). Separate process narrows single-kill blast radius only — **does not** survive force-stop/`FLAG_STOPPED`; the reviver must use job/FCM/a11y, never a bg FGS start. |

### 2.3 Leveraging unused Device-Admin capabilities (still functional on SDK 36)

| Improvement | Impact | Effort | DA | Notes |
|---|---|---|---|---|
| `setMaximumTimeToLock(admin, ~5–15s)` in `KioskPolicy.enter()`, reset `0` in `exit()` | Med | Low | yes | Under non-deprecated `<force-lock/>`; engages OS keyguard on screen-off and triggers `USER_PRESENT` revival. Strong only if a secure credential exists; nuisance-locks during legit EMI use. |
| `lockNow()` on **tamper events** only (`MyAccessibilityService` settings-match, `SimChangeReceiver`) | Med | Low | yes | `<force-lock/>`, fully supported for legacy DA. Scope to discrete events with a ~3s throttle (not the 5s loop) to avoid the black-screen flash that got it removed from `enter()`. |
| `getStorageEncryptionStatus()` in periodic report | Low | Low | yes | Read-only, no privilege. Low signal (FBE mandatory since Android 10 → almost always ACTIVE) but free fleet risk-scoring. |
| Runtime **capability probe** of DA policies + honest reporting; retire the `CameraPolicy` "it works" assumption | Med | Low | yes | `setCameraDisabled` is deprecated for legacy DA on 12+ and `CameraPolicy.kt` swallows the throw. **Probe by read-back only** (`getCameraDisabled(admin)`, `getMaximumFailedPasswordsForWipe(admin)`) — never actively call `setMaximumFailedPasswordsForWipe` (installs a live wipe trigger on pre-12). "No exception" ≠ enforced. |
| `wipeData(0)` as **server-authorized** last resort | High | Med | yes | `<wipe-data/>` not deprecated for legacy DA. **Gate hard** — see §5. Only over an authenticated channel, never the current SMS static-string path; no FRP results (not DO). |

### 2.4 Security & trust surface

| Improvement | Impact | Effort | DA | Notes |
|---|---|---|---|---|
| Delete hardcoded keystore fallback, rotate key with **v3 lineage**, purge git history (`build.gradle.kts:47-50`, commit `c4d551c`) | High | Low | yes | Replace fallback with `getProperty(...) ?: throw GradleException(...)`. v3 rotation (`apksigner rotate --lineage`) keeps enrolled devices updatable (all ≥ API 28); admin binding is keyed to package/component, survives resign. BFG the `Jss@90912` literal. `.jks`/`keystore.properties` already git-ignored. |
| Move HMAC master key to NDK + enable **R8/minify** (`build.gradle.kts:57-58`, `KioskActivity.kt:533,593`) | High | Med | yes | Obscurity not secrecy (Frida still dumps it), so pair with per-device keying. **R8 keep-rules required** for `DeviceAdminReceiver`, `*Service`, `*Worker`, FCM service — ship in a canary flavor first. Also removes shipped log strings. |
| Per-device unlock key provisioned at enrollment | High | High | yes* | Client side is pure DA (Keystore/`EncryptedSharedPreferences`, FCM receive). *Requires dealer-PIN-tooling coordination to derive the same secret — **no `server.js` change** if it reuses data enrollment already delivers; otherwise keep NDK+R8 as the client-only blast-radius reducer. Keep the old fleet key valid only during a migration window. |
| Harden unlock PIN: date-in-HMAC + anti-rollback + lockout (`KioskActivity.kt:522,564-567,577-587,593`) | High | Low | yes | (1) Fold `yyyyMMdd` into `timeKey` (kills daily replay — **coordinate the dealer PIN formula**); (2) monotonic max-seen-slot floor sourced from the **HTTP `Date` header** of existing HTTPS calls (`connection.getHeaderFieldDate`) — clamp-forward, don't hard-fail; (3) persistent fail-count + exponential backoff disabling the unlock button. All client-only. |
| Authenticate FCM commands client-side (`MyFirebaseMessagingService.onMessageReceived:48-150`) | High | Med | partial | Sender-ID pin (`BuildConfig` `EXPECTED_FCM_SENDER`), IMEI-match (use existing `getDeviceImei()` reading `user_prefs`, **not** the non-existent `DeviceController.getImei()`), and a `sentTime`/nonce **freshness** guard on top of `messageId` dedup. Sender-ID+IMEI are fleet-wide/non-secret → defense-in-depth, not real auth; the freshness guard is the robust part. **Fail-open** on empty stored IMEI. Gate self-weakening commands (`DISABLE_ACCESSIBILITY`, `REMOVE_MOBILE`) strictest. True per-device HMAC needs a server signer → stub `verifyPayloadSignature()`. |
| SMS command hardening (`SmsCommandReceiver`) | Med | Low | yes | Drop the `"JSSINF"` **substring** sender trust and the static fleet-wide body strings; require an IMEI+time-window OTP validated like the unlock code. |

### 2.5 Code-health & observability

| Improvement | Impact | Effort | DA | Notes |
|---|---|---|---|---|
| Firebase **Crashlytics** (ride existing Firebase) | High | Low | yes | Fix BOM ordering (`build.gradle.kts:116-117` — BOM must precede `firebase-messaging`, drop the `23.4.1` pin), add plugin+dep, `recordException(e)` in the empty catch blocks of `AlwaysAliveService`/`PreventiveService`/`KioskEnforcementService`/`FcmHeartbeatWorker`/`PolicyCheckWorker`. Custom keys = **enrollment id, not IMEI**. |
| Single integrity-protected `LockRepository` (merge `LockedStateStore` + `KioskStateManager`) | High | Med | yes | OR-merge migration (**fail-safe: either says locked ⇒ locked**), Keystore-HMAC tag over `{locked, updatedAt, enrollmentId}`, fail-closed on invalid tag; delete the never-called `syncWithLockedState()`. Keep a directBoot plaintext shadow flag. Beats casual pref edits, not a determined root. **Gate behind the test net.** |
| Logging facade with release stripping + PII redaction | High | Med | yes | ~814 ungated `Log.*` calls leak IMEI/SIM/GPS in release (`GpsFetchService.kt:81`, `RemoveAllRestrictionsPolicy.kt:123-124`, `MainActivity.kt:624`). `BuildConfig.DEBUG` gate + regex redaction (mask last-4) + route WARN/ERROR to `Crashlytics.log()`; re-save files UTF-8 to kill the `âœ…` mojibake. |
| Test net: `LockRepository`, `PolicyChangeProcessor`, PIN vectors, real 3-screen flow | High | Med | yes | Replace the stock counter `test/widget_test.dart`. Robolectric/JUnit for merge/fail-safe/PIN-rollback; abstract storage behind an interface (Robolectric can't do Keystore). **This is the enabling safety net for the store-merge/R8/PIN/secret work.** |
| Rename `DeviceOwnerReceiver` via subclass shim | Low | Low | yes | Move logic to `JssDeviceAdminReceiver`, keep `class DeviceOwnerReceiver : JssDeviceAdminReceiver()` so the manifest component string (baked into every enrolled admin binding) is **unchanged** — renaming `android:name` would detach admin fleet-wide. Manifest-referenced ⇒ auto-kept under R8. |
| Delete dead code | Low | Low | yes | `GpsFetchService.kt` (unregistered, still logs IMEI/GPS), `LockActivity` (never launched), `KioskStateManager.validateKioskState()`/`enforcementCount` — remove to shrink incident-triage surface. |

---

## 3. Prioritized phases

### P0 — Security & state-integrity foundations (stop the bleeding)
Order: **(1)** test net → **(2)** rotate signing key + v3 lineage + delete hardcoded fallback + purge history → **(3)** `LockRepository` (integrity-tagged, fail-closed, single source) → **(4)** NDK+R8 for the master key & enable minify → **(5)** PIN date-in-HMAC + anti-rollback + lockout → **(6)** Crashlytics + logging redaction.
*Rationale:* today one decompile unlocks the whole fleet forever and the signing key is publicly compromised — these are catastrophic and client-only. The test net lands first because (3)(4)(5) can silently mass-unlock the fleet.
*Verify:* PIN-vector + migration-fail-safe unit tests green; confirm a re-signed higher-`versionCode` APK installs over an enrolled device without losing admin; `strings`/jadx on the release APK no longer yields the master key or PII log strings; Crashlytics receives a forced test crash.

### P1 — Reliability the enforcement depends on
Order: route all relaunches through `KioskLauncher`/accessibility → real+verified battery-opt gate (fix `HardeningPermissionManager` fake-grant) → WorkManager `UPDATE`/expedited/network fix → direct-boot-readable state → replace `getRunningTasks` + calm cadence.
*Rationale:* every enforcement fix is worthless if the restart paths throw and are swallowed; make revival actually legal and self-observable.
*Verify:* on a MIUI/ColorOS test unit, force the app to background/Doze and confirm KioskActivity returns via the a11y path (not a swallowed BAL exception in logcat); reboot-while-locked re-locks; `isIgnoringBatteryOptimizations()` truly gates "setup complete".

### P2 — Anti-tamper friction, detection & real OS teeth
Order: FCM command auth (sender/IMEI/freshness) + SMS OTP → `onDisableRequested`/`onDisabled` escalation → active admin/accessibility watchdog (overlay restore screen) → fail-closed cold-start + heartbeat-gap server detection + out-of-app-storage marker → `setMaximumTimeToLock`/tamper-`lockNow()`.
*Rationale:* raises the cost and observability of every realistic bypass and adds the DA-legal OS keyguard teeth, layered on the now-trustworthy state store.
*Verify:* forged/replayed FCM `DISABLE_ACCESSIBILITY` is rejected; deactivating admin fires the deterrent text + server alert + re-enroll nag; clearing data then relaunching lands on the kiosk (fail-closed); tamper-`lockNow` drops to keyguard without a lock-storm.

### P3 — Consolidation, telemetry & hygiene
Order: `GuardianService`/`EnforcementCoordinator` mesh collapse (behind flag, compare kill rates) → JobScheduler+AlarmManager backstop rework → `:watchdog` process + `stopWithTask`/FGS subtype → capability probe + `getStorageEncryptionStatus` telemetry → rename receiver, delete dead code.
*Rationale:* highest-effort/structural work; do last, only with the test net and health signal in place to catch regressions in the core survival path.
*Verify:* flagged A/B shows equal-or-lower OEM kill/ANR rate vs the old mesh; health record populates one Crashlytics key set; capability matrix reported per-device so nobody trusts a silently-no-op policy again.

---

## 4. Explicitly rejected — Device-Owner-only (do not re-propose)

Given this codebase's DO-leftover history, these will **compile but throw/no-op** on the fleet:

| Idea | Blocking requirement |
|---|---|
| `setLockTaskPackages` / `startLockTask` (real kiosk pinning) | Device Owner |
| `setStatusBarDisabled` (block shade/quick-settings) | Device Owner |
| `setKeyguardDisabledFeatures` / keyguard control | DO/PO **and** deprecated for legacy DA on 12+ |
| `addUserRestriction(DISALLOW_SAFE_BOOT / DISALLOW_FACTORY_RESET / DISALLOW_ADD_USER / DISALLOW_CONFIG_SCREEN_LOCK / DISALLOW_CONFIG_DATE_TIME)` | DO/PO (silently ignored for DA) |
| `setUninstallBlocked` | Device Owner |
| Factory Reset Protection / `WIPE_RESET_PROTECTION_DATA` / `WIPE_SILENTLY` / `factoryReset()` | Device Owner |
| `resetPassword` to **set** a device credential | DO/PO (throws on O+) |
| `setMaximumFailedPasswordsForWipe` (auto-wipe on failed OS PIN) | DO/PO — throws for legacy DA on **all** Android 12+; also watches the OS credential, never the app PIN |
| `setCameraDisabled` / `setPasswordQuality`/`Complexity` | Deprecated legacy-DA setters → SecurityException on 12+ |
| `onPasswordFailed` brute-force watch | Deprecated for non-DO/PO on 12+; only sees OS keyguard, never the app's 6-digit PIN → use an **in-app** attempt counter instead |
| `DevicePolicyManager.setBatteryOptimizationEnabled` (auto-whitelist) | Device Owner — use the user-consent `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog |
| Preventing force-stop / clearing `FLAG_STOPPED` | Device Owner / privileged system app |

**Corollary that no client code can fix:** a force-stopped or cleared-data package is dead until manual relaunch, and safe mode disables the app — design for **server-side detection of the gap**, not client self-healing.

---

## 5. Legal / UX cautions (need business + consent sign-off)

- **`wipeData()` remote nuke (§2.3)** — irreversible full factory reset, destroys customer data, removes the locker itself (no relock), and with no DO leaves **no FRP** (clean, unmanaged device after). Major exposure under **RBI digital-lending / repossession norms**; a spoofed or accidental trigger is catastrophic. Ship only behind authenticated channel + explicit dealer double-confirm + server-side rate-limit + audit log + **legal review**. Never wire to any automatic/failed-attempt trigger.
- **`setMaximumTimeToLock` / tamper `lockNow` (§2.3)** — repeated forced locks during legitimate EMI-mode use are a real nuisance; keep timeouts ≥10–15s and scope `lockNow` to discrete tamper events. Requires the customer to have set a secure credential to have real teeth (and that credential is theirs — it contains, doesn't defeat, a deliberate user).
- **PIN lockout (§2.4)** — must not brick a legitimate mistyper: cap the backoff and provide an online reset/escape path; anchor to trusted time so a reboot can't clear the penalty but a genuinely-wrong clock can't strand the user.
- **Camera/keyguard/password policies** — beyond being deprecated, disabling the camera or forcing credentials is a consent-sensitive change; the capability probe (§2.3) should *report* rather than silently assert them.
- **Restricted permissions on Play** — `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SYSTEM_ALERT_WINDOW`, `USE_FULL_SCREEN_INTENT`, `BIND_ACCESSIBILITY_SERVICE`, SMS receipt: fine for an enterprise/sideloaded fleet, but a listing/policy risk if ever published publicly. Enrollment onboarding must disclose the monitoring (location, IMEI, lock control) for consumer-protection compliance.

*No backend/`server.js` changes are assumed anywhere in this roadmap. Two items — per-device unlock key (§2.4) and date-in-HMAC PIN (§2.4) — require the dealer-side PIN-generation tooling to match the new client formula; sequence those releases in lockstep with support.*