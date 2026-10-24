# SnapSort

A private Android screenshot organiser built with Kotlin, Jetpack Compose and Material 3. Review your existing gallery, find text, protect useful images, then send unwanted originals to Android's trash with system confirmation.

## Download the Android app

[Download the debug APK as a ZIP](https://github.com/jheelsonare/Snapsort/raw/refs/heads/snapsort-download/downloads/SnapSort-download.zip). Extract it and install `SnapSort-debug.apk` on Android 11 or newer. See [downloads](downloads/README.md) for checksums and installation details.

## Run it

Requirements: **Android 11 or newer**; JDK 17 or 21; Android SDK platform 35 and build-tools 35.0.0. No API key, account, backend, or paid service is required. The Latin OCR model ships with the app.

Open this checkout in Android Studio and let Gradle sync, or build from a terminal:

```bash
# Linux setup helper, if the JDK/Android SDK are missing.
# Read the Android SDK license first; this flag accepts it for installation.
./tools/bootstrap-android.sh --accept-licenses
source tools/android-env.sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

The helper installs tools beside the checkout by default, honours existing Android SDK/cache environment variables, and verifies downloaded archives. It keeps machine paths in ignored `local.properties` and proxy settings in the local Gradle cache. Android Studio users can use their existing SDK and JDK without the helper. The helper supports Linux x86-64; other hosts should install Android Studio's SDK/JDK normally.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

```bash
# Connect a device with USB debugging enabled, or start an Android Studio emulator.
source tools/android-env.sh
adb devices
./gradlew installDebug
adb shell am start -n com.snapsort.app/.MainActivity
./gradlew connectedDebugAndroidTest
```

Use the existing checkout for cloud tasks. Each cloud task is isolated; a Git worktree is unnecessary.

## Use SnapSort

1. Tap **Allow access**. SnapSort explains its local processing before asking Android for gallery access. Android 14+ can grant access to selected images; the app shows that limitation and lets you choose more. Denied access has a settings recovery action, and **Select images** works independently of broad permission.
2. Screenshots are found in MediaStore by folder and filename, including common vendor and translated conventions. Add missed images through Android's document picker. There are no full private copies of the collection.
3. Browse by date and category, or search filenames and recognised text. OCR and SHA-256 indexing run incrementally in the background. Results become available as processing completes; the progress indicator and any indexing failures remain visible. Suggested categories are simple local text rules; tap a category in the preview to correct it.
4. Review **Exact duplicates**, **Older screenshots**, **Large screenshots**, or **All screenshots**. Duplicate identity requires distinct canonical MediaStore originals with matching full-file SHA-256 and byte size. Unresolved provider references are excluded because they may alias the same original. **Select extra copies · keep one** retains one original globally, preferring a Keep image and otherwise the newest copy—even when the view is filtered. Visually similar files with different bytes are not called exact duplicates.
5. Mark useful images **Keep**. Kept images remain in All screenshots but are excluded from cleanup suggestions and Select all by default. You can explicitly include them or select them individually. A review warns when the selection contains Keep images. Age and size are review signals, never reasons for automatic removal.
6. **Select all** applies to the current filtered view and protects Keep by default. The selected count and byte estimate cover the entire selection, including selected items hidden by a subsequent search or category filter. **Deselect** clears it. Preview the selected names, thumbnails, dates, and sizes in **Review selection**.
7. **Continue to Android · move to trash** requests Android's confirmation for the original MediaStore images. Cancelling preserves remaining images and their selection. Confirmation, cancellation, permission changes, and gallery changes all trigger a fresh scan. Requests over 2,000 images use multiple system confirmations; cancellation stops the remaining batches.

Trash can retain storage until it is emptied or expires. The estimate is the original file sizes, not a promise of immediately reclaimed space. Recovery belongs to the device gallery's trash where available; SnapSort does not present an Undo button or implement restoration.

**Remove from SnapSort only** is offered for manually added images and affects the local collection, leaving originals intact. Some document/cloud providers supply readable images without an Android MediaStore trash capability. These images can still be indexed and organised, but cleanup confirmation is disabled for a selection containing them; remove them from that selection or manage the originals in their provider/gallery. The app never falls back to silently deleting a document.

## Implementation

- `data/GalleryScanner.kt` queries original MediaStore rows and resolves supported picked images to stable per-volume media URIs. Pending/trashed images are excluded. Grants are rechecked on resume; a content observer refreshes external changes.
- `data/MetadataStore.kt` stores URI metadata, extracted text, SHA-256, category overrides, Keep, and manual collection membership in SQLite. Batch transactions and changed-row writes keep refresh work bounded. Unavailable originals are hidden while their organisation metadata is retained for temporary permission/storage loss.
- `data/ImageIndexer.kt` streams original bytes for hashing and bounds decoded images for OCR. The bundled ML Kit Latin recognizer works on the device without an account. Other writing systems are not bundled in this version.
- `domain/CleanupRules.kt` and `CategoryClassifier.kt` contain deterministic review and suggestion rules. User category corrections take precedence over OCR suggestions.
- `SnapSortViewModel.kt` handles selection, refresh, and derived views. Filtering and duplicate summaries execute off the UI thread. The UI uses lazy thumbnail grids; it does not decode all originals at once.
- `MainActivity.kt` hosts permission/picker contracts and `MediaStore.createTrashRequest`. Android performs the trash operation after its confirmation; the app reconciles the result from a new gallery query.

The database is private app storage and Android backup is disabled. Uninstalling SnapSort removes its metadata, not your gallery originals. See [VALIDATION.md](VALIDATION.md) for checks and device coverage.
