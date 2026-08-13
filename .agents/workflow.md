# Running DPC — Agent Workspace Index

> **Start here.** This folder (`.antigravity/`) is the context/workflow hub for AI agents and
> humans working on the Running DPC. It holds the durable knowledge that is *not* obvious from
> the code alone. Read the doc that matches your task before editing.

_Last updated: 2026-07-08._

---

## 🚦 Read-this-first, by task

| If you are going to… | Read |
|---|---|
| Touch **anything** in this repo (get oriented fast) | [`info/project_context.md`](info/project_context.md) — the 5-minute onboarding: what this app is, Device-Admin gotchas, architecture, where things live |
| Add / edit a **white-label client (flavor)** | [`workflows/add_new_client.md`](workflows/add_new_client.md) — the generator command + the 2 manual steps |
| Understand **how flavors work** under the hood | [`info/flavor_system.md`](info/flavor_system.md) — registry → codegen → Gradle/Kotlin/Dart, + hardening |
| See the **flavor matrix** (domains, names, themes) | [`info/flavors.md`](info/flavors.md) |
| Understand the **why / rollout** behind the flavor system | [`plans/flavor_productionization_plan.md`](plans/flavor_productionization_plan.md) |
| Work on **battery / service / watchdog** optimization | [`plans/dpc_optimization_plan.md`](plans/dpc_optimization_plan.md) |
| Harden **enforcement / anti-tamper / security** (what's possible under Device Admin) | [`plans/da_improvement_roadmap.md`](plans/da_improvement_roadmap.md) — DA-verified improvement roadmap (⚠️ has the P0 security items) |

---

## 🧠 The one fact that trips everyone up

This is a **Device Admin** DPC (activated via Settings on already-in-use phones), **NOT** a Device
Owner. Device-Owner-only `DevicePolicyManager` APIs (`setLockTaskPackages`, `setKeyguardDisabled`,
`addUserRestriction`, `setApplicationRestrictions`, …) silently fail or throw here. Kiosk lock is
done with a relaunched full-screen `KioskActivity` + an AccessibilityService watchdog, not lock-task.
Details in [`info/project_context.md`](info/project_context.md).

## 🏷️ Flavors in one paragraph

11 white-label clients share **one** codebase and **one** `applicationId` (`com.renew.jss`),
distinguished only by backend domain + branding + Firebase project. Everything about a client is
defined **once** in [`../tool/flavors/flavors.yaml`](../tool/flavors/flavors.yaml); a Dart codegen
step (`tool/flavors/gen_flavors.dart`) produces `android/flavors.gen.json` and
`lib/config/flavor_registry.g.dart`, which Gradle, `ApiConfig.kt`, and `AppConfig` all read from.
**Never** hand-edit flavor data in `build.gradle.kts` / `ApiConfig.kt` / `app_config.dart` — edit
the YAML and regenerate: `flutter pub get && dart run tool/flavors/gen_flavors.dart`.

---

## 🗂️ Folder map

```
.antigravity/
├── workflow.md                         ← you are here (index / entry point)
├── info/
│   ├── project_context.md              project onboarding & architecture (READ FIRST)
│   ├── flavors.md                      the 11-flavor matrix
│   └── flavor_system.md                flavor registry architecture + build/security hardening
├── workflows/
│   └── add_new_client.md               how to onboard a new client (generator + manual steps)
└── plans/
    ├── flavor_productionization_plan.md  the design/rollout plan behind the flavor system
    └── dpc_optimization_plan.md          battery/service optimization backlog
```

## ✍️ Maintaining these docs

- Keep this index and `info/project_context.md` current when the architecture changes — they are
  the primary context-transfer surface for the next agent/model.
- `plans/*` are historical design records; the **as-built** reference is `info/flavor_system.md`.
- Cross-link with **relative paths** (e.g. `info/flavor_system.md`), never absolute `file://` paths.
