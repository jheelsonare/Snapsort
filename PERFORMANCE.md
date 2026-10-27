# Large-library performance

Performance evidence must distinguish metadata calculations from gallery queries, image decoding, OCR, scrolling and device battery use. A synthetic metadata pass cannot establish that a phone handles 10,000 real screenshots smoothly.

## Repeatable host-JVM measurements

Run from this checkout with the Android SDK/JDK configured:

```bash
./tools/measure-metadata-performance.sh
```

The JUnit harness creates deterministic libraries of 1,000 and 10,000 screenshot metadata records. Thirty percent belong to exact-copy groups of three; the others are unique. It varies categories, Keep flags, sizes, dates and approximately 700–900 characters of synthetic OCR text per record. It does not read or write gallery images.

The report is `app/build/reports/benchmarks/metadata-performance.json`. Each operation has five warmups and twenty measured samples with median/p95 wall time and, where the JDK supports it, mean allocation bytes for the current thread. Approximate retained fixture/summary heap is measured after requested garbage collection. GC, JIT, host contention and a shared JVM make these estimates, not device memory or stable regression limits. The test asserts equivalent results for its uncached/cached derivation comparison; it does not assert timing thresholds.

Operations include date browsing, two-word OCR search, age filtering, large-file ordering, duplicate browsing, duplicate-extra selection, selected byte totals and a duplicate-view derivation. The comparison uses the production `LibrarySummary`, reusing library-derived data across control changes; the summary must be rebuilt whenever library records change, particularly Keep, hash, URI or byte size. An implementation must never preserve stale cleanup targets to improve timings. `LibrarySummary` snapshots collections, ignores repeated URI identities, preserves Keep anchors, requires positive-sized supported originals for duplicates and saturates byte totals instead of overflowing.

Measurements are generated at execution time. No device scrolling, OCR throughput, first display, peak Android memory or battery results are claimed by this harness.

## Initial measured evidence

The [initial host-JVM report](performance-results/metadata-initial-2026-10-27.json) was recorded on 2026-10-27 with Temurin 21.0.9 on a Linux amd64 cloud host reporting five available processors, while software-emulator baseline validation was also active. Five warmups and twenty samples were used. The first comparison used an experimental summary equivalent to the prior view-model's full group-size map, including singletons. It motivated the production `LibrarySummary` and cache integration; subsequent reports measure the production helper and include only actual duplicate groups in that map, so their exact work differs slightly.

| Synthetic metadata records | Uncached duplicate-view median | Reused-summary median | Uncached mean allocation | Reused-summary mean allocation |
| --- | --- | --- | --- | --- |
| 1,000 | 3.00 ms | 0.73 ms | 1.12 MB | 0.18 MB |
| 10,000 | 12.07 ms | 3.96 ms | 11.76 MB | 1.86 MB |

The 10,000-record initial run also reported 32.02 ms median date browsing, 28.03 ms OCR-text search and 23.49 ms duplicate browsing. The host was contended and operation ordering/JIT/GC affect results; these figures identify allocation and repeat-calculation pressure, not Android frame-time guarantees. Reusing a summary is supported by the lower calculation cost on unchanged metadata. Device-first-display, actual hashing/OCR throughput, scroll jank, memory and battery remain unmeasured until the device protocol below is executed.

The integrated production `LibrarySummary` rerun is preserved in [performance-results/metadata-production-summary-2026-10-27.json](performance-results/metadata-production-summary-2026-10-27.json). At 10,000 records, the same-run comparison measured 24.71 ms median / 14.30 MB mean allocation without reuse and 3.56 ms / 1.03 MB with reuse. The ViewModel caches by immutable item-list identity and invalidates on library updates, including Keep/hash/category edits. These are within-run comparisons; differences between runs include host contention and JIT effects and are not a before/after phone speed claim.

## Physical-device protocol

Use a disposable test device or a consented test collection. Do not populate or modify a personal gallery without consent. Record phone model, Android/build version, CPU, RAM, storage type, battery health/charge, thermal state, power mode and app commit/version. Use the same fixture set and no other active gallery-writing apps. Include normal and long screenshots, some unreadable/provider-denied entries, and known exact-copy groups. Keep manifest hashes and expected counts; do not commit private screenshot text or image data.

For separate 1,000-, 5,000- and 10,000-image collections:

1. Cold start with an empty app metadata database. Record launch-to-first-gallery-content and launch-to-complete-list using a screen recording or a UI automation timestamp. Record initial discover count, time to first OCR result, indexing duration, succeeded/failed hash and OCR counts, and images per minute. Repeat after a warm restart to separate indexing from metadata reload.
2. Capture a System Trace/Perfetto recording while scrolling the same grid path, opening/zooming a long screenshot, changing categories and entering the same search. Report frame duration/jank distributions, input latency and any missed frames; mean FPS alone hides stalls. Capture `adb shell dumpsys meminfo com.snapsort.app` before indexing, during indexing, after indexing and after viewer navigation. Use Android Studio's memory profiler for retained bitmap/metadata allocations and check for an increasing baseline.
3. Inspect duplicate-extra selection after each hash publication. Compare suggested IDs with the fixture manifest and verify a retained original in every group, including Keep changes and category/search filters. Correctness is required even if summary caching reduces CPU work.
4. Refresh after external deletion, restore and permission revocation. Measure refresh duration and verify unavailable originals disappear without stale selections. Repeat on slow/removable storage if supported by the phone.
5. For battery, unplug the phone, hold brightness/radio/network conditions constant and compare equal-duration idle versus indexing/review runs. Reset battery statistics only on a disposable test device, then capture `adb shell dumpsys batterystats com.snapsort.app` and a bug report/System Trace where appropriate. Report elapsed time, temperature and charging state; do not infer battery drain from JVM CPU timings.
6. Repeat at least three runs per collection/device and report median plus range. Archive commands, raw traces and exact result counts. Do not label an emulator result as physical-device evidence.

Physical devices and a consented screenshot collection are not provided in this cloud workspace. The protocol remains a release validation requirement until those measurements are recorded.
