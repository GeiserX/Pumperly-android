# Development

## Debug build

```bash
git clone https://github.com/GeiserX/Pumperly-android.git
cd Pumperly-android
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Signed release build

Pass signing config as Gradle properties (`-P`) or environment variables:

```bash
./gradlew assembleRelease \
  -PPUMPERLY_KEYSTORE_PATH=path/to/keystore.jks \
  -PPUMPERLY_KEYSTORE_PASSWORD=changeme \
  -PPUMPERLY_KEY_ALIAS=upload \
  -PPUMPERLY_KEY_PASSWORD=changeme

# Optionally override version (CI derives these from the git tag):
#   -PVERSION_NAME=1.2.0 -PVERSION_CODE=10200
```

Alternatively, set them as environment variables (`PUMPERLY_KEYSTORE_PATH`, etc.).
