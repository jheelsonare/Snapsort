#!/usr/bin/env bash
# Start an already installed API35 AOSP image without hardware acceleration.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/android-env.sh"
image_dir="$ANDROID_HOME/system-images/android-35/default/x86_64"
[[ -f "$image_dir/system.img" ]] || { echo "Install SDK system-images;android-35;default;x86_64 and emulator first." >&2; exit 1; }
mkdir -p "$ANDROID_AVD_HOME/SnapSort_API35.avd"
printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-35\n' "$ANDROID_AVD_HOME/SnapSort_API35.avd" > "$ANDROID_AVD_HOME/SnapSort_API35.ini"
cat > "$ANDROID_AVD_HOME/SnapSort_API35.avd/config.ini" <<CONFIG
AvdId=SnapSort_API35
PlayStore.enabled=false
abi.type=x86_64
avd.ini.encoding=UTF-8
avd.ini.displayname=SnapSort API 35
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.device.name=pixel_6
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.keyboard=yes
hw.lcd.width=540
hw.lcd.height=960
hw.lcd.density=240
hw.ramSize=2048
disk.dataPartition.size=4G
image.sysdir.1=$image_dir
showDeviceFrame=no
skin.dynamic=yes
skin.name=540x960
skin.path=_no_skin
CONFIG
# Emulator 37 requires explicit opt-in for its supported slow software CPU mode.
export ANDROID_I_WANT_MY_TCG=yes
exec emulator -avd SnapSort_API35 -accel off -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -memory 2048 -cores 2 "$@"
