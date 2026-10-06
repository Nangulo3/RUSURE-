# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**RuSure** is a native Android digital-wellbeing app that inserts *intentional friction* (countdown timers + interruptions) before and during use of high-dopamine apps/sections (Instagram Reels, YouTube Shorts, TikTok) instead of hard-blocking them. Kotlin + Jetpack Compose + Material 3, backed by an `AccessibilityService`. UI language is Spanish (strings hardcoded in the Composables).

## Working rules (confirmed by the user — do not violate)

> Single source of truth for these rules, for Claude Code AND the Claude Desktop project
> (which reads this section from the repo). Keep it self-contained and short.

1. **Ask first.** If a change would alter observable behaviour and the expected behaviour is not
   explicitly defined in `docs/DECISIONS.md`, ask before implementing — even if the answer seems
   obvious or a similar implementation exists. (D-003)
2. **Order of work:** understand → document → decide → design → implement. During investigation,
   don't refactor or fix in passing; document the problem. (D-004)
3. **Current ≠ expected.** `DEEP_INIT_REPORT.md` describes today's code; `DECISIONS.md` is the spec.
   Unconfirmed behaviour stays an OPEN QUESTION; never promote it to a decision.
4. **No per-app patches.** Never `if (Instagram && comments) …`; fix the general rule.
5. **Cite sources:** file and line when possible (`service/RuSureAccessibilityService.kt:123`) plus
   document IDs (D-005, B3, §26-B), instead of describing from memory.
6. **Plan first, execute after approval.** Execution is delegated to a Sonnet subagent in auto mode.
7. **Test safety net (D-010).** Any change in `service/` or `domain/engine/` keeps
   `testDebugUnitTest` green. `spec/` failure = regression. `current/` failure = unconfirmed
   behaviour changed → ask first. Exit/foreground/timing changes also require
   `docs/DEVICE_CHECKLIST.md` on a real device.

## Documentation map

| File | Contents |
|---|---|
| `docs/DEEP_INIT_REPORT.md` | Full audit (2026-09-13, extended 2026-10-04): architecture, event system, state machine, timers, concurrency, persistence, UI, bugs B1–B15, suspicious behaviours S1–S14, edge cases E1–E28, 14 open questions |
| `docs/DECISIONS.md` | Only user-confirmed behaviour (D-001…D-012) + the list of open questions |
| `docs/baseline/F1_summary.md` | On-device baseline (2026-10-04): what the engine actually did across 8 friction flows, read from the `RuSure/Engine` trace. Descriptive, not spec. Source of B15. |
| `CLAUDE.md` (this file) | Stable operating context |
| `docs/CLAUDE_PROJECT.md` | Setup of the Claude Desktop *Project* for RuSure: its custom instructions, which repo paths to connect via the GitHub connector, and the push→Sync routine. |

## Commands

```powershell
.\gradlew.bat :app:assembleDebug          # build debug APK
.\gradlew.bat :app:compileDebugKotlin      # fast compile check
.\gradlew.bat :app:testDebugUnitTest       # run JVM unit tests (currently only FormattersTest, 8 tests)
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
- **domain/** — `catalog/TargetCatalog`, `detection/TargetDetector`, **`engine/*`** (`GateEngine` = the state machine, plus `Seams.kt` and `EngineContracts.kt`), `gate/*`, `model/*`, `pause/PauseController`.
- **service/** — `RuSureAccessibilityService`: the **Android adapter** (~254 lines). Receives accessibility events, provides the real `Clock` / `WindowProbe` / `DeviceState` / `AppTargetProbe` / `SectionProbe` / `SessionStore` / `EngineEffects` / `Tracer`, and runs the ticker loop. Since F3 **no policy lives here** — if you are about to add an `if` about *when* to interrupt, it belongs in `GateEngine`.
- **ui/** — `dashboard/`, `statistics/`, `config/`, `interruption/`, `navigation/`, `settings/`, `theme/`, `util/`.
- **di/** — `AppContainer` (manual singletons).

### Manual DI flow
`RuSureApp.onCreate()` builds the single `AppContainer`. Everything reaches it via `(application as RuSureApp).container`. ViewModels are created through `companion object factory(container)` helpers. `AppContainer` owns the singletons that MUST be shared across the process: `repository`, `detector`, and especially **`gateCoordinator`** (the service and the friction Activity live in the same process and share this one instance).

### Detection is split from configuration (important)
`AppTargetConfig` (DB) stores only user-tunable params (timers, enabled, `entryAction`). The *detection signatures* (resource-ids + ES/EN content-descriptions) live in code in `TargetCatalog`, keyed by `catalogKey`. This lets you update fragile matchers (Reels/Shorts viewIds change between app versions) without DB migrations. To support a new app/section, add a `CatalogEntry` — **but note the seeder never re-seeds existing installs** (bug B3).

### The friction lifecycle (core mental model)
`GateEngine` (`domain/engine/`) keeps an in-memory `TargetRuntime` per target (`GateState` + sessionId + clocks + suspension). It is NOT persisted; process death loses it. Its inputs are `onStarted`, `onTargetsChanged`, `onWindowStateChanged`, `onContentChanged`, `onTick` and `onDecision`; its only outputs are `EngineEffects` (`launchFriction`, `goHome`), `SessionStore` and `GateCoordinator`. Being free of Android, it is testable on the JVM with the clock, windows and device state injected (D-010).

States: `IDLE → GATING → ALLOWED → LIMIT_REACHED → (RE_ENTRY) GATING …`, plus `BLOCKED` for `entryAction = BLOCK`.

1. `TYPE_WINDOW_STATE_CHANGED` → APP_GLOBAL detected by package (gates only when `state == IDLE` **and** the package is a "fresh foreground"); `TYPE_WINDOW_CONTENT_CHANGED` (throttled 300 ms) → SECTION detected by walking the node tree (gates when `state == IDLE`, no freshness required). A deferred "settle scan" re-checks sections at +400/1000/1700/2600 ms because the Reels/Shorts player inflates late and then stops emitting events.
2. On a gate the service starts/reuses a `UsageSession`, increments `interruptions`, publishes a `GateRequest` to `GateCoordinator` and launches the **translucent** `InterruptionActivity` (`NEW_TASK | CLEAR_TASK | NO_ANIMATION`) over the target app. With `entryAction = BLOCK` there is no screen: session opened+closed, `GLOBAL_ACTION_HOME`, state `BLOCKED`, HOME re-pressed at most every 400 ms while the target stays foreground.
3. `InterruptionViewModel.consumeRequest()` reads the request (one-shot), runs the countdown and observes 24 h stats. "Continuar" is gated on `remainingSeconds <= 0`.
4. Decisions travel back through `GateCoordinator.submitDecision(...)` (a `SharedFlow` the service collects): `Continue` → `ALLOWED`; `Leave` → `incrementCancelled` + end session + `GLOBAL_ACTION_HOME`.
5. A 1 s ticker accumulates continuous active time while `ALLOWED`; crossing `continuousUsageLimitSeconds` flips to `LIMIT_REACHED` and fires a `RE_ENTRY` gate (same session; INITIAL gates start a new one = a new "opening"). The same ticker runs `sweepExpiredSuspensions()`.
6. `domain/pause/PauseController` (D-009, `docs/DECISIONS.md`) backs a global 5-minute pause: while `isPaused()`, `triggerGate` passes every target through without friction (`allowWithoutFriction`, stats keep counting); the same ticker detects the pause start/end edge and reconciles runtimes (`onPauseStarted`/`onPauseEnded`).

The Activity↔Service contract is entirely `GateCoordinator`; they never reference each other directly. The friction Activity blocks the back gesture, so the only exits are the two buttons.

### How "the user left the app" is inferred — and why it is fragile
There is **no direct signal**. `GateEngine` treats *any* accessibility event whose `packageName` differs from `currentForegroundPackage` as "the target left the foreground" (`onForegroundPackageChanged`), and then:

- internal navigation (section no longer detected, same package) → suspension with **`NO_EXPIRY`** grace → never re-gates;
- foreground left (event from another package) → suspension with **7 s** grace (`BACKGROUND_GRACE_MILLIS`) → `sweepExpiredSuspensions` closes the session and returns the state to `IDLE`;
- `IDLE` + next detection = **new opening** → friction.

Because IME (keyboard), SystemUI and system dialogs emit events with **their own package**, any auxiliary window lasting more than 7 s *used to be* interpreted as leaving the app — the documented root cause of both reported bugs (Instagram comments, TikTok search), see `DEEP_INIT_REPORT.md` §25, §26, §26-B. Fixed on 2026-09-14 by the delayed exit confirmation below.

**Confirmed target behaviour (see `docs/DECISIONS.md`)**:
- **D-005 — IMPLEMENTED**: leaving is *confirmed with a delay*. A foreign-package event no longer suspends anything; it only records a pending exit (`pendingExits`). The 1 s ticker runs `confirmPendingExits()`: after `FOREGROUND_EXIT_CONFIRM_MILLIS` (3 s) it checks `hasApplicationWindow(pkg)` — any `TYPE_APPLICATION` window belonging to the target package. If the app still has one (keyboard/dialog/shade on top), the exit is discarded and the foreground is handed back to it; otherwise the targets are suspended exactly as before (7 s grace counted **from the confirmation**). Not validated on a device yet; the 3 s value is provisional.
- **D-006**: the "time away that makes a return a new opening" becomes a **per-target user setting** (today the `BACKGROUND_GRACE_MILLIS = 7_000` constant). Default/range still undecided.
- **D-007**: internal navigation (comments, profile, search, feed) is immune to friction for as long as the app stays foreground — current behaviour, now confirmed as intentional.
- **D-008**: the continuous-use reminder measures time **in the app** (including internal navigation), while the time shown in Stats counts only the visible section. This asymmetry is deliberate.
- **D-011 — NOT IMPLEMENTED**: locking the phone inside a target is treated like leaving the app. If the locked time exceeds D-006's threshold (the *same* setting, not its own), unlocking counts as a new opening and friction applies. Today `isDeviceActive()` makes a lock of any length friction-free (S8).
- **D-012 — NOT IMPLEMENTED**: while the friction screen is visible, an auxiliary window (IME, shade, system dialog) must not close the gate or drop the session; only a real exit (home, recents, another app) does. Fixes B15.

Do not implement these without being asked.

`getWindows()`/`AccessibilityWindowInfo` is used in exactly one place: `hasApplicationWindow()`, inside the delayed exit confirmation (D-005). Nothing else in the service inspects the window list, `event.className` or `event.windowId`.

### Stats
`UsageSession` rows are the source of truth: openings = row count in the window, usage time = `SUM(activeDurationMillis)`, last use = `MAX(startEpochMillis)`, interruptions = `SUM(interruptions)`, cancelled = `SUM(cancelledAccesses)`. Two consumption paths: SQL aggregates over 24 h (`observeStatsSince`, used by dashboard and friction screen) and raw sessions over 14 days aggregated in memory by `StatisticsViewModel` (used by the three statistics screens). `MockStatistics` still exists but only feeds `@Preview`.

### Navigation
No Navigation-Compose: `ui/navigation/MainNavHost.kt` holds a `sealed interface Destination` in `rememberSaveable` state with a custom `Saver`. Two root tabs (Menú / Estadísticas) with `AppBottomBar`; `Config`, `StatsBreakdown` and `StatsDetail` stack on top. `StatisticsViewModel` is created once in `MainNavHost` and its state is passed down to all three statistics screens.

## Manifest essentials
`RuSureAccessibilityService` is declared with `BIND_ACCESSIBILITY_SERVICE` + `res/xml/accessibility_service_config.xml` (no fixed `packageNames` — so events arrive from **every** app, including the keyboard and SystemUI; filtering is dynamic from the DB). `InterruptionActivity` is `Theme.RuSure.Interruption` (translucent), `noHistory`, `excludeFromRecents`, `singleTask`, `taskAffinity=""`. A `<queries>` block lists the catalog packages for label/icon resolution. **The app declares zero `<uses-permission>`**: no INTERNET, no overlay, no foreground service — everything relies on the accessibility binding.
