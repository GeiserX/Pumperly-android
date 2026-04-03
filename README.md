<p align="center">
  <img src="docs/images/banner.svg" alt="Pumperly Android" width="900"/>
</p>

<h1 align="center">Pumperly for Android</h1>

<p align="center">
  <strong>Open-source fuel & EV route planner — native Android app.</strong>
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.pumperly.app"><img src="https://img.shields.io/badge/Google%20Play-Download-34d399?style=flat-square&logo=google-play&logoColor=white" alt="Google Play"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/GeiserX/Pumperly-android?style=flat-square" alt="License"></a>
  <a href="https://github.com/GeiserX/Pumperly-android/releases"><img src="https://img.shields.io/github/v/release/GeiserX/Pumperly-android?style=flat-square" alt="Release"></a>
</p>

---

## What is this?

The official Android app for [Pumperly](https://pumperly.com), wrapping the web app in a native shell. This keeps the web version as the single source of truth — all features, updates, and fixes land in the [main Pumperly repo](https://github.com/GeiserX/Pumperly) and are instantly available in the app without a new release.

## Features

- **Full Pumperly experience** — route planning, real-time fuel prices, EV charging, corridor filtering
- **Native geolocation** — uses Android's location services for accurate positioning
- **Deep links** — `pumperly.com` URLs open directly in the app
- **Offline fallback** — shows a friendly offline page with retry when there's no connection
- **Dark mode** — follows your system theme automatically
- **Pull-to-refresh** — swipe down to reload
- **Lightweight** — ~3 MB APK, minimal battery usage

## Install

**Google Play** (recommended):

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" width="200">](https://play.google.com/store/apps/details?id=com.pumperly.app)

**Direct APK**:

Download the latest APK from [Releases](https://github.com/GeiserX/Pumperly-android/releases).

## Building from Source

```bash
git clone https://github.com/GeiserX/Pumperly-android.git
cd Pumperly-android
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Signed Release Build

```bash
# Set signing config in local.properties:
# KEYSTORE_FILE=path/to/keystore.jks
# KEYSTORE_PASSWORD=...
# KEY_ALIAS=...
# KEY_PASSWORD=...

./gradlew assembleRelease
```

## Architecture

This app follows a **WebView shell** pattern:

- The web app at `pumperly.com` is the single source of truth for all UI and business logic
- The Android shell provides native bridges for geolocation, deep links, and connectivity
- New features are added to the web app and automatically appear in the Android app
- App updates are only needed for native-layer changes (permissions, deep links, Play Store metadata)

## Related Projects

| Project | Description |
|---------|-------------|
| [Pumperly](https://github.com/GeiserX/Pumperly) | Main web app — fuel & EV route planner |
| [pumperly-mcp](https://github.com/GeiserX/pumperly-mcp) | MCP Server for AI assistants |
| [pumperly-ha](https://github.com/GeiserX/pumperly-ha) | Home Assistant integration |
| [n8n-nodes-pumperly](https://github.com/GeiserX/n8n-nodes-pumperly) | n8n community node |

## License

[GPL-3.0](LICENSE)

---

<p align="center">
  Made by <a href="https://github.com/GeiserX">Sergio Fernandez</a>
</p>
