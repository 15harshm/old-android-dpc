# White-Label DPC: Flavor Onboarding & Hardening Plan

> 📌 **Status: mostly implemented (2026-07-08).** This is the original design/rollout plan and is
> kept as a historical record of *why*. The **as-built reference** is
> [`../info/flavor_system.md`](../info/flavor_system.md); to onboard a client use
> [`../workflows/add_new_client.md`](../workflows/add_new_client.md). Still outstanding from §4/§5
> here: `git rm --cached` of already-committed secrets, keystore rotation, and the deeper P2/P3
> code-health items (lock-state consolidation, watchdog consolidation, tests, Crashlytics).

*Prepared for the DPC owner — an implementable plan, not a discussion. No backend/server changes are proposed anywhere in this document.*

---

## 1. The problem today

Adding **one** client (flavor) is a fully manual, cross-language edit with no single source of truth and no validation. The same three facts — the **flavor id string**, the **domain**, and the **app name** — are re-typed by hand into parallel maps in two languages plus Android resources. Here is the real inventory.

**Files touched to add one flavor: ~16** (8 existing edited + ~8 new/copied), across **~4 new directories**, in **3 languages** (Kotlin, Dart, Gradle Kotlin-DSL) plus Android XML.

| # | File | Edit |
|---|------|------|
| 1 | `android/app/build.gradle.kts` (56–103) | New `create("<id>"){ dimension="branding"; applicationId="com.renew.jss" }` block |
| 2 | `android/app/src/main/kotlin/com/renew/jss/ApiConfig.kt` (8–21) | New branch in the `DOMAIN` `when(BuildConfig.FLAVOR)` |
| 3 | same file (23–36) | New branch in the `APP_NAME` `when` |
| 4 | same file (40–62) | *Conditional:* if custom subdomain, up to 3 more branches (`API_BASE`, `SAVE_FCM_TOKEN`, `FCM_TOKEN_ENDPOINT`) — the hassnimobile case |
| 5 | `lib/config/app_config.dart` (17–127) | New `case '<id>':` in the giant switch (appName, domain, appDescription, currencySymbol, paymentTerm) |
| 6 | `lib/main.dart` (25–31) | Append `\|\| currentFlavor == '<id>'` to the theme OR-chain |
| 7 | `lib/widgets/imei_input_screen.dart` (21–26) | Append to the identical 5-flavor OR-chain |
| 8 | `lib/widgets/permission_setup_screen.dart` (235–240) | Append to the identical OR-chain |
| 9 | `lib/widgets/main_screen.dart` (102–107) | Append to the identical OR-chain |
| 10 | `lib/features/emi/presentation/emi_info_screen.dart` (126–131) | Append to the identical OR-chain |
| 11 | `android/app/src/<id>/res/values/strings.xml` | **New file** — `app_name` + `accessibility_service_description` |
| 12 | `android/app/src/<id>/google-services.json` | **New file** — copied from a reference flavor, wrong Firebase project until swapped |
| 13–17 | `android/app/src/<id>/res/mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.{png\|jpg\|jpeg}` | **5 new bitmaps**, extension inconsistent across existing flavors |
| 18 | `android/app/src/<id>/res/layout/kiosk_layout.xml` | *Conditional (premium flavors):* copy ~520 lines, hand-edit hardcoded brand text at lines 33 and 504 |

**~11 mandatory edit points** (up to ~14 with a custom domain, kiosk layout, and eplocker-style behavior), and the **flavor id literal is hand-typed in 8+ locations**.

### The drift risks this creates (all silent)

- **Silent fallback on typo — the headline risk.** A misspelled key in `ApiConfig.kt` (`DOMAIN`/`APP_NAME`) or `app_config.dart` hits the `else`/`default:` branch and ships **`myshopmypoint.com`** with no compile error and no runtime error. The wrong-backend build looks fine until devices phone home to the wrong server.
- **Domain duplicated across two languages.** `ApiConfig.kt` `DOMAIN` (Kotlin) and `app_config.dart` `domain` (Dart) must be kept in sync by hand. **Already drifted:** hassnimobile is `admin.skoolx.cloud` in `app_config.dart:82` but bare `skoolx.cloud` in `ApiConfig.kt:19` (Kotlin then special-cases `API_BASE` at line 41 to compensate).
- **App name triplicated.** `ApiConfig.APP_NAME` + `AppConfig.appName` + `res/values/strings.xml` `app_name`. **Already drifted:** myshopmypoint is `"My Shop My Point"` in `ApiConfig.kt:26` vs `"My Device"` in `app_config.dart:41`.
- **5 parallel premium-UI gates.** The literal `fastemi || caapglobal || hassnimobile || nexcops || nexorha` chain is copy-pasted in **6 files** (`main.dart` + the 4 screens above). Miss one and that screen silently drops to the basic theme — a partial, hard-to-spot UI break.
- **google-services.json placeholder misroutes FCM.** Copied from the reference client (e.g. `fastemi-4134f` / `com.fast.emi`); the build succeeds with the wrong Firebase project and nothing flags it.
- **Kiosk brand text baked into XML.** `kiosk_layout.xml:33` (`DEVICE PROTECTED BY <CLIENT>`) and `:504` (`🛡️ <Client> Device Security`) are hardcoded `android:text`, not `@string` refs — stale reference-client branding survives copy/paste.
- **Verification catches none of it.** The current runbook runs only `flutter analyze` + `gradlew assemble<Flavor>Debug --dry-run` — neither validates the domain, that all 5 UI files were updated, that the three app-name sources agree, or that google-services.json belongs to the client.

---

## 2. Target: add a client from parameters

**One declarative registry becomes the only place a client is defined.** Gradle, `ApiConfig.kt`, and `AppConfig` all *derive* from it instead of each holding a hand-maintained copy.

### The registry entry format

`tool/flavors/flavors.yaml` — a `defaults:` block plus one entry per client that lists **only what deviates** from the defaults:

```yaml
defaults:
  applicationId: com.renew.jss          # never changes (single-install DPC)
  apiBase:    "https://{domain}/api2"    # default pattern
  socketBase: "https://socket.{domain}"  # default pattern
  currency:   "₹"
  term:       "EMI"
  usesFastEmiUi: false
  paymentEnable: false
  accessibilityDescription: "{appName} DPC - Required for monitoring app usage and device control"

flavors:
  - id: fastemi                 # ^[a-z][a-z0-9]+$  (valid Gradle flavor AND Dart-map key)
    appName: "FastEMI"
    domain:  fastemi.org
    usesFastEmiUi: true
    primaryColor: "#0D0D0D"
    accentColor:  "#C6F135"

  - id: trini
    appName: "trini"
    domain:  trinimultiventure.com
    currency: "GH₵"             # only region overrides listed
    term:     "Installment"

  - id: hassnimobile            # the ONE custom-subdomain client → declarative override
    appName: "Hassni Mobile Zone"
    domain:  admin.skoolx.cloud
    socketBase: "https://socket.skoolx.cloud"
    currency: "AED"
    usesFastEmiUi: true
```

Fields per the spec: **name** (`appName`), **id**, **domain**, **currency**, **term**, **theme/color** (`usesFastEmiUi` + `primaryColor`/`accentColor`), **payment_enable** (`paymentEnable`), and **reference-flavor** (supplied at generation time, see the command below — it is the flavor whose mipmaps/google-services/kiosk layout are copied as scaffolding).

### How the default pattern removes exceptions

Because `apiBase` defaults to `https://{domain}/api2` and `socketBase` to `https://socket.{domain}`, **a normal flavor lists neither**. Today `ApiConfig.kt` special-cases hassnimobile in **four separate places** (`DOMAIN`, `API_BASE`, `SAVE_FCM_TOKEN`, `FCM_TOKEN_ENDPOINT`). Under the registry, hassnimobile is the **only** entry that carries an explicit `domain`/`socketBase` override — the exception is one declarative line, not four hardcoded code branches, and the current bare-`skoolx.cloud` drift is reconciled at migration into the single correct value.

### How the three surfaces derive from it

A dependency-free Dart codegen step, `tool/flavors/gen_flavors.dart`, reads the YAML, merges defaults, validates, and emits **two checked-in artifacts**:

- `android/flavors.gen.json` — a flat resolved array `{id, appName, domain, apiBase, socketBase, appDescription}`.
- `lib/config/flavor_registry.g.dart` — a `const Map<String, FlavorEntry> kFlavorRegistry`.

**Gradle** — the 11 hand-written `create()` blocks in `build.gradle.kts:56–103` collapse to a loop over the JSON, injecting per-flavor `buildConfigField` **and** `resValue` (so `strings.xml` disappears too):

```kotlin
val flavors = groovy.json.JsonSlurper()
    .parse(rootProject.file("../flavors.gen.json")) as List<Map<String, Any>>
productFlavors {
  flavors.forEach { f -> create(f["id"] as String) {
    dimension = "branding"; applicationId = "com.renew.jss"
    buildConfigField("String", "DOMAIN",      "\"${f["domain"]}\"")
    buildConfigField("String", "APP_NAME",    "\"${f["appName"]}\"")
    buildConfigField("String", "API_BASE",    "\"${f["apiBase"]}\"")
    buildConfigField("String", "SOCKET_BASE", "\"${f["socketBase"]}\"")
    resValue("string", "app_name", f["appName"] as String)
    resValue("string", "accessibility_service_description", f["appDescription"] as String)
  } }
}
```

`JsonSlurper` ships with Gradle — no new plugin or dependency.

**ApiConfig.kt** — all five `when(BuildConfig.FLAVOR)` tables (lines 8–62) are deleted and replaced with four reads. The 10 derived endpoint `val`s (46–53) are untouched because they interpolate `API_BASE`:

```kotlin
val DOMAIN         = BuildConfig.DOMAIN
val APP_NAME       = BuildConfig.APP_NAME
private val API_BASE = BuildConfig.API_BASE
val SAVE_FCM_TOKEN = "${BuildConfig.SOCKET_BASE}/save-fcm-token"
```

(Fix the dead `getApiBaseUrl()` double-`/api2` bug at line 65 while here.)

**AppConfig** — `app_config.dart:17–127`'s 100-line switch becomes a lookup:

```dart
final e = kFlavorRegistry[flavor] ?? kFlavorRegistry['myshopmypoint']!;
appName = e.appName;  domain = e.domain;  /* … */
apiBaseUrl = 'https://${e.domain}/api2';
usesFastEmiUi = e.usesFastEmiUi;
```

The per-flavor `strings.xml` files are deleted (values now come from `resValue`); the `app_name`/`accessibility_service_description` keys are removed from `src/main/res/values/strings.xml` to avoid a duplicate-resource merge error.

### The command a user runs

```bash
dart run tool/flavors/add_flavor.dart \
    --name "Acme Secure"  --id acme  --domain acme.com \
    --currency "$"  --term EMI  --primary-color "#101418" --accent-color "#39FFB0" \
    --fastemi-ui  --payment-enable  --reference fastemi
```

It **validates** (duplicate id? id matches `^[a-z][a-z0-9]+$`? domain well-formed? hex colors valid? does `src/<reference>/` exist?), **appends** the `acme` entry to `flavors.yaml`, **scaffolds** `android/app/src/acme/` (mipmaps + a `google-services.PLACEHOLDER.json` marker + `kiosk_layout.xml` if `--fastemi-ui`, all copied from `fastemi`), **regenerates** both artifacts, and runs `flutter analyze` + `gradlew :app:assembleAcmeDebug --dry-run`.

### The silent-fallback hole is closed

`gen_flavors.dart` **fails the build** if any flavor present in `productFlavors` lacks a registry entry, and validation rejects a malformed id at generation time. A typo can no longer ship `myshopmypoint.com` silently.

**Two human steps remain and are flagged in the generator output** (neither is auto-solvable): replace `google-services.PLACEHOLDER.json` with the client's real Firebase file, and drop in the real `ic_launcher` bitmaps (Firebase configs and logo art cannot be generated).

---

## 3. Killing the scattered conditionals

The registry gives us one boolean, `usesFastEmiUi`, plus typed capability flags (`paymentEnable`, and an `notifyEnrollUpdate`-style flag for the eplocker one-off). The six copy-pasted OR-chains and the scattered `currentFlavor == 'x'` behavior checks all collapse to semantic lookups. Membership lives in **one place** (the YAML) instead of six files.

**Before** — the same literal chain in `main.dart`, `imei_input_screen.dart`, `permission_setup_screen.dart`, `main_screen.dart`, `emi_info_screen.dart`:

```dart
// main.dart:25–31
theme: (AppConfig.currentFlavor == 'fastemi' ||
        AppConfig.currentFlavor == 'caapglobal' ||
        AppConfig.currentFlavor == 'hassnimobile' ||
        AppConfig.currentFlavor == 'nexcops' ||
        AppConfig.currentFlavor == 'nexorha')
    ? FastEmiTheme.themeData
    : ThemeData(primarySwatch: Colors.blue, fontFamily: 'Roboto'),

// each of the 4 screens
if (AppConfig.currentFlavor == 'fastemi' || /* …4 more… */) return _buildFastEmiUI(context);

// main.dart:187  — behavior branch
if (AppConfig.currentFlavor == 'eplocker') { /* isEnrollUpdate call */ }
```

**After:**

```dart
// main.dart
theme: AppConfig.usesFastEmiUi ? FastEmiTheme.themeData
                               : ThemeData(primarySwatch: Colors.blue, fontFamily: 'Roboto'),

// same single line in all 4 screens
if (AppConfig.usesFastEmiUi) return _buildFastEmiUI(context);

// main.dart behavior branch — driven by a registry flag, not a name literal
if (AppConfig.brand.notifyEnrollUpdate) { /* isEnrollUpdate call */ }
```

Now it is **impossible** to update 5 of 6 sites and silently break the 6th. Flavor-specific *behavior* (payment, enroll-notify) is visible as data in one YAML row rather than accreting as string-equality checks across general code.

*Scope note:* the ~150 static `FastEmiTheme.lime/.bgDark` references inside the screen bodies still render lime/dark regardless of `primaryColor`. Carrying `primaryColor`/`accentColor` in the registry and turning `FastEmiTheme` into a `themeData(primary, accent)` factory is wired as far as the theme object; converting those 150 body references to `Theme.of(context)` so per-client recolor actually renders is a **follow-up**, not part of this change (it is a pure refactor with no behavior change and can land later).

---

## 4. What else to do (no backend)

Client/build-side hardening only — nothing here touches a server. Ranked by impact-per-unit-effort.

| Priority | Item | What / where | Effort | Impact |
|---|---|---|---|---|
| **P0** | Extract signing secrets | `build.gradle.kts:33–36` hardcodes keystore + key passwords in plaintext. Read from a git-ignored `keystore.properties` or `System.getenv(...)`. The `Properties` import at lines 1–2 already exists, unused. | Low | Critical |
| **P0** | Rotate the leaked key | `test-dpc.jks` (password in history) signs **all 11 brands** via the single shared release config. Generate a new keystore; treat the old one as burned. *Confirm the distribution model first — see caveat below.* | Low | Critical |
| **P0** | Untrack committed secrets | `.gitignore` lists `*.jks`/`key.properties`/`*.log` but files committed earlier are still tracked. `git rm --cached` the keystore, every `src/*/google-services.json`, and `android/.kotlin/errors`. Add `**/google-services.json`, `**/*firebase-adminsdk*.json`, `android/.kotlin/`. Verify: `git ls-files \| grep -Ei 'jks\|google-services\|adminsdk\|\.log$'` returns empty. | Low | High |
| **P1** | Kill global cleartext | `AndroidManifest.xml:50` sets `usesCleartextTraffic="true"`. Add `res/xml/network_security_config.xml` with `cleartextTrafficPermitted="false"`, reference it, remove the flag. All domains are already https → runtime no-op, closes MITM exposure. | Low | High |
| **P1** | CI matrix over 11 flavors | `.github/workflows/build.yml`: `flutter analyze --fatal-warnings` + `flutter test` + `assemble<Flavor>Debug` for each flavor. The **only** automated guard against a flavor added to Gradle but missed elsewhere. Provide per-flavor google-services from CI secrets. | Medium | High |
| **P1** | CI-driven versioning | `versionCode` is hardcoded `3` (`build.gradle.kts:52`) for all 11 flavors → upload collisions. Drive from `System.getenv("BUILD_NUMBER")`; add per-flavor `versionNameSuffix` for provenance. Keep the single `applicationId` (intentional for an enrollment-based DPC); document that in CLAUDE.md. | Low–Med | Med |
| **P2** | Tests at the dangerous seams | Unit-test `PolicyChangeProcessor.kt` (policy payload → dispatched actions) and the lock-state store(s); widget-test the imei→permission→main flow with a mocked `getFlavor` channel. Wire into the CI gate. | Medium | High |
| **P2** | Single lock-state source of truth | Two overlapping lock-state stores risk split-brain (locked in one, unlocked in the other). Collapse to one `LockStateRepository`; migrate persisted `SharedPreferences` state so enrolled devices don't reset. | Medium | High |
| **P2** | Service/watchdog consolidation | `PolicyMonitoringService`, `PolicyCheckWorker`, `FcmHeartbeatWorker`, `GpsFetchService` + boot/relaunch overlap. Consolidate periodic paths onto WorkManager (already a dep); keep foreground services only where a persistent notification is required. Fewer wakelocks, fewer Android 12+ background-start denials. **Do this only after the test net exists.** | Medium | Med |
| **P2** | Crashlytics (client-side) | Firebase is already wired per flavor. Add `firebase-crashlytics`; a crash on a locked enrolled device is currently invisible. | Low | Med |
| **P3** | Kiosk layout deduplication | 5 flavors carry near-identical ~520-line `kiosk_layout.xml` differing in 3 values. Collapse to the single `main` layout with `@string`/`@color` placeholders fed per flavor. | Med | Med |
| **P3** | Dependency currency | okhttp 4.11.0, firebase-messaging 23.4.1, work-runtime 2.9.0, firebase-bom 32.7.4 are behind. Bump, pin via the BOM, run the new CI matrix; add Dependabot. | Low | Low |
| **P3** | Rename `DeviceOwnerReceiver` | Per MEMORY this DPC is **Device Admin, not Device Owner** — the `DeviceAdminReceiver` subclass named `DeviceOwnerReceiver` actively misleads. Rename the class/file + `<receiver>` in the manifest + ~21 refs. Safe once CI exists. | Low | Low |
| **P3** | Normalize launcher icons | Icons are `.png`/`.jpg`/`.jpeg` across flavors. Standardize on `.png`. | Low | Low |

**Caveats the owner must decide before P0 rotation / P1 versioning:** rotating the signing key breaks in-place updates for any brand distributed via Play with signature-stable expectations — confirm distribution is MDM/enrollment-based first. If any brand must become a separate Play listing, giving flavors distinct `applicationId`s forces every google-services.json to re-register the new package (real per-flavor toil) — keep the shared id unless a listing split is actually required.

---

## 5. Phased rollout

Ordered so **current builds never break**, each phase independently verifiable and revertable. Verification ladder per phase: **(a)** `flutter analyze`, **(b)** `gradlew assemble<Flavor>Debug --dry-run` across all 11, **(c)** one **real** build (`gradlew assembleFastemiDebug` — a premium flavor exercising the FastEmi path — and one classic flavor such as `assembleAilockerDebug`), installed and smoke-tested (correct app name, correct domain in a network call, correct theme).

**Phase 0 — Secrets & safety net (no behavior change).**
Extract signing secrets (P0), untrack committed secrets + tighten `.gitignore` (P0), add `network_security_config.xml` (P1), stand up the CI matrix (P1). Rotate the key only after the distribution model is confirmed.
*Verify:* `git ls-files \| grep -Ei 'jks\|google-services\|adminsdk'` empty; CI green on all 11 `assemble*Debug`; one real release-signed build succeeds with secrets sourced from env.

**Phase 1 — Introduce the registry as the source, keep both sides byte-identical.**
Author `tool/flavors/flavors.yaml` by transcribing the 11 flavors from `ApiConfig.kt:8–36` and `app_config.dart:24–111`, **reconciling the two known drifts** (hassnimobile domain → `admin.skoolx.cloud`/`socket.skoolx.cloud`; myshopmypoint appName). Write `gen_flavors.dart`; commit `flavors.gen.json` + `flavor_registry.g.dart`. Do **not** yet delete any `when`/`switch` — only add the artifacts and a test asserting each generated `FlavorEntry` equals the current hardcoded value.
*Verify:* value-preservation test passes for all 11; `flutter analyze` clean; artifacts committed and `git diff --exit-code` clean after a re-gen.

**Phase 2 — Switch Dart to the registry.**
Rewrite `app_config.dart` `initialize()` to the registry lookup; collapse the six OR-chains (`main.dart` + 4 screens) to `AppConfig.usesFastEmiUi`; drive the eplocker branch from a registry flag.
*Verify:* (a) analyze, then a real build of one premium + one classic flavor — confirm theme selection and app name are visually identical to pre-change; diff screenshots if possible.

**Phase 3 — Switch Gradle + Kotlin to the registry.**
Replace the 11 `create()` blocks in `build.gradle.kts` with the `JsonSlurper` loop emitting `buildConfigField` + `resValue`; gut the five `when` tables in `ApiConfig.kt` to the four `BuildConfig` reads; fix the `getApiBaseUrl()` double-`/api2` bug. **In the same commit**, delete every `src/<flavor>/res/values/strings.xml` and remove `app_name`/`accessibility_service_description` from `src/main/res/values/strings.xml` (duplicate-resource merge failure otherwise). Register `generateFlavors` as a `preBuild` dependency; add the CI `git diff --exit-code` gate on the generated artifacts.
*Verify:* (b) `--dry-run` all 11; (c) real build of hassnimobile (the custom-subdomain exception) + one default-pattern flavor — confirm the resolved domain/socket URLs in a live network call match the previous build exactly.

**Phase 4 — The generator CLI + gate.**
Ship `add_flavor.dart` with shared validation (duplicate id, id/domain/hex regex, `--reference` existence, placeholder-google-services warning) and the build-fails-on-missing-registry-entry check. Add `tool/flavors/README.md` and update MEMORY: *flavors.yaml is the only edit point*.
*Verify:* run the generator end-to-end on a throwaway `--id demo` flavor → analyze + `assembleDemoDebug --dry-run` pass; then remove the demo entry and confirm artifacts regenerate clean.

**Phase 5 — Correctness & hygiene (each behind CI + its own PR).**
In order: tests for `PolicyChangeProcessor` + lock state (P2) → lock-state single source of truth with SharedPreferences migration (P2) → service/watchdog consolidation (P2, only now that tests exist) → Crashlytics (P2) → kiosk layout dedup (P3) → dependency bumps + `DeviceOwnerReceiver` rename + icon normalization (P3).
*Verify per PR:* CI matrix green; for lock-state and service changes, a real on-device lock/unlock cycle on both a premium and a classic flavor before merge.

---

**Net effect:** adding a client goes from ~16 files / 8+ hand-typed id literals / 3 languages with silent-fallback drift, to **one `flavors.yaml` entry (or one generator command)** with generation-time validation — the only irreducibly manual steps being the client's real Firebase file and logo bitmaps, both explicitly flagged. The hardening track runs in parallel and is gated by the CI net stood up in Phase 0.