# Privacy Policy: Pumperly for Android

Pumperly for Android shows the Pumperly website, https://pumperly.com, and adds a home-screen widget, a "Cheapest nearby" screen and an offline list. It has no accounts, no ads, no analytics and no tracking, and we never sell data.

## What the app uses

- **Location.** With your permission, the app reads your location while it is open and gives it to the Pumperly page you are looking at, the same way a browser does, so the page can show stations near you and plan routes. Only pumperly.com pages can receive it. The app never asks for location in the background.
- **The widget and the "Cheapest nearby" screen.** To show the cheapest stations around you, the app sends a position rounded to about 110 m, together with your chosen fuel, to Pumperly's stations API. The widget reuses the last position the app saw while it was open. The request carries no identifier, so nothing ties it to you.
- **On your device only.** The fuel you choose, the last rounded position and the last list of stations (shown when you are offline) are stored in the app's private storage. They never leave the device except as the request above.

## Permissions

- **Internet and network state**: to load pumperly.com, to fetch prices, and to tell when you are offline.
- **Precise and approximate location**: only while the app is open, as described above.
- **Wake lock, run at startup, foreground service**: requested by Android's WorkManager library, which refreshes the widget about once an hour and after a restart. The app runs no foreground service of its own.

## What the website does

Everything you do inside the app's web view happens on pumperly.com and follows the website's privacy policy: https://pumperly.com/en/privacy

## What we do not do

- No account, sign-in or registration.
- No advertising, no analytics SDKs, no Google Play services, no tracking across apps or websites.
- No sale or sharing of data with data brokers.

## Contact

support@pumperly.com

Last updated: October 2026.
