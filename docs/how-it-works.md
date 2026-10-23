# How it works

The app follows a **WebView shell** pattern: it wraps the [Pumperly](https://github.com/GeiserX/Pumperly) web app in a native Android shell. The web version stays the single source of truth, so features, updates and fixes land in the main Pumperly repo and reach the app without a new release.

- The web app at `pumperly.com` is the single source of truth for all UI and business logic
- The Android shell provides native bridges for geolocation, deep links, and connectivity
- New features are added to the web app and automatically appear in the Android app
- App updates are only needed for native-layer changes (permissions, deep links, Play Store metadata)

## The shell's rules

The decisions the shell makes live in two small objects under `app/src/main/java/com/pumperly/app/shell/`, ported from the iOS app and covered by JVM tests:

- `NavigationPolicy` decides where a navigation goes. Only https on `pumperly.com` and `www.pumperly.com` loads in the app, plus `about:`. `javascript:`, `data:`, `blob:` and `file:` are never followed, in the app or outside it. A frame inside a page may load https in place but never sends the user out of the app. Everything else (other sites, `http:`, `mailto:`, `tel:`, `geo:`) opens in the app that owns it. Only an https pumperly.com origin gets the location.
- `ShellError` maps a failed main-frame load to one of three screens: offline (retry), certificate (go back) and page (retry). Host lookup, connect, timeout and I/O errors are offline; a failed TLS handshake is the certificate screen; an unsupported scheme shows nothing; anything else, and any HTTP 4xx or 5xx, is the page error. Certificate errors are never bypassed.

When the WebView's renderer crashes or is killed for memory, `onRenderProcessGone` replaces the dead WebView with a new one that reloads the same page, so the app keeps running.

The WebView is not darkened by the app. pumperly.com has its own dark theme and follows `prefers-color-scheme`, which the WebView takes from the app's light or dark theme; when the system switches between the two, the page reloads to pick up the change.

## Location for the native surfaces

Each time the app comes to the front with location allowed, it takes one foreground fix (`core.Locator`, 10 s timeout, never prompts), saves it rounded to 3 decimals for up to 7 days, and hands it to `core.NearbyPrefetcher`, which caches the nearest stations for the widget fuel. Without permission (never given, or revoked, which restarts the app), the saved position and the cached stations are deleted. The app never asks for background location.

## Cleartext

Release builds allow no cleartext traffic. Debug builds also allow `http://localhost` and `http://127.0.0.1` for a local dev server, through `app/src/debug/res/xml/network_security_config.xml`.
