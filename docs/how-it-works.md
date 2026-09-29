# How it works

The app follows a **WebView shell** pattern: it wraps the [Pumperly](https://github.com/GeiserX/Pumperly) web app in a native Android shell. The web version stays the single source of truth, so features, updates and fixes land in the main Pumperly repo and reach the app without a new release.

- The web app at `pumperly.com` is the single source of truth for all UI and business logic
- The Android shell provides native bridges for geolocation, deep links, and connectivity
- New features are added to the web app and automatically appear in the Android app
- App updates are only needed for native-layer changes (permissions, deep links, Play Store metadata)
