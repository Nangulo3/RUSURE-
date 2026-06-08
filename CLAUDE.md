# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**RuSure** is a native Android digital-wellbeing app that inserts *intentional friction* (countdown timers + interruptions) before and during use of high-dopamine apps/sections (Instagram Reels, YouTube Shorts, TikTok) instead of hard-blocking them. Kotlin + Jetpack Compose + Material 3, backed by an `AccessibilityService`.

## Commands

```powershell
.\gradlew.bat :app:assembleDebug          # build debug APK
.\gradlew.bat :app:compileDebugKotlin      # fast compile check
.\gradlew.bat :app:testDebugUnitTest       # run JVM unit tests
.\gradlew.bat :app:installDebug            # install on connected device/emulator
.\gradlew.bat :app:connectedDebugAndroidTest   # instrumented tests (needs device)
```

Run a single unit test:
```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.rusure.app.FormattersTest.formatDuration_zero"
```

First build must be **online** (downloads Room/coroutines/etc.); afterwards `--offline` works.

## Toolchain gotchas (read before touching the build)

This project runs on a very new toolchain (AGP 9.2.1, Kotlin 2.2.10) with non-obvious consequences:

- **AGP 9 uses built-in Kotlin.** Do NOT add the `org.jetbrains.kotlin.android` plugin — it is not applied and is not needed. Adding it will conflict.
- **Hilt does not work here.** The Hilt Gradle plugin calls AGP's `BaseExtension`, which AGP 9 removed (`Android BaseExtension not found`). DI is therefore **manual** via `di/AppContainer.kt`. Do not reintroduce Hilt/Dagger annotations (`@AndroidEntryPoint`, `@HiltViewModel`, `@Inject`) unless the AGP/Hilt versions are first pinned to a compatible pair.
- **KSP + built-in Kotlin** requires `android.disallowKotlinSourceSets=false` in `gradle.properties` (already set). KSP is used only for Room.

## Architecture

Single-module app under package `com.rusure.app`, layered:

- **data/** — Room (`RuSureDatabase`, entities `AppTargetConfig` + `UsageSession`, DAOs, `Converters`), `RuSureRepository` (the only persistence facade), `DefaultTargetsSeeder` (seeds the curated catalog on first run, all targets disabled).
- **domain/** — `catalog/TargetCatalog`, `detection/TargetDetector`, `gate/*`, `model/*`.
- **service/** — `RuSureAccessibilityService` (the engine).
- **ui/** — `interruption/` (friction screen) and `settings/` (config), each with a `ViewModel`.
- **di/** — `AppContainer` (manual singletons).

### Manual DI flow
`RuSureApp.onCreate()` builds the single `AppContainer`. Everything reaches it via `(application as RuSureApp).container`. ViewModels are created through `companion object factory(container)` helpers passed to `by viewModels { ... }`. `AppContainer` owns the singletons that MUST be shared across process: `repository`, `detector`, and especially **`gateCoordinator`** (the service and the friction Activity live in the same process and share this one instance).

### Detection is split from configuration (important)
`AppTargetConfig` (DB) stores only user-tunable params (timers, enabled). The *detection signatures* (resource-ids + ES/EN content-descriptions) live in code in `TargetCatalog`, keyed by `catalogKey`. This lets you update fragile matchers (Reels/Shorts viewIds change between app versions) without DB migrations. To support a new app/section, add a `CatalogEntry`.

### The friction lifecycle (core mental model)
`RuSureAccessibilityService` keeps an in-memory `GateState` per target: `IDLE → GATING → ALLOWED → LIMIT_REACHED → (re-entry) GATING …`. It is NOT persisted.

1. `TYPE_WINDOW_STATE_CHANGED` → detect APP_GLOBAL by package; `TYPE_WINDOW_CONTENT_CHANGED` (throttled) → detect SECTION by walking the node tree.
2. On detection while `IDLE`, the service: starts/finds a `UsageSession`, increments its `interruptions`, publishes a `GateRequest` to `GateCoordinator`, and launches the **translucent** `InterruptionActivity` (`FLAG_ACTIVITY_NEW_TASK | CLEAR_TASK`) over the target app.
3. `InterruptionViewModel.consumeRequest()` reads the request, runs the countdown, observes 24h stats. The "Continuar" button is gated on `remainingSeconds <= 0`.
4. User decision goes back through `GateCoordinator.submitDecision(...)` (a `SharedFlow` the service collects): `Continue` → `ALLOWED`; `Leave` → end session + `performGlobalAction(GLOBAL_ACTION_HOME)`.
5. A 1s ticker accumulates continuous active time while `ALLOWED`; crossing `continuousUsageLimitSeconds` flips to `LIMIT_REACHED` and fires a `RE_ENTRY` gate (reuses the same session; INITIAL gates start a new one = a new "opening").

The Activity↔Service contract is entirely `GateCoordinator`; they never reference each other directly. The friction Activity blocks the back gesture so the only exits are the two buttons.

### Stats
`UsageSession` rows are the source of truth: openings = row count in window, usage time = `SUM(activeDurationMillis)`, last use = `MAX(startEpochMillis)`, interruptions = `SUM(interruptions)`. The 24h aggregate is a single reactive Room query (`observeStatsSince`).

## Manifest essentials
`RuSureAccessibilityService` is declared with `BIND_ACCESSIBILITY_SERVICE` + `res/xml/accessibility_service_config.xml` (no fixed `packageNames` — filtering is dynamic from the DB). `InterruptionActivity` is `Theme.RuSure.Interruption` (translucent), `noHistory`, `excludeFromRecents`, `singleTask`. A `<queries>` block lists the catalog packages for label resolution.
