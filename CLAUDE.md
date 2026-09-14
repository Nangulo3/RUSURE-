# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**RuSure** is a native Android digital-wellbeing app that inserts *intentional friction* (countdown timers + interruptions) before and during use of high-dopamine apps/sections (Instagram Reels, YouTube Shorts, TikTok) instead of hard-blocking them. Kotlin + Jetpack Compose + Material 3, backed by an `AccessibilityService`. UI language is Spanish (strings hardcoded in the Composables).

## Working rules (confirmed by the user — do not violate)

1. **If a change would alter observable user-facing behaviour and the expected behaviour is not explicitly defined, ASK FIRST.** Do not decide UX/product on your own, even when the answer seems obvious or a similar implementation already exists. (`docs/DECISIONS.md` D-003)
2. Order of work: **understand → document → decide → design → implement**. Don't refactor or "fix while passing by" during an investigation phase; document the problem instead. (D-004)
3. Never turn *current* behaviour into *expected* behaviour. `docs/DEEP_INIT_REPORT.md` = how it works today; `docs/DECISIONS.md` = what the user decided it must do; unconfirmed things stay as open questions.
4. The user delegates plan execution to a Sonnet subagent in auto mode — plan first, execute after approval.

## Documentation map

| File | Contents |
|---|---|
| `docs/DEEP_INIT_REPORT.md` | Full audit (2026-09-13): architecture, event system, state machine, timers, concurrency, persistence, UI, bugs B1–B14, suspicious behaviours S1–S14, edge cases E1–E28, 12 open questions |
| `docs/DECISIONS.md` | Only user-confirmed behaviour + the list of open questions |
| `CLAUDE.md` (this file) | Stable operating context |

## Commands

```powershell
.\gradlew.bat :app:assembleDebug          # build debug APK
.\gradlew.bat :app:compileDebugKotlin      # fast compile check
.\gradlew.bat :app:testDebugUnitTest       # run JVM unit tests (currently only FormattersTest, 5 tests)
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
- Only `material-icons-core` is on the classpath: every other glyph and all charts are hand-drawn with `Canvas`. Don't add the extended icons dependency without asking.

## Architecture

Single-module app under package `com.rusure.app`, layered:

- **data/** — Room (`RuSureDatabase` v4, entities `AppTargetConfig` + `UsageSession`, DAOs, `Converters`), `RuSureRepository` (the only persistence facade), `DefaultTargetsSeeder` (seeds the curated catalog on first run **only if the table is empty**, all targets disabled).
- **domain/** — `catalog/TargetCatalog`, `detection/TargetDetector`, `gate/*`, `model/*`.
- **service/** — `RuSureAccessibilityService` (the engine, ~676 lines: detection + policy + timing + stats writes).
- **ui/** — `dashboard/`, `statistics/`, `config/`, `interruption/`, `navigation/`, `settings/`, `theme/`, `util/`.
- **di/** — `AppContainer` (manual singletons).

### Manual DI flow
`RuSureApp.onCreate()` builds the single `AppContainer`. Everything reaches it via `(application as RuSureApp).container`. ViewModels are created through `companion object factory(container)` helpers. `AppContainer` owns the singletons that MUST be shared across the process: `repository`, `detector`, and especially **`gateCoordinator`** (the service and the friction Activity live in the same process and share this one instance).

### Detection is split from configuration (important)
`AppTargetConfig` (DB) stores only user-tunable params (timers, enabled, `entryAction`). The *detection signatures* (resource-ids + ES/EN content-descriptions) live in code in `TargetCatalog`, keyed by `catalogKey`. This lets you update fragile matchers (Reels/Shorts viewIds change between app versions) without DB migrations. To support a new app/section, add a `CatalogEntry` — **but note the seeder never re-seeds existing installs** (bug B3).

### The friction lifecycle (core mental model)
`RuSureAccessibilityService` keeps an in-memory `TargetRuntime` per target (`GateState` + sessionId + clocks + suspension). It is NOT persisted; process death loses it.

States: `IDLE → GATING → ALLOWED → LIMIT_REACHED → (RE_ENTRY) GATING …`, plus `BLOCKED` for `entryAction = BLOCK`.

1. `TYPE_WINDOW_STATE_CHANGED` → APP_GLOBAL detected by package (gates only when `state == IDLE` **and** the package is a "fresh foreground"); `TYPE_WINDOW_CONTENT_CHANGED` (throttled 300 ms) → SECTION detected by walking the node tree (gates when `state == IDLE`, no freshness required). A deferred "settle scan" re-checks sections at +400/1000/1700/2600 ms because the Reels/Shorts player inflates late and then stops emitting events.
2. On a gate the service starts/reuses a `UsageSession`, increments `interruptions`, publishes a `GateRequest` to `GateCoordinator` and launches the **translucent** `InterruptionActivity` (`NEW_TASK | CLEAR_TASK | NO_ANIMATION`) over the target app. With `entryAction = BLOCK` there is no screen: session opened+closed, `GLOBAL_ACTION_HOME`, state `BLOCKED`, HOME re-pressed at most every 400 ms while the target stays foreground.
3. `InterruptionViewModel.consumeRequest()` reads the request (one-shot), runs the countdown and observes 24 h stats. "Continuar" is gated on `remainingSeconds <= 0`.
4. Decisions travel back through `GateCoordinator.submitDecision(...)` (a `SharedFlow` the service collects): `Continue` → `ALLOWED`; `Leave` → `incrementCancelled` + end session + `GLOBAL_ACTION_HOME`.
5. A 1 s ticker accumulates continuous active time while `ALLOWED`; crossing `continuousUsageLimitSeconds` flips to `LIMIT_REACHED` and fires a `RE_ENTRY` gate (same session; INITIAL gates start a new one = a new "opening"). The same ticker runs `sweepExpiredSuspensions()`.
6. `domain/pause/PauseController` (D-009, `docs/DECISIONS.md`) backs a global 5-minute pause: while `isPaused()`, `triggerGate` passes every target through without friction (`allowWithoutFriction`, stats keep counting); the same ticker detects the pause start/end edge and reconciles runtimes (`onPauseStarted`/`onPauseEnded`).

The Activity↔Service contract is entirely `GateCoordinator`; they never reference each other directly. The friction Activity blocks the back gesture, so the only exits are the two buttons.

### How "the user left the app" is inferred — and why it is fragile
There is **no direct signal**. The service treats *any* accessibility event whose `packageName` differs from `currentForegroundPackage` as "the target left the foreground" (`onForegroundPackageChanged`), and then:

- internal navigation (section no longer detected, same package) → suspension with **`NO_EXPIRY`** grace → never re-gates;
- foreground left (event from another package) → suspension with **7 s** grace (`BACKGROUND_GRACE_MILLIS`) → `sweepExpiredSuspensions` closes the session and returns the state to `IDLE`;
- `IDLE` + next detection = **new opening** → friction.

Because IME (keyboard), SystemUI and system dialogs emit events with **their own package**, any auxiliary window lasting more than 7 s *used to be* interpreted as leaving the app — the documented root cause of both reported bugs (Instagram comments, TikTok search), see `DEEP_INIT_REPORT.md` §25, §26, §26-B. Fixed on 2026-09-14 by the delayed exit confirmation below.

**Confirmed target behaviour (see `docs/DECISIONS.md`)**:
- **D-005 — IMPLEMENTED**: leaving is *confirmed with a delay*. A foreign-package event no longer suspends anything; it only records a pending exit (`pendingExits`). The 1 s ticker runs `confirmPendingExits()`: after `FOREGROUND_EXIT_CONFIRM_MILLIS` (3 s) it checks `hasApplicationWindow(pkg)` — any `TYPE_APPLICATION` window belonging to the target package. If the app still has one (keyboard/dialog/shade on top), the exit is discarded and the foreground is handed back to it; otherwise the targets are suspended exactly as before (7 s grace counted **from the confirmation**). Not validated on a device yet; the 3 s value is provisional.
- **D-006**: the "time away that makes a return a new opening" becomes a **per-target user setting** (today the `BACKGROUND_GRACE_MILLIS = 7_000` constant). Default/range still undecided.
- **D-007**: internal navigation (comments, profile, search, feed) is immune to friction for as long as the app stays foreground — current behaviour, now confirmed as intentional.
- **D-008**: the continuous-use reminder measures time **in the app** (including internal navigation), while the time shown in Stats counts only the visible section. This asymmetry is deliberate.

Do not implement these without being asked, and never patch the symptoms per-app (`if Instagram && comments …`).

The service never inspects `getWindows()`/`AccessibilityWindowInfo` (though `flagRetrieveInteractiveWindows` is enabled), `event.className` or `event.windowId`.

### Stats
`UsageSession` rows are the source of truth: openings = row count in the window, usage time = `SUM(activeDurationMillis)`, last use = `MAX(startEpochMillis)`, interruptions = `SUM(interruptions)`, cancelled = `SUM(cancelledAccesses)`. Two consumption paths: SQL aggregates over 24 h (`observeStatsSince`, used by dashboard and friction screen) and raw sessions over 14 days aggregated in memory by `StatisticsViewModel` (used by the three statistics screens). `MockStatistics` still exists but only feeds `@Preview`.

### Navigation
No Navigation-Compose: `ui/navigation/MainNavHost.kt` holds a `sealed interface Destination` in `rememberSaveable` state with a custom `Saver`. Two root tabs (Menú / Estadísticas) with `AppBottomBar`; `Config`, `StatsBreakdown` and `StatsDetail` stack on top. `StatisticsViewModel` is created once in `MainNavHost` and its state is passed down to all three statistics screens.

## Manifest essentials
`RuSureAccessibilityService` is declared with `BIND_ACCESSIBILITY_SERVICE` + `res/xml/accessibility_service_config.xml` (no fixed `packageNames` — so events arrive from **every** app, including the keyboard and SystemUI; filtering is dynamic from the DB). `InterruptionActivity` is `Theme.RuSure.Interruption` (translucent), `noHistory`, `excludeFromRecents`, `singleTask`, `taskAffinity=""`. A `<queries>` block lists the catalog packages for label/icon resolution. **The app declares zero `<uses-permission>`**: no INTERNET, no overlay, no foreground service — everything relies on the accessibility binding.
