#!/usr/bin/env bash
# Put the device on the watch face, and confirm it got there.
#
# Worth a script because the obvious keys do the opposite of what you expect and
# the failure is silent: KEYCODE_HOME pressed *on the watch face* opens the app
# launcher, and a tap aimed at the face then lands on an app icon. Several
# measurements in this project were quietly taken against the launcher or the
# Contacts app before anyone noticed.
set -euo pipefail
cd "$(dirname "$0")"
. ./env.sh
DEVICE="${1:-${ANDROID_SERIAL:-}}"
adb() { command adb ${DEVICE:+-s "$DEVICE"} "$@"; }

adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 1
for _ in 1 2 3 4 5 6; do
    focus=$(adb shell dumpsys window 2>/dev/null | grep -o 'mCurrentFocus=.*' | head -1)
    # The watch face itself is SysUiActivity; the launcher and any app are not.
    case "$focus" in
        *SysUiActivity*)   echo "on the watch face"; exit 0 ;;
        # Real hardware sleeps while you are typing the next command. An AOD
        # overlay means the screen is off, not that we are in the wrong app.
        *Aod*|*Keyguard*)  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 ;;
        *AllAppsLauncher*) adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 ;;
        *)                 adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 ;;
    esac
    sleep 2
done
echo "could not reach the watch face; last focus: ${focus:-unknown}" >&2
exit 1
