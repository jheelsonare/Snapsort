# Build checks and signed releases

## Continuous checks

`.github/workflows/android-ci.yml` runs on pull requests, pushes to `main`, and manual dispatch. It builds the debug APK and instrumentation APK, runs JVM tests and Android lint, and uploads the APK and reports for 14 days. A separate KVM emulator matrix runs the instrumentation suite on API 30, 33, 34, and 35. After instrumentation, each matrix job reinstalls the built app and test APKs on the same emulator and runs `tools/check-process-death.py --reset-test-app`. API 34 and 35 additionally run `tools/check-gallery-access.py --reset-test-app` for real denied, limited, full, and revoked access. The permission driver requires selected-photo APIs and is deliberately not invoked on API 30 or 33; process-death checks support those versions using their legacy/full-image permission paths. These drivers clear only the disposable test app's data and clean their uniquely recorded gallery fixtures. Their JSON, UI XML, and phase logs are uploaded with the instrumentation reports under `app/build/reports/device-smoke/`. A smoke failure fails its job; no unsupported API is reported as a permission pass.

These remain emulator checks, not physical-phone certification. The physical Android 11–15/vendor-gallery matrix is a manual release gate and requires actual devices; CI cannot substitute for it.

All workflows pin `ubuntu-24.04` and use its preinstalled Android SDK 35/build-tools 35.0.0, verified by an explicit step. They do not run `android-actions/setup-android@v3`: the initial hosted run failed in that setup action before Gradle or any test executed. Its underlying logs required GitHub sign-in and were unavailable from this cloud session, so the precise internal cause is not claimed. Pinning the image also avoids the announced `ubuntu-latest` migration to Ubuntu 26. Official checkout/setup-java/upload-artifact/setup-gradle actions use their current verified major versions to remove Node 20/deprecation warnings. These configuration fixes still require a successful real hosted rerun.

A failed test remains a failed CI run. Test reports are uploaded even on failure. Review the job log and report; do not rerun until the original failure disappears from the record or describe a targeted rerun as a clean full-suite result.

The release workflow does not automatically certify a build merely because a version tag exists. Before tagging, wait for the exact commit's Android checks to pass and record the physical-device, accessibility, performance, and gallery/trash checks in `VALIDATION.md`. Physical-device and consented-data work still requires actual access to those devices/data.

## Publish the exact tested debug APK (interim distribution)

`.github/workflows/debug-release.yml` provides a versioned download for the **exact debug APK tested locally**, without needing a production signing key or a personal access token. It uses the runner's job-scoped `GITHUB_TOKEN` with `contents: write`. This is a debug **prerelease**, separate from the production-signed `v*` workflow below.

Before creating a `debug-v1.1.0` tag:

1. Build and validate the intended APK locally, retaining the debug signing identity if it must update an existing debug installation.
2. Prepare `downloads/SnapSort-download.zip` containing exactly one member named `SnapSort-debug.apk`. Write both the ZIP and extracted APK SHA256 values to `downloads/SHA256SUMS`. Commit the archive, manifest, source, and exact validation results together. Future debug tags likewise require their prepared artifact in the tagged commit; missing or stale files fail publication.
3. Tag that commit with `debug-v` plus the APK's embedded version name, for example `git tag debug-v1.1.0`, then `git push origin debug-v1.1.0`.
4. The workflow verifies the ZIP checksum, its single expected member and CRC, the extracted APK checksum and Android signature, the application ID/debug flag, and the APK version against the tag. It publishes the unchanged APK as `SnapSort-debug-v1.1.0.apk` plus `SHA256SUMS` in a versioned GitHub prerelease. A mismatch stops publication.
5. Verify the published asset's checksum and download before replacing an older working link. New download links should target versioned Releases rather than repository ZIP blobs. Removing the current ZIP from `main` after a release exists does not remove it from its historical release tag; a future debug tag still needs a newly prepared artifact.

This workflow deliberately **does not rebuild or re-sign on the runner**: a fresh runner would generate a different debug key, preventing an update to the locally tested installation. It does not claim production signing or physical-phone certification. The production workflow below remains blocked until the owner supplies its release key and completes the release validation gate.

## One-time signing setup

No production signing key is included in this repository or provisioned by the app. A signed release cannot be published until the repository owner supplies one. Keep a secure backup: future APK updates must use the same signing identity.

1. Generate a key **on your own trusted machine**, using the JDK's interactive password prompts (never put passwords in a shell command or chat):

   ```bash
   keytool -genkeypair -keystore snapsort-release.jks -storetype JKS \
     -alias snapsort -keyalg RSA -keysize 3072 -validity 10000
   ```

2. In the GitHub repository, configure an Actions environment named **release**. Restrict it to version tags and add required reviewers if available. Store these **environment secrets**:

   | Secret | Value |
   | --- | --- |
   | `SNAPSORT_KEYSTORE_BASE64` | The keystore file encoded as a single base64 value |
   | `SNAPSORT_KEYSTORE_PASSWORD` | The keystore password |
   | `SNAPSORT_KEY_ALIAS` | `snapsort`, or the alias you chose |
   | `SNAPSORT_KEY_PASSWORD` | The private key password |

   For example, `base64 < snapsort-release.jks | tr -d '\n'` produces the first value on Linux/macOS. Paste its output only into GitHub's secret field; do not post it in a ticket, chat, or build log. Never commit keystores or passwords. These are GitHub Actions secrets, independent of cloud development HTTP secrets.

3. Confirm repository Actions are enabled and allowed to use the workflow's `contents: write` permission. The workflow uses the job-scoped `GITHUB_TOKEN`; a personal access token is not required.

## Publish a version

1. Set a new, monotonically increasing `versionCode` and the intended `versionName` in `app/build.gradle.kts`; commit them with the release changes.
2. Complete CI and device validation. The initial next version is `versionName = "1.1.0"`, `versionCode = 2`.
3. Tag that tested commit with the exact version name prefixed by `v`:

   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```

4. `.github/workflows/android-release.yml` requires all four signing secrets, builds/tests/lints, verifies the APK signature, and checks that its embedded version matches the tag. It then publishes `SnapSort-v1.1.0.apk` and `SHA256SUMS` to a versioned GitHub Release. Missing secrets, an invalid key, signature failure, or a version mismatch stop publication; there is no debug-key fallback.
5. Download the release asset, verify its checksum and signing certificate, and test installation/update on a device before announcing it. Keep the signing certificate fingerprint in your release records.

The ZIP under `downloads/` is a **debug** distribution/input artifact. Production binaries belong in versioned Release assets, not new Git blobs. Preserve a working old download until its versioned replacement is actually published and verified; a debug prerelease does not become a production release.

A production-signed APK cannot update an installed debug APK because the signatures differ. Uninstalling the debug app first removes its local OCR, category corrections, and Keep metadata; gallery originals remain on the phone. Do not describe that as a seamless update.

## Local signed or unsigned builds

For a local signed build, set `SNAPSORT_KEYSTORE_FILE`, `SNAPSORT_KEYSTORE_PASSWORD`, `SNAPSORT_KEY_ALIAS`, and `SNAPSORT_KEY_PASSWORD` in your shell using a secure secret manager, then run `./gradlew assembleRelease`. `SNAPSORT_KEYSTORE_FILE` is the path to the actual decoded keystore, not base64. The Gradle configuration rejects partially configured signing.

With none of these variables, `assembleRelease` builds an **unsigned** APK for inspection. It cannot be installed or published as a signed release. Ordinary `assembleDebug`, tests, and lint require no signing secrets.
