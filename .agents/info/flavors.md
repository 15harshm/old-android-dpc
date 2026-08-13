# DPC Flavors & Branding Matrix

The Running DPC is **multi-tenant**: 14 white-label clients ("flavors") share one codebase and one
`applicationId` (`com.renew.jss`), distinguished only by **backend domain**, **branding**, and
**Firebase project** — never by package name (they are enrollment-based, single-install).

- **Architecture / how it works:** [`flavor_system.md`](flavor_system.md)
- **Add or edit a client:** [`../workflows/add_new_client.md`](../workflows/add_new_client.md)
- **Single source of truth:** [`../../tool/flavors/flavors.yaml`](../../tool/flavors/flavors.yaml)

> ⚠️ **Do not hand-edit flavor data** in `build.gradle.kts`, `ApiConfig.kt`, or `app_config.dart`.
> Those are generated/derived. Edit `flavors.yaml` and run
> `flutter pub get && dart run tool/flavors/gen_flavors.dart`.

---

## 📋 The 14 flavors

Domains follow the default pattern unless noted: REST API = `https://<domain>/api2`, push/socket =
`https://socket.<domain>`. "FastEmi UI" = the premium theme (`usesFastEmiUi: true`).

| Flavor id | App name (launcher) | Domain | Currency | Term | FastEmi UI | Notes |
|---|---|---|---|---|:---:|---|
| `ailocker` | AI LOCKER | ailocker.org | ₹ | EMI | — | |
| `eplocker` | EMI PRO LOCKER | eplocker.com | ₹ | EMI | — | `notifyEnrollUpdate` (calls isEnrollUpdate on setup) |
| `myshopmypoint` | My Device | myshopmypoint.com | ₹ | EMI | — | the fallback flavor (registry default) |
| `keygen` | KeyGen | emikeygen.com | ₹ | EMI | — | accessibility desc currently inherits generic text* |
| `trini` | triniMV | trinimultiventure.com | GH₵ | Installment | — | Ghana |
| `fastemi` | FastEMI | fastemi.org | ₹ | EMI | ✅ | |
| `nexorha` | Nexorha Secure Lock | nexorha.xyz | ₹ | EMI | ✅ | |
| `caapglobal` | caapglobal | caapglobal.com | ₹ | EMI | ✅ | |
| `nexcops` | Nexcops | nexcops.com | ₹ | EMI | ✅ | |
| `emipay` | EMI PAY | emipay24.com | ₹ | EMI | — | |
| `hassnimobile` | Hassni Mobile Zone | **admin.skoolx.cloud** | AED | EMI | ✅ | **custom subdomain** — `socketBase: https://socket.skoolx.cloud` (not `socket.admin.…`) |
| `ailocker2` | AI LOCKER PRO | ailocker.online | ₹ | EMI | — | |
| `paykist` | Paykist | paykist.com | ₹ | EMI | ✅ | |
| `rocketsolution` | Rocket Solution | rocketsolution.org | ₹ | EMI | — | |

\* `keygen` and `emipay` inherit the generic "My Shop My Point DPC …" accessibility description
because their original `strings.xml` never set one — behavior preserved at migration. Branding them
is a 1–2 line edit in `flavors.yaml` (`accessibilityDescription:`) + regenerate.

---

## 🔧 What each flavor field controls

Defined per client in `flavors.yaml` (only deviations from `defaults:` are listed):

| Field | Consumed by | Effect |
|---|---|---|
| `appName` | Gradle `resValue` → launcher label; `AppConfig.appName` | The user-visible brand name |
| `domain` | derives `apiBase`/`socketBase` | Backend host |
| `apiBase` (override) | `BuildConfig.API_BASE` → `ApiConfig`; `AppConfig.apiBaseUrl` | REST base; defaults to `https://{domain}/api2` |
| `socketBase` (override) | `BuildConfig.SOCKET_BASE` → `ApiConfig.SAVE_FCM_TOKEN` | Push/FCM-token host; defaults to `https://socket.{domain}` |
| `currency` / `term` | `AppConfig.currencySymbol` / `paymentTerm` | EMI amount formatting & wording |
| `usesFastEmiUi` | `AppConfig.usesFastEmiUi` | Premium theme + premium screen layouts (replaces the old 5-file OR-chains) |
| `paymentEnable` | `AppConfig` | In-app payment capability toggle |
| `notifyEnrollUpdate` | `AppConfig.notifyEnrollUpdate` | Calls `isEnrollUpdate` on setup (was the eplocker name-check) |
| `appDescription` / `accessibilityDescription` | `AppConfig` / Gradle `resValue` | In-app text / Accessibility-settings description |

Per-flavor **assets** still live under `android/app/src/<id>/`: `google-services.json` (Firebase,
required, gitignored) and `res/mipmap-*/ic_launcher.png` (logo). Per-flavor `strings.xml` no longer
exists — `app_name` and `accessibility_service_description` are generated via Gradle `resValue`.

---

> **Note:** All flavors intentionally share `applicationId` `com.renew.jss` (enrollment-based DPC,
> one install per device). Splitting into per-flavor package names would force every flavor's
> `google-services.json` to re-register a new package and change the Play listing model — only do it
> if separate Play Store listings are actually required.
