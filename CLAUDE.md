# CLAUDE.md — Pumperly Android

## Overview
Official Android app for Pumperly, the open-source fuel and EV route planner. A WebView shell wrapping pumperly.com with native geolocation, deep links, offline fallback, dark mode support, and strict security model. Native screens (widget fuel picker, offline stations, cheapest nearby) are written in Compose and the home-screen widget in Glance; no Google Play services, so an F-Droid build stays possible.

## Tech Stack
- Kotlin 2.4.0, compiled by AGP 9's built-in Kotlin. The `kotlin-android` plugin is declared at the root with `apply false` only to pin the compiler version; `:app` applies the Compose and serialization compiler plugins, which must match it.
- The WebView shell is Android Views with a programmatic layout; new native screens use Jetpack Compose (Material 3), the widget uses Glance, background refresh uses WorkManager, settings use DataStore Preferences, JSON uses kotlinx.serialization
- AndroidX WebKit, SwipeRefreshLayout, SplashScreen
- Android SDK 26+ (minSdk), compileSdk 37, targetSdk 36
- JDK 17 (CI and the Docker build agree)
- Gradle with version catalogs (`gradle/libs.versions.toml`)
- Unit tests: JUnit 4 + Robolectric under `app/src/test`. Robolectric runs the API 35 sandbox (`app/src/test/resources/robolectric.properties`) because its API 36 sandbox needs Java 21

## Development

```bash
# Build debug APK
./gradlew assembleDebug

# Signed release build
./gradlew assembleRelease \
  -PPUMPERLY_KEYSTORE_PATH=path/to/keystore.jks \
  -PPUMPERLY_KEYSTORE_PASSWORD=changeme \
  -PPUMPERLY_KEY_ALIAS=release \
  -PPUMPERLY_KEY_PASSWORD=changeme
```

**No local Java on the macOS dev machines.** Build and test on a Linux host with Docker through `scripts/remote-gradle.sh` (it reads the host and a per-checkout build root from `scripts/remote-gradle.env`, which is git-ignored), or let CI do it. A green remote run is a pre-check, not CI.

```bash
scripts/remote-gradle.sh 'assembleDebug lintDebug testDebugUnitTest'
```

Read test counts from `app/build/test-results/testDebugUnitTest/*.xml` (the script fetches them back); `BUILD SUCCESSFUL` alone does not say how many tests ran.

**Lint:** `app/lint.xml` raises `MissingOnRenderProcessGone` to an error; every other issue stays a warning (`warningsAsErrors = false`). `app/lint-baseline.xml` holds only the two existing `MissingOnRenderProcessGone` occurrences in `PumperlyWebView.kt`. The change that adds `onRenderProcessGone` must empty that file (keep the `<issues>` element), or a later regression would be hidden by the baseline.

### Repository & Infrastructure

- **Package:** `com.pumperly.app`
- **Version source of truth:** `gradle.properties` (`VERSION_NAME`, `VERSION_CODE`). CI overrides via `-P` flags.
- **Release workflow:** Bump `gradle.properties`, then push a matching `v*` tag -> GitHub Actions builds the APK (app signing key) and the AAB (Play upload key), checks both certificate fingerprints, and creates the GitHub Release
- **CI workflow:** On PR -> debug APK + lint + unit tests. On push to main -> the same plus a signed release APK.
- **Signing keystores:** kept outside the repo, never committed. The app signing key (certificate SHA-256 `D6:D2:EF:3D:…:3D:EE`, also in pumperly.com's `assetlinks.json`) is in secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`; the Play upload key (`6D:E6:6E:31:…:E5:AF`) in `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_PASSWORD`, `UPLOAD_KEY_ALIAS`. Passwords live nowhere in this repo.
- **F-Droid:** MR !35851 at gitlab.com/fdroid/fdroiddata. Metadata at `metadata/com.pumperly.app.yml`.
- **Web app:** pumperly.com (Next.js, source of truth for all features)

## Architecture

```
com.pumperly.app/
  MainActivity.kt          # Web shell: WebView setup, permissions, error handling, state restore
  PumperlyWebView.kt       # WebViewClient + WebChromeClient (navigation, errors, geolocation, file upload)
  PumperlyApplication.kt   # Redraws the widget when the fuel changes
  core/                    # Settings, fuel types, location, nearest-stations API, station cache, prefetcher
  shell/                   # Navigation policy and ShellError
  ui/settings/             # Fuel picker (SettingsActivity)
  ui/error/                # Error screen with the cached stations
  ui/cheapest/             # Cheapest nearby screen (CheapestNearbyActivity)
  widget/                  # Glance home-screen widget, its state, refresh worker and tap intents
```

### Key Components

- **MainActivity**: Builds the shell programmatically (LinearLayout root -> ProgressBar + SwipeRefreshLayout[FrameLayout[WebView]] + a ComposeView hosting `ui.error.ErrorScreen`). Handles geolocation permission requests, file chooser, back navigation, dark mode, edge-to-edge and the splash screen; opens `SettingsActivity` on first launch and calls `WidgetRefreshWorker.refreshNow` after each foreground fix.
- **PumperlyApplication**: implements `WidgetFuelListener`, so a fuel change in the picker redraws the widget.
- **Manifest**: declares `SettingsActivity` (not exported, singleTop), `CheapestNearbyActivity` (exported for the OPEN_APP_FEATURE capability), the `CheapestNearbyReceiver` widget provider, the `res/xml/shortcuts.xml` launcher shortcuts and `localeConfig`.
- **PumperlyWebViewClient**: Keeps pumperly.com navigation in-app, opens external URLs in system browser. Reports errors (OFFLINE, SSL, PAGE) to MainActivity for error page display.
- **PumperlyWebChromeClient**: Progress bar updates, geolocation permission prompts, file input chooser.
- **ShellError enum**: Three error types with distinct UI (OFFLINE=retry with the cached stations, SSL=go back, PAGE=retry).

### Security Model

- `ALLOWED_HOSTS` allowlist for all URL loading and geolocation grants
- `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW`
- `allowFileAccess = false`, `allowContentAccess = false`
- SSL errors always cancelled, never bypassed
- User agent includes `PumperlyAndroid/{version}` for server-side detection

### Dark Mode

- No WebView darkening: pumperly.com follows `prefers-color-scheme` from the app's DayNight theme; a light/dark switch reloads the page.
- The Compose screens pick their light or dark colours from the system theme

### Geolocation

- The webapp (pumperly.com) handles all geolocation logic — the Android app just grants WebView permission
- `onGeolocationPermissionsShowPrompt` requests Android location permission when webapp calls `navigator.geolocation.getCurrentPosition()`
- Only pumperly.com origins are granted geolocation access (checked against `ALLOWED_HOSTS`)

### State Management

- State must survive process death: save/restore `pendingUrl`, `currentError`, and `webView.url` via `onSaveInstanceState`
- The shell is built programmatically in `buildLayout()` / `buildErrorView()`, no XML layouts; native screens are Compose
- URL allowlist enforced in `ALLOWED_HOSTS` — only pumperly.com HTTPS URLs load in-app
- External URLs open in system browser via `shouldOverrideUrlLoading`

## Key Rules
- Never hardcode signing credentials; pass via Gradle properties or environment variables
- The web version (pumperly.com) is the single source of truth for features
- App updates should be minimal — most changes land in the main Pumperly repo
- License: GPL-3.0

## APK Installation via adb

- `adb install -r <apk>` installs over existing app **only if signatures match**
- Debug and release APKs have different signing keys — installing one over the other fails
- To switch signing type: `adb uninstall com.pumperly.app` first (loses app data)
- CI release APK matches the keystore for GitHub Releases and F-Droid — always prefer `app-release`

## Known Limitations

- `fileUploadCallback` and `pendingGeolocationCallback` are in-memory only — lost on process death. Low risk: user just retries.
- No offline/PWA support for the site itself: offline, the app shows up to three stations from the last cached lookup
- Single-host only — no self-hosted instance support in the app

*Generated by [LynxPrompt](https://lynxprompt.com) CLI*


<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:970c3bf2 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->

## Where the tracker syncs

This repo is public, so its tracker syncs only to the private remote named by `sync.remote` in `.beads/config.yaml`. The block above says sync uses "your git remote". Here that never means this GitHub repo. Don't add it as a Dolt remote and don't push `refs/dolt/*` to it.
