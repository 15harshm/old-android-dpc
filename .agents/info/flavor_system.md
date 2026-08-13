# Flavor System & Hardening — Architecture Reference

A durable reference for how white-label clients ("flavors") are defined, how the
build derives every branded surface from a single registry, and the security/
build hardening that ships alongside it.

This DPC is **multi-tenant**: 11 flavors (`ailocker`, `eplocker`, `myshopmypoint`,
`keygen`, `trini`, `fastemi`, `nexorha`, `caapglobal`, `nexcops`, `emipay`,
`hassnimobile`) share one codebase and one `applicationId` (`com.renew.jss`).
Flavors are **not** distinguished by package name — they are enrollment-based
installs distinguished by backend domain, branding, and Firebase project.

> Reminder (see [`project_context.md`](project_context.md)): this app is a
> **Device Admin, not a Device Owner**. The flavor system is orthogonal to that,
> but it is why there is one shared `applicationId` rather than per-flavor packages.

---

## 1. The OLD pain (what this replaced)

Adding **one** client was a fully manual, cross-language edit with **no single
source of truth and no validation**. The same three facts — the **flavor id
string**, the **domain**, and the **app name** — were re-typed by hand into
parallel maps in three languages (Kotlin, Dart, Gradle Kotlin-DSL) plus Android
XML resources.

**~16 files touched per client** (8 edited + ~8 new/copied), across ~4 new
directories:

| Surface | File(s) | Edit |
|---|---|---|
| Gradle | `android/app/build.gradle.kts` | New `create("<id>") { … }` product-flavor block |
| Kotlin | `ApiConfig.kt` | New branch in the `DOMAIN` `when`, the `APP_NAME` `when`, and (for a custom subdomain) up to 3 more branches (`API_BASE`, `SAVE_FCM_TOKEN`, `FCM_TOKEN_ENDPOINT`) |
| Dart | `lib/config/app_config.dart` | New `case '<id>':` in a ~100-line switch (appName, domain, description, currency, term) |
| Dart UI | `lib/main.dart` + `imei_input_screen.dart` + `permission_setup_screen.dart` + `main_screen.dart` + `emi_info_screen.dart` | Append `|| currentFlavor == '<id>'` to **5 identical** premium-UI OR-chains |
| Android res | `android/app/src/<id>/res/values/strings.xml` | New file (`app_name` + `accessibility_service_description`) |
| Firebase | `android/app/src/<id>/google-services.json` | New file, copied from a reference flavor (wrong Firebase project until swapped) |
| Icons | `android/app/src/<id>/res/mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.*` | 5 new bitmaps, inconsistent extension across flavors |
| Kiosk | `android/app/src/<id>/res/layout/kiosk_layout.xml` | (premium flavors) ~520 lines copied with hardcoded brand text |

The flavor id literal was hand-typed in **8+ locations**. Every drift it caused
was **silent**:

- **Silent fallback on a typo — the headline risk.** A misspelled key in
  `ApiConfig.kt` or `app_config.dart` hit the `else`/`default:` branch and
  shipped **`myshopmypoint.com`** with no compile error and no runtime error —
  a wrong-backend build that looked fine until devices phoned home to the wrong
  server.
- **Domain duplicated across two languages.** Kotlin `DOMAIN` and Dart `domain`
  had to be hand-synced. **Real drift that existed:** `hassnimobile` was
  `admin.skoolx.cloud` in Dart but bare `skoolx.cloud` in Kotlin (which then
  special-cased `API_BASE` to compensate).
- **App name triplicated** across `ApiConfig.APP_NAME`, `AppConfig.appName`, and
  `strings.xml`. **Real drift:** `myshopmypoint` was `"My Shop My Point"` in
  Kotlin vs `"My Device"` in Dart.
- **5 parallel premium-UI gates.** The literal
  `fastemi || caapglobal || hassnimobile || nexcops || nexorha` chain was
  copy-pasted in 6 files. Miss one and that one screen silently dropped to the
  basic theme.
- **google-services.json placeholder misrouted FCM** — the build succeeded with
  the reference client's Firebase project and nothing flagged it.
- **Verification caught none of it** — the runbook ran only `flutter analyze` +
  a Gradle dry-run.

---

## 2. The NEW architecture — one registry, everything derives

**`tool/flavors/flavors.yaml` is the single source of truth.** Gradle,
`ApiConfig.kt`, and `AppConfig` all *derive* from it instead of each holding a
hand-maintained copy.

### 2.1 The registry (`tool/flavors/flavors.yaml`)

A `defaults:` block plus one entry per client listing **only what deviates**:

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

Because `apiBase` defaults to `https://{domain}/api2` and `socketBase` to
`https://socket.{domain}`, a **normal flavor lists neither**. `hassnimobile` is
the **only** entry carrying an explicit `socketBase` override — the special case
that used to be **four** hardcoded Kotlin branches is now **one declarative
line**.

### 2.2 The generator (`tool/flavors/gen_flavors.dart`)

A dependency-free Dart codegen step reads the YAML, merges defaults, **validates**
(id regex, domain, hex colors, no duplicates, every `productFlavors` entry has a
registry entry), and emits **two checked-in artifacts**:

- **`android/flavors.gen.json`** — a flat resolved array of
  `{id, appName, domain, apiBase, socketBase, appDescription}`, consumed by
  Gradle.
- **`lib/config/flavor_registry.g.dart`** — a `const Map<String, FlavorEntry>
  kFlavorRegistry`, consumed by `app_config.dart`.

Both artifacts are **committed** and **CI enforces they are fresh** (see §3).

**The silent-fallback hole is closed:** the generator fails if any flavor in
`productFlavors` lacks a registry entry, and validation rejects a malformed id at
generation time. A typo can no longer ship `myshopmypoint.com` silently.

### 2.3 How the three surfaces consume the artifacts

**Gradle** — the 11 hand-written `create()` blocks collapse to a loop over the
JSON (via `JsonSlurper`, which ships with Gradle — no new dependency), injecting
per-flavor `buildConfigField` **and** `resValue` (so `strings.xml` disappears
too):

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

**Kotlin (`ApiConfig.kt`)** — all five `when(BuildConfig.FLAVOR)` tables are
deleted and replaced with reads of the generated `BuildConfig` fields:

```kotlin
val DOMAIN         = BuildConfig.DOMAIN
val APP_NAME       = BuildConfig.APP_NAME
private val API_BASE = BuildConfig.API_BASE
val SAVE_FCM_TOKEN = "${BuildConfig.SOCKET_BASE}/save-fcm-token"
```

**Dart (`AppConfig`)** — the ~100-line switch becomes a lookup into the generated
registry:

```dart
final e = kFlavorRegistry[flavor] ?? kFlavorRegistry['myshopmypoint']!;
appName = e.appName;  domain = e.domain;  /* … */
apiBaseUrl = 'https://${e.domain}/api2';
usesFastEmiUi = e.usesFastEmiUi;
```

The per-flavor `strings.xml` files are deleted (values now come from `resValue`),
and `app_name`/`accessibility_service_description` are removed from
`src/main/res/values/strings.xml` to avoid a duplicate-resource merge error.

### 2.4 Scattered conditionals collapse to one flag

The **5 copy-pasted premium-UI OR-chains** (`main.dart` + 4 screens) and the
scattered `currentFlavor == 'x'` behavior checks all become a single semantic
lookup. Membership lives in **one place** (the YAML) instead of six files:

```dart
// Before (repeated in 6 files):
if (AppConfig.currentFlavor == 'fastemi' || /* …4 more… */) return _buildFastEmiUI(context);

// After (same single line everywhere):
if (AppConfig.usesFastEmiUi) return _buildFastEmiUI(context);
```

Flavor-specific *behavior* (e.g. the eplocker enroll-update branch, payment)
becomes a typed registry flag, not a name-equality check scattered across
general code. It is now **impossible** to update 5 of 6 sites and silently break
the 6th.

---

## 3. Hardening changes (client/build-side only — no backend)

These ship alongside the registry and are gated by the CI net.

| Area | Change |
|---|---|
| **Signing secrets** | `build.gradle.kts` no longer hardcodes the keystore/key passwords. They are read from a git-ignored `android/app/keystore.properties` (or `System.getenv(...)`). See §3.1. |
| **Network security** | `android/app/src/main/res/xml/network_security_config.xml` sets `base-config cleartextTrafficPermitted="false"`; the manifest `<application>` references it via `android:networkSecurityConfig` and the blanket `android:usesCleartextTraffic="true"` is removed. All backend hosts are HTTPS, so this is a runtime no-op that closes MITM exposure. |
| **Versioning** | `versionCode` is driven from CI (`System.getenv("BUILD_NUMBER")`) instead of a hardcoded `3` shared by all 11 flavors, preventing upload collisions. The single `applicationId` is intentional for an enrollment-based DPC. |
| **.gitignore** | Adds `android/app/keystore.properties`, `**/google-services.json`, `**/*firebase-adminsdk*.json`, `android/.kotlin/` (`*.jks` and `*.log` were already ignored). |
| **CI matrix** | `.github/workflows/build.yml` regenerates the artifacts, fails on stale codegen (`git diff --exit-code`), runs `flutter analyze`, and assembles all 11 flavors. |

### 3.1 Untracking already-committed secrets

`.gitignore` prevents **future** commits, but files committed **before** the
ignore rules stay tracked. The owner must untrack them once (run these from the
repo root, sequentially — no other agent editing):

```bash
# The shared release keystore (password was in history — treat as burned/rotate).
git rm --cached android/app/test-dpc.jks

# Every per-flavor Firebase config (11 flavors).
git rm --cached android/app/src/*/google-services.json

# Firebase Admin SDK service-account key(s), if any are tracked (server-side creds).
git rm --cached **/*firebase-adminsdk*.json    # e.g. hmdm-2e5f2-firebase-adminsdk-*.json

# Kotlin incremental-compile error logs (build noise).
git rm --cached -r android/.kotlin

# Then commit the removals.
git commit -m "chore: untrack committed secrets and build noise"
```

**Verification — this must return empty:**

```bash
git ls-files | grep -Ei 'jks|google-services|adminsdk|\.log$'
```

> `git rm --cached` removes the files from the index but **keeps them on disk**,
> so local/CI builds still work. The keystore password lives in git history —
> generate a new keystore and treat the old one as compromised (confirm the
> distribution model is MDM/enrollment-based first; rotating breaks in-place Play
> updates for signature-stable listings).

---

## 4. Regenerating after editing the registry

Any time you edit `tool/flavors/flavors.yaml` (by hand or via the generator),
regenerate the two committed artifacts and commit them:

```bash
flutter pub get && dart run tool/flavors/gen_flavors.dart
```

Then commit `android/flavors.gen.json` and `lib/config/flavor_registry.g.dart`
alongside the YAML change. **CI fails (`git diff --exit-code`) if you forget** —
the committed artifacts must match a fresh regeneration.

To onboard a new client, prefer the generator CLI — see
[`../workflows/add_new_client.md`](../workflows/add_new_client.md).

---

## 5. Architecture diagram

```
                     ┌─────────────────────────────────────┐
                     │   tool/flavors/flavors.yaml          │   ← SINGLE SOURCE OF TRUTH
                     │   defaults: + one entry per client   │     (hand-edit or via add_flavor.dart)
                     └───────────────────┬─────────────────┘
                                         │
                        dart run tool/flavors/gen_flavors.dart
                        (merge defaults · validate · fail on drift)
                                         │
                 ┌───────────────────────┴───────────────────────┐
                 ▼                                                ▼
   ┌─────────────────────────────┐                 ┌──────────────────────────────────┐
   │ android/flavors.gen.json    │  (committed)    │ lib/config/flavor_registry.g.dart │  (committed)
   │ [{id,appName,domain,        │                 │ const Map<String,FlavorEntry>     │
   │   apiBase,socketBase,       │                 │   kFlavorRegistry                 │
   │   appDescription}, …]       │                 └──────────────────┬───────────────┘
   └──────────────┬──────────────┘                                    │
                  │ JsonSlurper loop                                  │ lookup
                  ▼                                                   ▼
   ┌──────────────────────────────┐                    ┌──────────────────────────────┐
   │ build.gradle.kts             │                    │ lib/config/app_config.dart   │
   │  productFlavors { create() } │                    │  kFlavorRegistry[flavor]      │
   │  buildConfigField(...)  ─────┼──► BuildConfig ──► │                               │
   │  resValue("app_name", …)     │    fields          │  usesFastEmiUi, appName, …    │
   └──────────────┬───────────────┘        │           └───────────────┬──────────────┘
                  │                         ▼                           │
                  │            ┌──────────────────────────┐            ▼
                  │            │ ApiConfig.kt             │   main.dart + 4 screens use
                  │            │  BuildConfig.DOMAIN, …   │   AppConfig.usesFastEmiUi
                  ▼            └──────────────────────────┘   (no more OR-chains)
        resValue → app_name / accessibility_service_description
        (per-flavor strings.xml deleted)

   ┌─────────────────────────────────────────────────────────────────────────────┐
   │ CI (.github/workflows/build.yml):                                            │
   │   verify → re-run gen_flavors.dart → git diff --exit-code → flutter analyze  │
   │   build  → matrix over all 11 flavors → ./gradlew :app:assemble<Flavor>Debug │
   │            (google-services.json injected from GOOGLE_SERVICES_<flavor> secret)│
   └─────────────────────────────────────────────────────────────────────────────┘
```

**Net effect:** adding a client goes from ~16 files / 8+ hand-typed id literals /
3 languages with silent-fallback drift, to **one `flavors.yaml` entry (or one
generator command)** with generation-time validation. The only irreducibly
manual steps are the client's real Firebase file and logo bitmaps — both
explicitly flagged (see [`../workflows/add_new_client.md`](../workflows/add_new_client.md)).
