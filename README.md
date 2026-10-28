# Pixli – Image Resizer (Android)

Jetpack Compose app that follows the 7-screen design: Home, Gallery Selection, Compress, Resize,
Edit & Save, Batch Process and Settings. Images are processed on-device and never uploaded. Ads: Yandex Mobile Ads SDK 8.6.0.

- Package: `com.pixli.imageresizer` · minSdk 29 · targetSdk 36
- Kotlin 2.4.10, Compose BOM 2026.09.00, Coil 3.6.3 (checked against current docs)
- Saves to `Pictures/Pixli` through MediaStore (no storage permission needed to save)

## Build with GitHub (no PC needed)

1. Push this folder to a GitHub repo (branch `main`).
2. **Actions → Android Build** runs automatically on every push (or press *Run workflow*).
   - `app-debug-apk` artifact = installable debug APK (no secrets required)
   - `app-release-aab` artifact = signed AAB for Google Play (needs the secrets below)
3. Open the finished run and download the artifacts at the bottom of the page.

## Signing (one time)

In a GitHub Codespace terminal (Java is included):

```bash
keytool -genkeypair -v -keystore release.jks -alias pixli \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks > release.jks.b64     # copy the content of this file
```

Repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `release.jks.b64` |
| `KEYSTORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | `pixli` |
| `KEY_PASSWORD` | the key password you chose |

Back up `release.jks` somewhere safe (never commit it; `.gitignore` already blocks `*.jks`).
If you lose it you cannot update the app on Google Play.

## Yandex ads

- Native ad card at the bottom of Home and Saved (no banner); an interstitial after every 3rd successful save.
- Debug builds always use Yandex demo ad units (`demo-native-content-yandex`, `demo-interstitial-yandex`).
- Release builds use your real ad units, set as defaults in `app/build.gradle.kts`:
  native `R-M-20295158-1`, interstitial `R-M-20295158-2`.
  Optional repo secrets `YANDEX_NATIVE_ID` / `YANDEX_INTERSTITIAL_ID` override them.
- Adjust the interstitial frequency in `Ads.kt` (`EVERY_N_SAVES`).
- Before publishing, update your Google Play Data safety form and privacy policy for ads.

## Notes

- `versionCode` = GitHub run number, so every CI build has a higher code automatically.
- The workflow uses Gradle 8.14.3 directly (no wrapper files needed). To add a wrapper:
  `gradle wrapper --gradle-version 8.14.3`
- Play Store icon (512×512): `store/play_store_icon_512.png`
