# F-Droid recipe and release builds

`com.musicremover.app.yml` is the F-Droid metadata recipe. Its existing Builds entries (v2.1.0, v3.0.0) build tags that have no ABI splits, so they still work unchanged.

## What changed after v3.0.0

From the release after v3.0.0 on, the Android build (`android/app/build.gradle.kts`):

- splits the APK per ABI: `arm64-v8a`, `armeabi-v7a`, `x86_64`, plus a universal APK (no 32-bit `x86`);
- sets `versionCode = defaultConfig.versionCode * 10 + abiCode`, with universal 0, armeabi-v7a 1, arm64-v8a 2, x86_64 3. For a base of 4 that gives universal 40, armeabi-v7a 41, arm64-v8a 42, x86_64 43;
- bundles youtubedl-android (Python, ffmpeg, QuickJS) next to ONNX Runtime for on-device link processing.

## Builds entries for the next release

Add one Builds entry per ABI. The `release` build type is signed with the debug key (`signingConfig = signingConfigs.getByName("debug")`), which would produce `murem-<abi>-release.apk`. F-Droid signs APKs itself, so remove that line in `prebuild`. Gradle then writes `murem-<abi>-release-unsigned.apk`.

Example for a base versionCode `N` and version `X.Y.Z` (replace `N*10+k` with the computed number):

```yaml
  - versionName: X.Y.Z
    versionCode: N*10+1
    commit: vX.Y.Z
    subdir: android/app
    gradle:
      - yes
    prebuild: sed -i -e '/signingConfig/d' build.gradle.kts
    output: build/outputs/apk/release/murem-armeabi-v7a-release-unsigned.apk

  - versionName: X.Y.Z
    versionCode: N*10+2
    commit: vX.Y.Z
    subdir: android/app
    gradle:
      - yes
    prebuild: sed -i -e '/signingConfig/d' build.gradle.kts
    output: build/outputs/apk/release/murem-arm64-v8a-release-unsigned.apk

  - versionName: X.Y.Z
    versionCode: N*10+3
    commit: vX.Y.Z
    subdir: android/app
    gradle:
      - yes
    prebuild: sed -i -e '/signingConfig/d' build.gradle.kts
    output: build/outputs/apk/release/murem-x86_64-release-unsigned.apk
```

Then set `CurrentVersionCode` to the highest code (`N*10+3`).

## License review

`io.github.junkfood02.youtubedl-android:library` declares GPL-3.0, and the bundled ffmpeg build is GPL as well. APKs that bundle them are distributed under GPL-3.0 terms (source offer, notices). The repository source stays MIT, which is GPL-compatible. The recipe's `License: MIT` needs review before the next release, for example `MIT AND GPL-3.0-only`, or a GPL-3.0 relicense. That is the maintainer's decision.

## F-Droid scanner and prebuilt binaries

The ONNX Runtime and youtubedl-android AARs contain prebuilt native binaries (ONNX Runtime, Python, ffmpeg, QuickJS). The F-Droid scanner flags them. The main F-Droid repository would need source builds of these libraries, or `scanignore` entries, which F-Droid policy may reject. A third-party repository such as IzzyOnDroid accepts the APKs built on GitHub instead.

## 16 KB page size

On arm64-v8a and x86_64, the ONNX Runtime libraries, the top-level youtubedl-android libraries (`libpython.so`, `libqjs.so`, `libffmpeg.so`, `libffprobe.so`) and every library inside `libpython.zip.so` are aligned to 16 KB. Five libraries inside `libffmpeg.zip.so` (`libwebp.so`, `libwebpdecoder.so`, `libwebpdemux.so`, `libwebpmux.so`, `libsharpyuv.so`) are aligned to 4 KB only, so an ffmpeg child process cannot start on a 16 KB-page device. The app never runs ffmpeg (downloads use `--fixup never --downloader native`), so link processing still works there. Fixing it needs an upstream rebuild of youtubedl-android's ffmpeg package.

## Release builds

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

`output-metadata.json` in the same folder lists each APK's versionCode.
