#!/usr/bin/env bash
# Measures deterministic synthetic metadata only. Does not access a device/gallery.
set -euo pipefail
snapsort_perf_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$snapsort_perf_root"
source tools/android-env.sh
./gradlew --no-daemon :app:testDebugUnitTest --tests '*PerformanceMeasurementTest' --rerun-tasks
printf 'Report: %s\n' "$snapsort_perf_root/app/build/reports/benchmarks/metadata-performance.json"
