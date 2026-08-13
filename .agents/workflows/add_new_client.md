# Adding a New Client (Flavor)

Step-by-step guide (for operators **and** agents) for onboarding a new white-label
client to the Running DPC. For the architecture behind this, see
[`../info/flavor_system.md`](../info/flavor_system.md). For project background, see
[`../info/project_context.md`](../info/project_context.md).

The whole point of the flavor system: **you define a client once**, in
`tool/flavors/flavors.yaml`, and Gradle, Kotlin (`ApiConfig.kt`), and Dart
(`AppConfig`) all derive from it. The generator below writes that entry for you,
scaffolds the Android resource directory, and regenerates the committed
artifacts. Two things it **cannot** do (Firebase config + logo art) are called
out explicitly.

---

## 1. Prerequisites

- Flutter SDK installed and on `PATH` (`flutter --version` works).
- Dependencies fetched at least once: `flutter pub get`.
- Working from the **repo root**, on a clean branch (the generator edits
  `flavors.yaml` and rewrites the generated artifacts — commit or stash unrelated
  changes first).
- The client's facts in hand: display name, a short lowercase **id**, backend
  **domain**, currency symbol, payment term wording, and brand colors.
- The client's **Firebase project** created (you'll need its `google-services.json`
  for the manual step in §4) and their **logo** at 5 densities.
- An existing flavor to use as the **`--reference`** (its mipmaps, kiosk layout,
  and google-services placeholder are copied as scaffolding). Use a premium
  reference like `fastemi` when the new client should use the FastEmi UI.

---

## 2. The command

```bash
dart run tool/flavors/add_flavor.dart \
    --name "Acme Secure" \
    --id acme \
    --domain acme.com \
    --currency "$" \
    --term EMI \
    --primary-color "#101418" \
    --accent-color "#39FFB0" \
    --fastemi-ui \
    --payment-enable \
    --reference fastemi
```

### Parameters

| Flag | Required | Maps to (`flavors.yaml`) | Rules / notes |
|---|---|---|---|
| `--name` | yes | `appName` | Display name shown as the launcher label and in-app. Free text; quote if it contains spaces. |
| `--id` | yes | entry `id` | **Must match `^[a-z][a-z0-9]+$`** — starts with a lowercase letter, then lowercase letters/digits only (no spaces, dashes, underscores, or uppercase). This is simultaneously the Gradle product-flavor name **and** the Dart registry map key, so it must be a valid identifier in both. Must be **unique**. |
| `--domain` | yes | `domain` | The client's backend host, e.g. `acme.com`. By default the app derives `apiBase = https://{domain}/api2` and `socketBase = https://socket.{domain}` from it, so a normal client lists nothing else. |
| `--currency` | yes | `currency` | Currency symbol shown in EMI amounts, e.g. `"$"`, `"₹"`, `"GH₵"`, `"AED"`. |
| `--term` | yes | `term` | Payment-term wording, e.g. `EMI` or `Installment`. |
| `--primary-color` | yes | `primaryColor` | Brand primary color as `#RRGGBB` hex. |
| `--accent-color` | yes | `accentColor` | Brand accent color as `#RRGGBB` hex. |
| `--fastemi-ui` | no (flag) | `usesFastEmiUi: true` | Toggles the premium FastEmi UI/theme for this client. Omit for the basic (blue) theme. Drives `AppConfig.usesFastEmiUi` everywhere — no OR-chain edits. |
| `--payment-enable` | no (flag) | `paymentEnable: true` | Enables the in-app payment capability for this client. Omit to leave it off. |
| `--reference` | yes | (generation-time only) | An **existing** flavor id whose `res/mipmap-*` icons, `res/layout/kiosk_layout.xml`, and `google-services` placeholder are copied into the new `src/<id>/` as scaffolding. Not stored in the registry. Pick a reference that already has the UI style you want (e.g. `fastemi` for a premium client). |

> Colors are carried in the registry and wired into the theme object. Note the
> follow-up caveat from the plan: ~150 static `FastEmiTheme` color references in
> screen bodies still render the default lime/dark until they're migrated to
> `Theme.of(context)`. Setting `primaryColor`/`accentColor` is correct and
> future-proof, but per-client **recolor** of those bodies is a separate refactor.

---

## 3. What the generator does automatically

1. **Validates** everything before writing: `--id` matches `^[a-z][a-z0-9]+$` and
   is not a duplicate; `--domain` is well-formed; `--primary-color`/`--accent-color`
   are valid hex; and `src/<reference>/` actually exists.
2. **Appends** the new entry to `tool/flavors/flavors.yaml` (only the fields that
   deviate from `defaults:`).
3. **Scaffolds** `android/app/src/<id>/` by copying from `--reference`: the five
   `res/mipmap-*/ic_launcher.*` icons, `res/layout/kiosk_layout.xml` (when
   `--fastemi-ui`), and a `google-services.PLACEHOLDER.json` marker (**not** a real
   Firebase config).
4. **Regenerates** the two committed artifacts — `android/flavors.gen.json` and
   `lib/config/flavor_registry.g.dart` — so Gradle/Kotlin/Dart immediately see the
   new flavor.
5. **Runs** `flutter analyze` and a Gradle dry-run
   (`gradlew :app:assemble<Id>Debug --dry-run`) as a smoke check, and prints the
   two manual steps below.

Because the id is validated at generation time and the generator fails if a
`productFlavors` entry lacks a registry entry, the old **silent fallback to
`myshopmypoint.com`** on a typo can no longer happen.

---

## 4. The TWO mandatory manual steps (the generator canNOT do these)

Neither is auto-solvable — they need artifacts only the client can provide:

1. **Drop in the real `google-services.json`.** Replace the scaffolded
   placeholder with the client's actual file from **their** Firebase project:

   ```
   android/app/src/<id>/google-services.json
   ```

   Download it from Firebase console → Project settings → your Android app
   (package `com.renew.jss`). Using the placeholder ships FCM push to the **wrong
   Firebase project** and the build gives no warning. This file is **gitignored**
   — provide it to CI as the `GOOGLE_SERVICES_<id>` secret (base64-encoded).

2. **Replace the placeholder launcher icons** with the client's real logo at all
   five densities:

   ```
   android/app/src/<id>/res/mipmap-hdpi/ic_launcher.png
   android/app/src/<id>/res/mipmap-mdpi/ic_launcher.png
   android/app/src/<id>/res/mipmap-xhdpi/ic_launcher.png
   android/app/src/<id>/res/mipmap-xxhdpi/ic_launcher.png
   android/app/src/<id>/res/mipmap-xxxhdpi/ic_launcher.png
   ```

   Until you do, the app ships with the **reference client's logo**. Standardize
   on `.png`.

---

## 5. Build & verify

Assemble a real signed release APK for the new flavor:

```bash
flutter build apk --flavor <id> --release
```

Confirm on-device (or from the build output / a network capture):

- Correct **app name** on the launcher icon.
- Correct **domain** in an outbound API call (`https://<domain>/api2/...`).
- Correct **theme** (premium FastEmi UI if `--fastemi-ui`, else the basic theme).
- Correct **Firebase project** — a test push actually reaches the device (this is
  what verifies you replaced the placeholder `google-services.json`).

CI (`.github/workflows/build.yml`) additionally assembles every flavor on each
push/PR and fails if the committed generated artifacts are stale.

---

## 6. Manual fallback (edit the YAML by hand)

If you don't want to run the generator, you can add the entry directly and then
regenerate:

1. Add an entry under `flavors:` in `tool/flavors/flavors.yaml`, listing only
   what deviates from `defaults:`:

   ```yaml
     - id: acme
       appName: "Acme Secure"
       domain:  acme.com
       currency: "$"
       usesFastEmiUi: true
       primaryColor: "#101418"
       accentColor:  "#39FFB0"
       paymentEnable: true
   ```

2. Regenerate the committed artifacts:

   ```bash
   flutter pub get && dart run tool/flavors/gen_flavors.dart
   ```

3. Manually scaffold `android/app/src/acme/` (copy mipmaps + kiosk layout from a
   reference flavor) and complete the two manual steps in §4.

### Special case — a custom subdomain (like `hassnimobile`)

Most clients need only `domain` because `apiBase`/`socketBase` are derived from
it. A client whose socket/push host does **not** follow `socket.{domain}` lists an
explicit `socketBase` override — one declarative line, no code branches:

```yaml
  - id: hassnimobile
    appName: "Hassni Mobile Zone"
    domain:  admin.skoolx.cloud            # API host  → https://admin.skoolx.cloud/api2
    socketBase: "https://socket.skoolx.cloud"  # push host, NOT socket.admin.skoolx.cloud
    currency: "AED"
    usesFastEmiUi: true
```

You can likewise override `apiBase` explicitly if a client's REST host doesn't
follow the `https://{domain}/api2` pattern.

---

## 7. Troubleshooting

| Symptom | Cause & fix |
|---|---|
| Generator rejects the id | `--id` violates `^[a-z][a-z0-9]+$` (uppercase, dash, underscore, leading digit, or too short) or is a **duplicate**. Pick a unique, all-lowercase id. |
| Generator says `--reference` not found | The referenced flavor has no `android/app/src/<reference>/` directory. Use an existing scaffolded flavor (e.g. `fastemi`, `ailocker`). |
| Build fails: *File google-services.json is missing* / Google Services plugin error | You still have the placeholder (or nothing) at `android/app/src/<id>/google-services.json`. Complete manual step §4.1. In CI, set the `GOOGLE_SERVICES_<id>` secret. |
| CI fails on `git diff --exit-code` after editing `flavors.yaml` | You edited the registry but didn't commit the regenerated artifacts. Run `flutter pub get && dart run tool/flavors/gen_flavors.dart` and commit `android/flavors.gen.json` + `lib/config/flavor_registry.g.dart`. |
| New flavor builds but shows the wrong logo | You didn't replace the reference icons — complete manual step §4.2. |
| New flavor phones home to the wrong backend | Wrong `domain` in the entry, or (for a custom subdomain) a missing `socketBase`/`apiBase` override — see §6. |
| Push notifications don't arrive | Still using the reference client's `google-services.json` placeholder — it routes FCM to the wrong Firebase project. Complete manual step §4.1. |
| Theme is basic when it should be premium (or vice-versa) | Toggle `--fastemi-ui` / the `usesFastEmiUi` flag in the entry, then regenerate. Membership is data in one row — no per-screen edits needed. |
