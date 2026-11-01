#!/usr/bin/env bash
# Builds Axis and the stand-in WhatsApp, installs both on the connected device (or boots the emulator), and runs e2e.py.
set -euo pipefail
cd "$(dirname "$0")/.."
T=${AXIS_TOOLCHAIN:-$HOME/.local/share/axis-toolchain}
export JAVA_HOME=$T/jdk21 ANDROID_SDK_ROOT=$T/android-sdk ANDROID_HOME=$T/android-sdk ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}
export ADB=$ANDROID_SDK_ROOT/platform-tools/adb
GRADLE=${GRADLE:-$T/gradle/gradle-9.8.2/bin/gradle}

$GRADLE --no-daemon :app:assembleDebug :testapps:fakewa:assembleDebug -q

STARTED_EMULATOR=0
trap '[ "$STARTED_EMULATOR" = 1 ] && $ADB emu kill >/dev/null 2>&1 || true' EXIT
if ! $ADB devices | awk 'NR>1 && $2=="device"' | grep -q .; then
  echo "No device attached: booting the emulator"
  STARTED_EMULATOR=1
  nohup $ANDROID_SDK_ROOT/emulator/emulator -avd axis_test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -memory 2048 -accel on >/dev/null 2>&1 &
  $ADB wait-for-device
  until [ "$($ADB shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 5; done; sleep 15
fi

$ADB uninstall app.axis >/dev/null 2>&1 || true
$ADB uninstall com.whatsapp >/dev/null 2>&1 || true
$ADB install app/build/outputs/apk/debug/app-debug.apk
$ADB install testapps/fakewa/build/outputs/apk/debug/fakewa-debug.apk
python3 e2e/e2e.py
