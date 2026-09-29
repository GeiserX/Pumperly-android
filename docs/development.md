# Development

## Debug build

```bash
git clone https://github.com/GeiserX/Pumperly-android.git
cd Pumperly-android
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Signed release build

Pass the non-secret settings as Gradle properties (`-P`) and the passwords as environment variables, so they stay out of your shell history and the process list:

```bash
export PUMPERLY_KEYSTORE_PASSWORD=...   # or load them from your secret manager
export PUMPERLY_KEY_PASSWORD=...
./gradlew assembleRelease \
  -PPUMPERLY_KEYSTORE_PATH=path/to/keystore.jks \
  -PPUMPERLY_KEY_ALIAS=upload

# Optionally override version (CI derives these from the git tag):
#   -PVERSION_NAME=1.2.0 -PVERSION_CODE=10200
```

Each of the four signing settings (`PUMPERLY_KEYSTORE_PATH`, `PUMPERLY_KEYSTORE_PASSWORD`, `PUMPERLY_KEY_ALIAS`, `PUMPERLY_KEY_PASSWORD`) is read as a Gradle property first, then from the environment.
