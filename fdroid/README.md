# F-Droid recipe and release builds

`com.musicremover.app.yml` is the metadata recipe for the F-Droid package `com.musicremover.app`. The live copy is in fdroiddata at `metadata/com.musicremover.app.yml` (https://gitlab.com/fdroid/fdroiddata). This file is the version to submit there. F-Droid builds from this repository and signs the APKs with its own key, so F-Droid installs and GitHub installs can't update each other.

## Builds since v4.0.0

The Android build (`android/app/build.gradle.kts`):

- splits the APK per ABI: `arm64-v8a`, `armeabi-v7a`, `x86_64`, plus a universal APK (no 32-bit `x86`);
- sets `versionCode = defaultConfig.versionCode * 10 + abiCode`, with universal 0, armeabi-v7a 1, arm64-v8a 2, x86_64 3. v4.0.0 has a base of 5, so armeabi-v7a is 51, arm64-v8a 52 and x86_64 53;
- names the APKs `murem-<abi>-<buildType>.apk`;
- bundles youtubedl-android (Python, ffmpeg, QuickJS) and ONNX Runtime for on-device processing.

The recipe has one Builds entry per ABI. Each entry runs the normal release build, removes the debug `signingConfig` line in `prebuild` (F-Droid signs the APK itself), and picks its APK with `output`. Without a signing config, Gradle writes `murem-<abi>-release-unsigned.apk`.

This was checked locally on v4.0.0 by running the same `sed` and `assembleRelease`. `output-metadata.json` listed:

| versionCode | file |
|---|---|
| 51 | `murem-armeabi-v7a-release-unsigned.apk` |
| 52 | `murem-arm64-v8a-release-unsigned.apk` |
| 53 | `murem-x86_64-release-unsigned.apk` |

## Automatic updates

`UpdateCheckMode: Tags` and `AutoUpdateMode: Version` let the F-Droid bot add new versions from tags. `UpdateCheckData` reads the base versionCode and versionName from `android/app/build.gradle.kts`, and `VercodeOperation` (`10*%c+1`, `10*%c+2`, `10*%c+3`) turns the base into the three per-ABI codes. A new release then only needs:

1. bump `versionCode` (base) and `versionName` in `android/app/build.gradle.kts`;
2. push a `vX.Y.Z` tag.

Keep the per-ABI scheme and the APK names as they are, or the copied Builds entries stop matching.

## Store metadata

F-Droid reads the description, changelog and screenshots from `fastlane/metadata/android/en-US/` at the commit it builds. Changelogs are named by versionCode, so v4.0.0 has `51.txt`, `52.txt` and `53.txt` with the same text. The 4.0.0 Builds entries point at the commit that added the v4.0.0 fastlane metadata, not at the `v4.0.0` tag. Between the tag and that commit only `fdroid/` and `fastlane/` changed, so the APKs are built from the same app code as the tag. For later releases, commit the new changelogs (`<base>1.txt`, `<base>2.txt`, `<base>3.txt`) before tagging, so the tag already contains them.

## Submitting the update

Open a merge request on fdroiddata that replaces `metadata/com.musicremover.app.yml` with this file. The pipeline runs `fdroid lint` and a test build. Things the reviewers may ask about:

- **License.** youtubedl-android and its ffmpeg build are GPL-3.0. The app source is MIT, which is GPL-compatible, but the combined APK is distributed under GPL-3.0 terms. Reviewers may ask to change `License: MIT`, for example to `MIT AND GPL-3.0-only`. That is the maintainer's decision.
- **Prebuilt native code.** The ONNX Runtime and youtubedl-android AARs from Maven Central contain prebuilt native libraries. Seal (`com.junkfood.seal`) ships youtubedl-android from the same Maven artifacts on F-Droid, so it is accepted in practice.
- **Anti-features.** The recipe declares `NonFreeNet`: models and yt-dlp updates come from GitHub and Hugging Face, and links point to sites such as YouTube. Seal declares the same anti-feature for its yt-dlp updates. yt-dlp updates in Murem only run when the user taps Update yt-dlp.
- **Model license.** `Kim_Vocal_2.onnx` has an unclear license. The app never bundles it; it is downloaded only after the user accepts a notice.

## 16 KB page size

On arm64-v8a and x86_64, the ONNX Runtime libraries, the top-level youtubedl-android libraries (`libpython.so`, `libqjs.so`, `libffmpeg.so`, `libffprobe.so`) and every library inside `libpython.zip.so` are aligned to 16 KB. Five libraries inside `libffmpeg.zip.so` (`libwebp.so`, `libwebpdecoder.so`, `libwebpdemux.so`, `libwebpmux.so`, `libsharpyuv.so`) are aligned to 4 KB only, so the ffmpeg child process may not start on a 16 KB-page device.

Link downloads don't need ffmpeg (`--fixup never --downloader native`). MP3 output does: the app checks first whether ffmpeg can encode MP3, and if not, it saves the result as M4A and says so. Fixing it fully needs an upstream rebuild of youtubedl-android's ffmpeg package.

## GitHub release builds

`.github/workflows` contains only `ci.yml` (Python lint and tests, Android `lint`). No workflow builds or publishes APKs. Release APKs are built locally with:

```
cd android
gradle assembleRelease
```

which writes to `android/app/build/outputs/apk/release/`:

- `murem-arm64-v8a-release.apk`
- `murem-armeabi-v7a-release.apk`
- `murem-x86_64-release.apk`
- `murem-universal-release.apk`

They are signed with the same key as v3.0.0 on GitHub, so they install as updates over earlier GitHub builds.
