# CLAUDE.md — Pumperly Android

## Overview
Official Android app for Pumperly, the open-source fuel and EV route planner. A WebView shell wrapping pumperly.com with native geolocation, deep links, offline fallback, dark mode support, and strict security model. Native screens (settings, offline, cheapest nearby) are Compose and the home-screen widget is Glance; no Google Play services, so an F-Droid build stays possible.

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
- **Release workflow:** Push a `v*` tag -> GitHub Actions builds signed APK+AAB, creates GitHub Release
- **CI workflow:** On PR -> debug APK + lint + unit tests. On push to main -> the same plus a signed release APK.
- **Signing keystore:** kept outside the repo, never committed. Base64 in GitHub secret `KEYSTORE_BASE64`; its passwords and alias are in secrets `KEYSTORE_PASSWORD`, `KEY_PASSWORD` and `KEY_ALIAS`, and nowhere in this repo.
- **F-Droid:** MR !35851 at gitlab.com/fdroid/fdroiddata. Metadata at `metadata/com.pumperly.app.yml`.
- **Web app:** pumperly.com (Next.js, source of truth for all features)

## Architecture

```
com.pumperly.app/
  MainActivity.kt      # Single activity: WebView setup, permissions, error handling, state restore
  PumperlyWebView.kt   # WebViewClient + WebChromeClient (navigation, errors, geolocation, file upload)
```

### Key Components

- **MainActivity**: Builds entire UI programmatically (LinearLayout root -> ProgressBar + SwipeRefreshLayout[WebView] + ErrorView). Handles geolocation permission requests, file chooser, back navigation, dark mode, edge-to-edge, and splash screen.
- **PumperlyWebViewClient**: Keeps pumperly.com navigation in-app, opens external URLs in system browser. Reports errors (OFFLINE, SSL, PAGE) to MainActivity for error page display.
- **PumperlyWebChromeClient**: Progress bar updates, geolocation permission prompts, file input chooser.
- **WebViewError enum**: Three error types with distinct UI (OFFLINE=retry, SSL=go back, PAGE=retry).

### Security Model

- `ALLOWED_HOSTS` allowlist for all URL loading and geolocation grants
- `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW`
- `allowFileAccess = false`, `allowContentAccess = false`
- SSL errors always cancelled, never bypassed
- User agent includes `PumperlyAndroid/{version}` for server-side detection

### Dark Mode

- Uses `isAlgorithmicDarkeningAllowed` on Android 13+ (Tiramisu)
- Falls back to deprecated `forceDark` on Android 10-12 (Q to S)
- Error view text color adapts to system dark theme

### Geolocation

- The webapp (pumperly.com) handles all geolocation logic — the Android app just grants WebView permission
- `onGeolocationPermissionsShowPrompt` requests Android location permission when webapp calls `navigator.geolocation.getCurrentPosition()`
- Only pumperly.com origins are granted geolocation access (checked against `ALLOWED_HOSTS`)

### State Management

- State must survive process death: save/restore `pendingUrl`, `currentError`, and `webView.url` via `onSaveInstanceState`
- All UI is built programmatically in `buildLayout()` / `buildErrorView()` — no XML layouts
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
- No offline/PWA support — shows error page when offline
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
