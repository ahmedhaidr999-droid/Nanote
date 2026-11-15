# Nanote — Release & Signing Guide

This document explains how to produce release builds of Nanote **without ever
committing signing credentials to Git**.

## ⚠️ Security notice (read before publishing)

**A release keystore (`keystore/nanote-release.keystore`) and its passwords
were accidentally committed to this repository's history during earlier
development (before Part 4).** They were removed from the tree in the Part 4
integration, but they remain retrievable from git history.

Consequences and required actions:

1. **Never use that old key for a public release.** Anyone with repository
   access can extract it.
2. **Generate a brand-new keystore** (instructions below) and keep it
   strictly out of Git.
3. If you ever published an APK signed with the old key, rotating the
   signing certificate on Google Play requires Play App Signing key reset
   (contact Play Console support).
4. The `.gitignore` now blocks `keystore/`, `*.keystore`, `*.jks` and
   `keystore.properties` patterns.

## Versioning

| Part | versionName | versionCode |
|---|---|---|
| Part 1 — foundation | 1.1.0 | 2 |
| Part 2 — handwriting engine | 1.2.0 | 3 |
| Part 3 — PDF/storage/search/export | 1.3.0 | 4 |
| **Part 4 — final integration (current)** | **1.4.0** | **5** |

Bump both in `app/build.gradle.kts` (`defaultConfig`). `versionCode` must
always increase monotonically.

## Application identity

- Application id / namespace: `com.nanote.app`
- Label: “Nanote”, launcher icon: adaptive + legacy (`app/src/main/res/mipmap*`)
- minSdk 26 (Android 8.0) · targetSdk 35 · compileSdk 35
- Debug builds add the `.debug` application-id suffix — debug and release can
  be installed side by side.

## Generating a release keystore (one-time)

```bash
keytool -genkeypair -v \
  -keystore nanote-release.keystore \
  -alias nanote \
  -keyalg RSA -keysize 4096 -validity 10000
```

Store the keystore somewhere safe **outside** the repository (password
manager, encrypted drive). Losing it means users can never upgrade over an
existing install.

## Local signing setup (untracked file)

Create `keystore/keystore.properties` (gitignored) in the project root:

```properties
storeFile=keystore/nanote-release.keystore
storePassword=<your store password>
keyAlias=nanote
keyPassword=<your key password>
```

`app/build.gradle.kts` picks it up automatically — `./gradlew assembleRelease`
then produces a properly signed APK.

## Signing via environment variables (CI)

The build also accepts credentials from the environment:

```
NANOTE_STORE_FILE=<path or keystore/keystore.properties-relative path>
NANOTE_STORE_PASSWORD=...
NANOTE_KEY_ALIAS=...
NANOTE_KEY_PASSWORD=...
```

Environment variables take precedence over the properties file.

### GitHub Actions

Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Content |
|---|---|
| `NANOTE_KEYSTORE_BASE64` | `base64 -w0 nanote-release.keystore` output |
| `NANOTE_STORE_PASSWORD` | keystore password |
| `NANOTE_KEY_ALIAS` | key alias (e.g. `nanote`) |
| `NANOTE_KEY_PASSWORD` | key password |

When `NANOTE_KEYSTORE_BASE64` is set, the `release` CI job restores the
key and the APK/AAB are signed with it. **Without the secrets the release
job still builds, but signs with the debug key** — such artifacts are for
internal testing only and are clearly labeled by the job name.

## Building release artifacts

```bash
# Release APK (R8 minified + resource shrinking)
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk

# Release AAB (for Google Play / Bundletool)
./gradlew bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

R8/ProGuard configuration: `proguard-rules.pro` at the app module.
The release build enables `isMinifyEnabled` + `isShrinkResources`.

Verified during Part 4 integration (sandbox, JDK 17, Android SDK 35):
`assembleRelease` and `bundleRelease` both complete successfully;
`apksigner verify` confirms the signature scheme of the produced APK.

## CI pipeline

`.github/workflows/build.yml` runs on every push (`main`, `feature/**`) and
every pull request:

1. **build-test** — `assembleDebug` (all modules), the full unit-test suite,
   `:app:lintDebug`, `assembleDebugAndroidTest`; uploads the debug APK
   artifact (test reports on failure).
2. **release** — `assembleRelease` + `bundleRelease`, APK and AAB artifacts;
   on `v*` tags it creates a GitHub Release with both artifacts.

## Publishing checklist (manual — never automated)

1. All CI checks green on the release commit.
2. Full local test suite green (`./gradlew testDebugUnitTest :core:model:test :core:storage:test`).
3. Physical-device smoke pass (see `TESTING.md` — handwriting, PDF, data
   integrity scenarios).
4. `versionCode`/`versionName` bumped.
5. `CHANGELOG.md` updated.
6. New keystore generated and configured via secrets (see the security
   notice above — do not reuse the exposed key).
7. Tag `v<versionName>` and push the tag; CI attaches the artifacts to a
   GitHub Release.
8. Review the GitHub Release notes before announcing anything.

Nanote is never published automatically; releasing is a deliberate human
action.
