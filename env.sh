# Source this before any gradle/adb/emulator work:  . ./env.sh
#
# Nothing here is installed by default on macOS. temurin's cask is a .pkg that
# needs sudo, so the JDK comes from the keg-only openjdk@21 formula instead —
# same JDK, no password, and JAVA_HOME points straight at it.
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
