#!/usr/bin/env bash
# Source this file from any directory before using Gradle, adb, or the emulator.
snapsort_repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$snapsort_repo_root/../.android-sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$snapsort_repo_root/../.android-user}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$ANDROID_USER_HOME/avd}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$snapsort_repo_root/../.gradle}"
if [[ ! -x "${JAVA_HOME:-}/bin/javac" ]] && ! command -v javac >/dev/null; then
  if [[ -x "$snapsort_repo_root/../.android-tooling/jdk-21.0.9+10/bin/javac" ]]; then
    export JAVA_HOME="$snapsort_repo_root/../.android-tooling/jdk-21.0.9+10"
  fi
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/19.0/bin:$PATH"
unset snapsort_repo_root
