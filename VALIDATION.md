# Validation

Run the current checks from the checkout:

```bash
source tools/android-env.sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
# Requires a connected Android 11+ device or emulator:
./gradlew connectedDebugAndroidTest
```

## Automated coverage

- JVM rules: category signal suggestions; blank/unknown text; dates and age boundaries; size ordering; OCR text/name/category search; Keep protection; hash plus size duplicate identity; global representative retention when a view is filtered; provider aliases cannot count as a retained gallery original; storage estimates.
- Instrumented data: metadata persistence; category overrides and Keep; screenshot naming/folder conventions; supported original-media URI eligibility; real original-byte SHA-256; bundled OCR; original bytes unchanged; external gallery changes; manual collection removal without deleting an original.
- Instrumented UI: selection dispatch; count/storage estimate; category correction; Keep controls and warning; review required before trash dispatch; review cancellation; unsupported trash disabled; limited-access messaging and additional-image controls.
- Real gallery flow: seed original MediaStore PNGs, discover/index them, organise them, invoke the app's review flow, cancel Android's trash confirmation, then confirm it and verify the actual gallery trash flag and updated selection.

Only test-created media is modified by the instrumentation suite. No tests intentionally remove the user's gallery. `testDebugUnitTest` does not exercise Android permission dialogs or media providers.

To test the real Android 14+ denied, selected-photo, and full-access dialogs on a **disposable emulator/test device**, build/install the APK, then run:

```bash
source tools/android-env.sh
python3 tools/check-gallery-access.py --reset-test-app --serial emulator-5554
```

The required flag authorises clearing the test app's local OCR, categories, and Keep metadata between scenarios. The script creates two unique external screenshot fixtures, drives the actual system permission dialogs, checks runtime grant state and visible originals, records JSON/XML evidence, and cleans only its fixtures. It does not fake access with `pm grant`. Android 15 may combine subfolders into its curated Screenshots album; the driver only chooses an unidentified thumbnail there after proving the entire active gallery contains exactly its two fixtures. The driver honours display-size overrides, clips taps to visible control bounds, and retries failed hierarchy reads. Stock English Android selectors may need adapting for vendor/localised UIs. Use `--scenarios limited full` to rerun only those checks.

## Current execution results

| Check | Current workspace result |
| --- | --- |
| Debug APK and instrumentation APK | Built successfully with Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.20, SDK 35 |
| JVM rules | 16 passed, 0 failed, 0 skipped |
| Android lint | No issues: 0 errors, 0 warnings |
| APK signature | Verified with Android build-tools `apksigner` |
| Packaged permissions | Gallery permissions only; no `INTERNET` or `ACCESS_NETWORK_STATE` |
| Android 15/API 35 full device suite | 20 passed, 1 failed, 0 skipped; the trash dialog was obscured by emulator system ANR overlays |
| Trash flow after emulator recovery | Passed: 1 test, 0 failures, 0 skipped; cancellation preserves original/selection, confirmation sets the original's trash flag and refreshes selection |
| Real denied/limited/full permission smoke | All three modes passed across focused runs: denied grants neither permission and opens Settings recovery; limited grants only selected-photo access and displays the one chosen original; full grants image access and displays both originals. Fixture cleanup reported no errors. |

The software emulator has no KVM acceleration and cold starts are slow. Its launcher (Quickstep), and later System UI, showed ANR dialogs over the app and Android's media confirmation. Captured hierarchy/screenshot evidence showed the actual media confirmation underneath. This was not a SnapSort crash. The targeted trash rerun passed using temporary emulator `settings put global hide_error_dialogs 1` to suppress crash/ANR overlays, while retaining Android media/permission confirmations and all functional assertions. No SnapSort crash/ANR events were present in the Android event log. The prior emulator setting was restored to its original unset value after validation. This workaround affects the test emulator, not the application or APK. The 21 distinct instrumented checks are covered by the full run's 20 passes plus the successful targeted trash rerun; the full-run command itself is recorded as failed, rather than relabelled as a clean full-suite run.

## Device coverage checklist

The following scenarios should also be checked on physical devices before production release:

1. Android 11/12 storage access, Android 13 full photo access, and Android 14/15 full versus selected-photo access. Deny once/permanently, use settings recovery, revoke access while away, choose more selected photos, and restart the process.
2. Gallery variants: `Pictures/Screenshots`, `DCIM/Screenshots`, vendor/translated names, SD-card volumes, document-provider images, cloud-only/unavailable images, and same-named images in different volumes.
3. Large real collections: scroll during indexing; change filters and search; correct a category while OCR is running; Keep a duplicate; select extras in a filtered duplicate group; inspect count and byte totals.
4. System trash: cancel; confirm; trash a mixed set where one original disappears externally; stop during a multi-batch operation; rotate/recreate the activity during confirmation; restore outside SnapSort and verify it is visible and unselected.
5. Outside changes: move/rename/delete/restore originals, empty gallery trash, eject removable storage, and return to SnapSort. Existing category/Keep metadata should survive temporary inaccessibility.
6. Accessibility: TalkBack, keyboard/switch navigation, large font/display scaling, small displays, landscape/tablets, and contrast. Selection and trash buttons must remain reachable.

No physical-device/vendor-gallery coverage is claimed unless explicitly recorded above. An emulator test of a specific Android API does not establish behaviour on every vendor or OS version.
