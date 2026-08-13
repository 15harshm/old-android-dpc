# Running DPC — Project Context (Agent Onboarding)

> The 5-minute orientation for any agent/model touching this repo. Read this before editing.
> For flavor work specifically, then read [`flavor_system.md`](flavor_system.md) +
> [`../workflows/add_new_client.md`](../workflows/add_new_client.md).

_Last updated: 2026-07-08._

---

## 1. What this app is

A **white-label EMI / device-financing locker**. A retailer sells a phone on installments; this app
(a DPC — Device Policy Controller) is installed so the lender can **remotely lock the phone into a
full-screen kiosk** if the customer misses a payment, and unlock it when they pay. 11 branded
clients ("flavors") share one codebase — see [`flavors.md`](flavors.md).

- **Flutter** front end (`lib/`): 3-screen onboarding — IMEI entry → permission setup → main/EMI info.
- **Kotlin/Android** native (`android/app/src/main/kotlin/com/renew/jss/`): **all real enforcement**.
- **Node.js `server.js`**: real-time FCM/Socket.IO command + push server (separate from the PHP `/api2` data API the app polls).
- Package/`applicationId`: **`com.renew.jss`** (shared by all flavors). MethodChannel: **`com.renew.jss/admin`**.

## 2. 🚨 THE critical fact: Device Admin, NOT Device Owner

This app is enrolled as a **Device Admin** (user taps "Activate" in Settings, or the in-app
`ACTION_ADD_DEVICE_ADMIN` flow) on **already-in-use / second-hand phones**. It is **NOT** a Device
Owner or Profile Owner — those need a factory-reset/QR provisioning that running phones can't do.

The codebase was **converted from an older Device-Owner DPC**, so beware:
- **Device-Owner/Profile-Owner-only `DevicePolicyManager` APIs DO NOT WORK.** Never add/rely on:
  `setLockTaskPackages`, `startLockTask`, `setStatusBarDisabled`, `setKeyguardDisabled`,
  `addUserRestriction`/`clearUserRestriction`, `setApplicationHidden`, `setUninstallBlocked`,
  `setGlobalSetting`/`setSecureSetting`, `setLocationEnabled`, `setPermissionGrantState`,
  `setApplicationRestrictions`, `setSystemUpdatePolicy`, DO `wipeData`, ownership transfer.
- **Device-Admin-safe (used here):** `isAdminActive`, `setCameraDisabled` (P+, with `<disable-camera/>`),
  `getCameraDisabled`, `removeActiveAdmin`, `resetPassword`, `setMaximumTimeToLock`.
- **Misleading names:** the admin receiver class is `DeviceOwnerReceiver` but is a plain
  `DeviceAdminReceiver`; log strings say "device owner" but the real gate is `isAdminActive()`.
- The old DO/provisioning leftovers (FRP `setApplicationRestrictions`, `ProvisioningModeActivity`,
  `AdminReceiver`, transfer-ownership manifest entries) were **removed 2026-07** — do not reintroduce.

## 3. How lock/kiosk works (no lock-task)

Network-independent; survives offline, reboot, SIM swap. No overlay window, no lock-task:
- **`activity/KioskActivity.kt`** — the lock screen. `FLAG_SHOW_WHEN_LOCKED`/`DISMISS_KEYGUARD`/
  `FULLSCREEN`/`SECURE`; blocks BACK/HOME/RECENTS/VOLUME keys; HMAC time-based PIN unlock.
- **`service/MyAccessibilityService.kt`** — the enforcement muscle. Relaunches KioskActivity when a
  launcher is detected while locked; blocks Settings→Security/Developer/Device-admin/Factory-reset/
  Uninstall, Play-Protect, wallpaper-change, recents. `accessibility_service_config.xml` lists **only**
  Settings/Launcher/PlayStore/SystemUI packages (NOT `@null`/all-apps — that would make banking/UPI
  apps warn the user).
- **`service/KioskEnforcementService.kt`** — 5s loop re-launching KioskActivity while locked.

## 4. Policy engine & command channels

**Flow:** backend → (FCM / SMS / HTTP poll) → `PolicyChangeProcessor` → `PolicyDispatcher.apply()` →
concrete `*Policy` → DPM/Settings + state stores.

- **`policy/PolicyChangeProcessor.kt`** — the real engine (throttle, de-dupe, persist, re-apply on boot).
- **`policy/PolicyDispatcher.kt`** — `when` over UPPERCASE keys: `CAMERA`, `KIOSK`, `LOCATION`,
  `WALLPAPER`, `REMOVE_ALL_RESTRICTIONS`.

**Three command ingress paths:**
1. **FCM** (`service/MyFirebaseMessagingService.kt`, primary) — types `POLICY_UPDATE`, `FETCH_GPS`,
   `AUDIO_REMINDER`, `REMOVE_MOBILE`, `DISABLE_ACCESSIBILITY`, `NOTIFICATION` (online heartbeat →
   `FCM_DeviceActiveStatus.php`). De-duped by messageId.
2. **SMS** (`receiver/SmsCommandReceiver.kt`, offline fallback) — two exact-string commands (lock/
   unlock) from an authorized "JSSINF" sender. Brittle by design.
3. **HTTP poll** (`service/PolicyCheckWorker.kt`, every 15 min) — for FCM-throttled devices.

## 5. Services, storage, gotchas

- **Foreground services:** `PolicyMonitoringService` (command sink), `AlwaysAliveService`,
  `PreventiveService`, `KioskEnforcementService`. **Watchdog redundancy is heavy** (3+ overlapping
  restart paths) — see [`../plans/dpc_optimization_plan.md`](../plans/dpc_optimization_plan.md).
- **Storage = SharedPreferences only (no DB).** ⚠️ **Two overlapping lock-state stores** kept in sync
  by hand: `LockedStateStore` (`emi_lock_state`) + `KioskStateManager` (`kiosk_state_manager`). Both
  must be false to be truly unlocked — a split-brain risk and a refactor candidate.
- **Duplicate packages:** `receiver/` vs `receivers/` — check which class the manifest registers
  before editing (the registered `BootReceiver` is `.receivers.BootReceiver`).

## 6. Where things live (quick map)

| Concern | Location |
|---|---|
| Native root | `android/app/src/main/kotlin/com/renew/jss/` |
| Admin receiver | `DeviceOwnerReceiver.kt` (a plain `DeviceAdminReceiver`) |
| Policy engine | `policy/PolicyChangeProcessor.kt`, `policy/PolicyDispatcher.kt`, `policy/*Policy.kt` |
| Lock UI | `activity/KioskActivity.kt` + `service/MyAccessibilityService.kt` + `service/KioskEnforcementService.kt` |
| Command in | `service/MyFirebaseMessagingService.kt` (FCM), `receiver/SmsCommandReceiver.kt` (SMS), `service/PolicyCheckWorker.kt` (poll) |
| Central control | `device/DeviceController.kt`, `activity/MainActivity.kt` (MethodChannel `com.renew.jss/admin`) |
| Flavor source of truth | `tool/flavors/flavors.yaml` → generated `android/flavors.gen.json` + `lib/config/flavor_registry.g.dart` |
| Flavor config (derived) | `android/app/build.gradle.kts` (loop), `ApiConfig.kt` (BuildConfig reads), `lib/config/app_config.dart` (registry lookup) |
| Flutter entry | `lib/main.dart` → `AppConfig.initialize()` → `HomePage` state machine |
| Backend | `server.js` (Node) + PHP `/api2` endpoints |

## 7. Recent structural changes (2026-07)

- **DO/provisioning leftovers removed** (see §2): FRP `setApplicationRestrictions`, unreachable kiosk
  block, orphaned `AdminReceiver`, provisioning activities + manifest entries.
- **Dead `lib/features/` parallel layer removed**: the app runs on `lib/widgets/` + `lib/services/` +
  native. `dpc_api.dart` pruned to just `getImei`. (Kept live: `policy_data.dart`,
  `policy_diff_engine.dart`, `policy_enforcement_manager.dart` — decision logging only.)
- **Permission screen** now shows **only during first enrollment** (gated purely on persisted
  `permissions_setup_completed`, not a live permission re-check).
- **Flavor system rebuilt** to a single-source-of-truth registry + codegen — see
  [`flavor_system.md`](flavor_system.md).
- **Hardening:** signing secrets → git-ignored `keystore.properties`; `network_security_config.xml`
  (cleartext off); `versionCode` from CI env; `.gitignore` tightened; CI matrix in
  `.github/workflows/build.yml`. **Still TODO** (documented in `flavor_system.md` §3.1): `git rm
  --cached` the already-committed secrets, and rotate the leaked keystore.

## 8. Build & verify

```bash
flutter analyze                                   # Dart static analysis
flutter build apk --flavor <id> --release         # e.g. --flavor emipay
# verify a flavor's launcher label from the APK:
#   aapt dump badging build/app/outputs/flutter-apk/app-<id>-release.apk | grep application-label
```

Known-good flavors to smoke-test after a change: `emipay` (basic theme) and `fastemi` (premium
`usesFastEmiUi` path + a `kiosk_layout.xml` override).
