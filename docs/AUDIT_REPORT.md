# Pixli – Audit & Fix Report

**Status of verification (read this first)**
This audit was done by reading and editing the source in an environment with no Android SDK and no network.
- NOT executed here: Gradle build, unit tests, debug APK, release AAB, any on-device test, batch runs of 10/50/100 images.
- Executed here: static consistency checks only (brace/paren balance, XML well-formedness, grep for stale API names).
- The GitHub Actions workflow runs the unit tests, builds the debug APK and builds the signed AAB. Those runs are the real verification.

## Bugs found and fixed

### Image engine (`ImageProcessor.kt`, new `ImageMath.kt`)
| Problem | Fix |
|---|---|
| Intermediate bitmaps leaked if an error occurred | Every bitmap is recycled in `finally`; rotation + scale done in ONE allocation |
| `Bitmap.compress()` result ignored; empty files reported as success | Return value and file length are checked; partial output folder is deleted |
| OutOfMemoryError aborted the whole operation | Progressive fallback (100/75/50/35/25 %); the user is told when the output is smaller than requested. Compress-only keeps full resolution unless the device truly cannot hold it |
| EXIF read 3x per image | Orientation and capture date read once in `queryInfo` and stored in `ImageInfo` |
| Corrupted/unsupported images crashed or gave vague errors | `ImageProcessingException` with readable messages |
| Possible 1 px size errors after rotate+scale | Dimensions verified and corrected |
| File-name collisions / odd characters | `sanitizeBaseName` |
| Transparency | Kept for PNG/WEBP; JPG flattened onto white (JPG has no alpha) |
| Presets could upscale | `fitWithin` never upscales and rotates the box for portrait photos |

### Saving / exporting
- Gallery save, Save as… and Share now return a result type; success is reported only if the stream opened and every byte was written.
- Failed saves delete their MediaStore entry / SAF document; no `IS_PENDING` leftovers.
- Original capture date kept (EXIF date, then provider columns).
- Share and Save-as launches catch `ActivityNotFoundException`; Saved screen share/delete report errors.
- Known Android rule: after an uninstall/reinstall the app cannot delete or list images it saved earlier (ownership is lost). The empty state explains where files are.

### Batch
- Results/checks/failures are keyed by image Uri (previously by list index, which broke when the list changed).
- "Add More" appends, ignores duplicates, keeps earlier images and results.
- Individual removal, per-image failure text, "N done · M failed" summary, determinate progress bar, Cancel button.
- Re-entrancy guards: no double processing, no double saving ("Save All" only saves images not yet saved).
- All heavy work is on `Dispatchers.Default/IO`, processed one image at a time to stay safe on low-RAM devices.

### Permissions / compatibility / lifecycle
- Gallery grid: `READ_MEDIA_IMAGES` (33+), `READ_MEDIA_VISUAL_USER_SELECTED` added for Android 14+ partial access, `READ_EXTERNAL_STORAGE` only up to API 32. Photo picker and Files picker need no permission. No CAMERA permission (system camera app is used).
- Rotation: tab, quality, size choice, custom width/height and camera URI survive; ads and the interstitial counter live in the ViewModel and are not recounted or reloaded.
- `largeHeap` enabled for very large photos.

### Yandex ads (SDK 8.6.0, production IDs unchanged)
- Native: one `NativeAdSlot` per screen, held in the ViewModel (SDK asks for a strong reference to loader and ad), application context only (no Activity leak), bounded retries (30 s / 60 s / 120 s, max 3), ad discarded when the screen really goes away, bind failures handled.
- Interstitial: preloaded 2 s after start, shown at most once per loaded ad, bounded retries, new save re-arms loading, safe `show()`, listener cleared, manager destroyed in `onCleared`.
- Rotation no longer double-counts saves. Debug builds: demo IDs and an interstitial after every save; release: IDs `R-M-20295158-1` / `-2`, every 3rd save.
- The Yandex SDK exposes no explicit destroy for native ads in the docs reviewed; cleanup is done by dropping references.

### Privacy / Play
- In-app privacy text rewritten: images never uploaded; Yandex SDK uses the internet and may collect device/advertising information per Yandex's policy. No policy URL was invented.
- Before publishing you still must: host a privacy policy page and enter its URL in Play Console; fill the Data safety form declaring the data collected by the Yandex SDK (device/advertising IDs at minimum; confirm in Yandex's SDK documentation) and check the merged manifest for `AD_ID`; consider a consent flow for EEA/UK users.

### Build
- AGP 9.1.0 + Gradle 9.3.1 + compileSdk 37 (required by the current Compose/lifecycle/Coil versions), built-in Kotlin.
- Workflow: unit tests first, test report artifact, least-privilege token, cancels superseded runs, debug APK + signed AAB jobs kept.
- R8: `-dontwarn com.yandex.**` and related rules; release still minified and shrunk.

## Tests added
`app/src/test/.../ImageMathTest.kt` (10 JVM tests): preset fitting/no-upscale/portrait box, aspect lock, sample size, EXIF axis swap, size limits, name sanitising, size formatting. **Not executed in this environment.**
Not covered by automated tests (needs a device or Robolectric): bitmap decode/encode, MediaStore saving, permissions, ad loading.

## Modified / new files
Modified: `Models.kt`, `ImageProcessor.kt`, `MainViewModel.kt`, `Ads.kt`, `App.kt`, `Components.kt`, `HomeScreens.kt`, `SelectScreen.kt`, `EditorScreens.kt`, `SettingsScreen.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `.github/workflows/android-build.yml`, `README.md`.
New: `ImageMath.kt`, `ImageMathTest.kt`, `docs/AUDIT_REPORT.md`.

## Known limitations
- Nothing here has been compiled or run; expect to fix small compile issues on the first CI run.
- Process death (system kills the app in the background) loses in-progress selections; saved images are unaffected.
- Metadata (EXIF/GPS) is not copied to outputs, except the original date.
- Very large images on low-memory devices may be produced smaller than requested (the user is told).
- Animated images become a single frame.
