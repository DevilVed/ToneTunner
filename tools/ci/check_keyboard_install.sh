#!/usr/bin/env bash
# The voice keyboard must be offered by Android right after the APK is installed, with no reboot:
# after a fresh install, and after uninstalling and reinstalling (as when the signing key changed).
# Runs against the emulator that the Android runtime job has already booted.
set -euo pipefail
apk=app/build/outputs/apk/debug/app-debug.apk
pkg=org.DevilVed.whisperplus
ime="$pkg/com.devilved.tonetunner.WhisperInputMethodService"

listed() { adb shell ime list -a -s | tr -d '\r' | grep -qxF "$ime"; }

wait_listed() {
    for _ in $(seq 1 20); do
        if listed; then return 0; fi
        sleep 1
    done
    return 1
}

fail() {
    echo "::error::$1"
    echo "--- ime list -a -s"; adb shell ime list -a -s || true
    echo "--- dumpsys input_method"; adb shell dumpsys input_method | head -80 || true
    echo "--- pm dump (services)"; adb shell pm dump "$pkg" | grep -A3 -i "InputMethod" | head -40 || true
    exit 1
}

test -f "$apk" || { echo "::error::$apk is missing"; exit 1; }
adb uninstall "$pkg" >/dev/null 2>&1 || true

adb install "$apk"
wait_listed || fail "After a fresh install the keyboard is not in Android's input method list."
echo "Fresh install: keyboard listed."

adb shell ime enable "$ime"
adb shell ime list -s | tr -d '\r' | grep -qxF "$ime" || fail "The keyboard could not be enabled."
echo "Keyboard enabled."

adb uninstall "$pkg"
if listed; then fail "The keyboard is still listed after uninstalling."; fi

adb install "$apk"
wait_listed || fail "After uninstalling and reinstalling, the keyboard is not in Android's input method list."
echo "Reinstall: keyboard listed without a reboot."
