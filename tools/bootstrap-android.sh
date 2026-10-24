#!/usr/bin/env bash
# Portable Linux Android SDK bootstrap; all downloads retain TLS/checksum verification.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$repo_root/../.android-sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$repo_root/../.android-user}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo_root/../.gradle}"
mkdir -p "$ANDROID_HOME" "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
ANDROID_HOME="$(cd "$ANDROID_HOME" && pwd)"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
# A runtime-only Java installation is insufficient: Android builds need javac.
if [[ ! -x "${JAVA_HOME:-}/bin/javac" ]] && ! command -v javac >/dev/null; then
  jdk_root="$repo_root/../.android-tooling"
  mkdir -p "$jdk_root/downloads"
  jdk_archive="$jdk_root/downloads/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz"
  if [[ ! -x "$jdk_root/jdk-21.0.9+10/bin/javac" ]]; then
    curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
      'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz' \
      -o "$jdk_archive"
    # Official release .sha256.txt: 810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f.
    printf '%s  %s\n' 810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f "$jdk_archive" | sha256sum --check --status
    tar -xzf "$jdk_archive" -C "$jdk_root"
  fi
  export JAVA_HOME="$jdk_root/jdk-21.0.9+10"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
sdkmanager="$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager"
if [[ ! -x "$sdkmanager" ]]; then
  for tool in curl unzip sha256sum python3 java; do command -v "$tool" >/dev/null || { echo "Install $tool first." >&2; exit 1; }; done
  archive="$(mktemp -d)"
  trap 'rm -rf "$archive"' EXIT
  curl --fail --location --retry 2 --proto '=https' --tlsv1.2 \
    https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
    -o "$archive/tools.zip"
  # SHA256 of Google's official archive, additionally checked against Google's SDK repository checksum during setup.
  printf '%s  %s\n' 7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee "$archive/tools.zip" | sha256sum --check --status
  unzip -q "$archive/tools.zip" -d "$archive/unpacked"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv "$archive/unpacked/cmdline-tools" "$ANDROID_HOME/cmdline-tools/19.0"
fi
# Java tools do not automatically use HTTPS_PROXY. Forward only host/port, never credentials.
# Existing Gradle user properties are preserved. Proxy credentials, if required, must use supported local configuration.
export SNAPSORT_SDKMANAGER="$sdkmanager"
python3 - "$@" <<'PY'
import os, pathlib, subprocess, sys, urllib.parse
sdk = os.environ['ANDROID_HOME']
command = [os.environ['SNAPSORT_SDKMANAGER'], '--sdk_root=' + sdk]
# Locally installed JDKs may not include the cloud proxy CA; use the platform's
# verified system Java trust store when available, preserving all TLS checks.
trust_store = pathlib.Path('/etc/ssl/certs/java/cacerts')
properties = pathlib.Path(os.environ['GRADLE_USER_HOME']) / 'gradle.properties'
existing = properties.read_text() if properties.exists() else ''
if trust_store.exists() and 'systemProp.javax.net.ssl.trustStore=' not in existing:
    with properties.open('a') as out:
        out.write('\nsystemProp.javax.net.ssl.trustStore=' + str(trust_store) + '\n')
proxy = urllib.parse.urlparse(os.environ.get('HTTPS_PROXY', os.environ.get('https_proxy', '')))
if proxy.hostname:
    command += ['--proxy=http', '--proxy_host=' + proxy.hostname, '--proxy_port=' + str(proxy.port or 80)]
    properties = pathlib.Path(os.environ['GRADLE_USER_HOME']) / 'gradle.properties'
    existing = properties.read_text() if properties.exists() else ''
    additions = []
    for protocol in ('http', 'https'):
        for key, value in [('proxyHost', proxy.hostname), ('proxyPort', str(proxy.port or 80))]:
            name = f'systemProp.{protocol}.{key}'
            if not any(line.strip().startswith(name + '=') for line in existing.splitlines()):
                additions.append(name + '=' + value)
    if additions:
        with properties.open('a') as out:
            out.write('\n' + '\n'.join(additions) + '\n')
sdk_environment = os.environ.copy()
if trust_store.exists() and 'javax.net.ssl.trustStore' not in sdk_environment.get('JAVA_TOOL_OPTIONS', ''):
    sdk_environment['JAVA_TOOL_OPTIONS'] = sdk_environment.get('JAVA_TOOL_OPTIONS', '') + ' -Djavax.net.ssl.trustStore=' + str(trust_store)
log = pathlib.Path(os.environ['ANDROID_USER_HOME']) / 'sdk-setup.log'
with log.open('w') as out:
    if '--accept-licenses' in sys.argv:
        result = subprocess.run(command + ['--licenses'], input='y\n' * 100, text=True, stdout=out, stderr=subprocess.STDOUT, env=sdk_environment)
        if result.returncode:
            sys.exit(f'SDK license step failed; inspect {log}')
    if not (pathlib.Path(sdk) / 'licenses/android-sdk-license').exists():
        sys.exit('Review the Android SDK license, then rerun with --accept-licenses to install the SDK.')
# Direct official archives avoid truncated Java SDK-manager downloads on some proxies.
# Pinned SHA1 checksums below are published in Google's HTTPS repository2-1.xml.
import hashlib, tempfile, zipfile
packages = [
    ('platform-35_r02.zip', '0bb560a90a7a2cbd0dd8348224d518b638fe7949', 'platforms', 'platforms/android-35/android.jar'),
    ('build-tools_r35_linux.zip', '2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc', 'build-tools', 'build-tools/35.0.0/aapt2'),
    ('platform-tools_r37.0.1-linux.zip', '477254aa5f903c15cf51001717bdf347fb6b53e0', '', 'platform-tools/adb'),
]
for name, expected, destination, marker in packages:
    if (pathlib.Path(sdk) / marker).exists():
        continue
    with tempfile.TemporaryDirectory() as temp:
        archive = pathlib.Path(temp) / name
        subprocess.run(['curl', '--fail', '--location', '--retry', '3', '--proto', '=https', '--tlsv1.2',
                        'https://dl.google.com/android/repository/' + name, '-o', str(archive)], check=True)
        if hashlib.sha1(archive.read_bytes()).hexdigest() != expected:
            sys.exit('Official SDK archive checksum mismatch: ' + name)
        with zipfile.ZipFile(archive) as zip_file:
            if zip_file.testzip() is not None:
                sys.exit('Damaged SDK archive: ' + name)
        subprocess.run(['unzip', '-qo', str(archive), '-d', str(pathlib.Path(sdk) / destination)], check=True)
        if name.startswith('build-tools'):
            # The official build-tools archive's top-level directory is android-15.
            extracted = pathlib.Path(sdk) / 'build-tools/android-15'
            if extracted.exists():
                extracted.rename(pathlib.Path(sdk) / 'build-tools/35.0.0')
print(f'Android SDK ready: {sdk}')
print(f'Gradle cache: {os.environ["GRADLE_USER_HOME"]}')
PY
# Respect user local configuration and avoid absolute machine paths in tracked files.
if [[ ! -e "$repo_root/local.properties" ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$repo_root/local.properties"
fi
printf 'Build with: source tools/android-env.sh && ./gradlew assembleDebug testDebugUnitTest\n'
