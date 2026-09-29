<p align="center">
  <img src="docs/images/banner.svg" alt="Pumperly for Android" width="900"/>
</p>

<h1 align="center">Pumperly for Android</h1>

<p align="center">
  <a href="https://github.com/GeiserX/Pumperly-android/releases"><img src="https://img.shields.io/github/v/release/GeiserX/Pumperly-android?style=flat-square" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/GeiserX/Pumperly-android?style=flat-square" alt="License"></a>
</p>

The Android app for [Pumperly](https://github.com/GeiserX/Pumperly), the open-source fuel and EV route planner. It wraps [pumperly.com](https://pumperly.com) in a WebView and adds native location, deep links and an offline page.

## Features

- **Full Pumperly experience** — route planning, real-time fuel prices, EV charging, corridor filtering
- **Native geolocation** — uses Android's location services for accurate positioning
- **Deep links** — `pumperly.com` URLs open directly in the app
- **Offline fallback** — shows a friendly offline page with retry when there's no connection
- **Dark mode** — follows your system theme automatically
- **Pull-to-refresh** — swipe down to reload
- **Lightweight** — the APK is under 200 KB; minimal battery use

## Quick start

Download `app-release.apk` from the [latest release](https://github.com/GeiserX/Pumperly-android/releases/latest), open it on the phone and allow installs from that source. Needs Android 8.0 (API 26) or later.

The app is not on Google Play yet. To build it yourself, see [Development](docs/development.md).

## Documentation

- [How it works](docs/how-it-works.md): the WebView shell and what stays native
- [Development](docs/development.md): debug build, signed release build, version properties

## Related projects

| Project | Description |
|---------|-------------|
| [Pumperly](https://github.com/GeiserX/Pumperly) | Main web app — fuel & EV route planner |
| [pumperly-mcp](https://github.com/GeiserX/pumperly-mcp) | MCP Server for AI assistants |
| [pumperly-ha](https://github.com/GeiserX/pumperly-ha) | Home Assistant integration |
| [n8n-nodes-pumperly](https://github.com/GeiserX/n8n-nodes-pumperly) (archived) | n8n community node |

## License

[GPL-3.0-or-later](LICENSE). Made by [Sergio Fernandez](https://github.com/GeiserX).
