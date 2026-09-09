#!/usr/bin/env bash
# Build, install and activate the watch face with both complications bound.
#
# This exists because none of the steps below are guessable and each wrong guess
# fails with a message that points somewhere else — see
# spikes/PHASE0-FINDINGS.md, W5. Getting it wrong by hand costs an hour; getting
# it wrong here costs one run.
set -euo pipefail
cd "$(dirname "$0")"
. ./env.sh

# Target one device explicitly. With an emulator and a real watch both attached,
# a bare `adb` refuses to act ("more than one device") and every step below
# fails in a way that looks like a build problem.
#
#   ./run.sh                       # the only attached device
#   ./run.sh adb-XXXXXXXX-YYYYYY   # a named one, from `adb devices`
#   ANDROID_SERIAL=... ./run.sh    # or via the environment
DEVICE="${1:-${ANDROID_SERIAL:-}}"
if [ -z "$DEVICE" ]; then
    COUNT=$(adb devices | grep -c "\sdevice$" || true)
    if [ "$COUNT" -gt 1 ]; then
        echo "More than one device attached; name one:" >&2
        adb devices | grep "\sdevice$" | sed 's/^/  /' >&2
        echo "  ./run.sh <serial>" >&2
        exit 1
    fi
fi
adb() { command adb ${DEVICE:+-s "$DEVICE"} "$@"; }

echo "target: ${DEVICE:-$(command adb devices | grep "\sdevice$" | awk '{print $1}')}"

APP=com.lukemeyer.bif.app
WF=com.lukemeyer.bif.watchface
DBG="am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE"

./gradlew :app:assembleDebug :watchface:assembleDebug -q
adb install -r -g app/build/outputs/apk/debug/app-debug.apk > /dev/null
adb install -r -g watchface/build/outputs/apk/debug/watchface-debug.apk > /dev/null
echo "installed"

adb logcat -c
# Note: --es watchFaceId. Every --ecn component form fails with
# "Watch face package is not installed", which is not what is wrong.
adb shell $DBG --es operation set-watchface --es watchFaceId $WF | grep -o 'data=.*'
sleep 4

# Slot ids are the RUNTIME's, not the slotId attributes in watchface.xml
# (1 and 2 there are 11 and 12 here). Read them rather than hardcoding.
# Match any slot line, not just NO_DATA: once the slots have data bound from a
# previous run the runtime stops logging NO_DATA, and a NO_DATA-only grep then
# finds nothing and takes the whole script down under `set -e`.
# (no mapfile here: macOS ships bash 3.2)
SLOTS=$(adb logcat -d 2>/dev/null \
    | grep -oE "WearComplicationProvider: \[[0-9]+:" \
    | grep -oE "[0-9]+" | sort -un | tr '\n' ' ' || true)
FRAME_SLOT=$(echo $SLOTS | cut -d' ' -f1)
SUB_SLOT=$(echo $SLOTS | cut -d' ' -f2)

if [ -z "$FRAME_SLOT" ] || [ -z "$SUB_SLOT" ]; then
    echo "could not read slot ids from logcat; got: ${SLOTS:-none}" >&2
    exit 1
fi
echo "slots: $FRAME_SLOT (frame) $SUB_SLOT (subtitle)"

# Types are ints, and they are the LEGACY values: LARGE_IMAGE=8, LONG_TEXT=4.
adb shell $DBG --es operation set-complication \
    --ecn component $APP/$APP.FrameComplicationService \
    --es watchFaceId $WF --ei slot "$FRAME_SLOT" --ei type 8 | grep -o 'data=.*'
adb shell $DBG --es operation set-complication \
    --ecn component $APP/$APP.SubtitleComplicationService \
    --es watchFaceId $WF --ei slot "$SUB_SLOT" --ei type 4 | grep -o 'data=.*'

sleep 4
mkdir -p shots
adb exec-out screencap -p > shots/latest.png
echo "shots/latest.png"
