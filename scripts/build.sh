#!/bin/sh
# Development build: build the debug APK, install it on the connected phone and restart the shell helper.
#   scripts/build.sh               build, install, restart the helper
#   scripts/build.sh --no-install  build only
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
if ! log=$(./gradlew -q :app:assembleDebug 2>&1); then
    echo "$log" | grep -v "SDK XML\|SDK processing"
    exit 1
fi
echo "$log" | grep -v "SDK XML\|SDK processing" || true
[ "$1" = "--no-install" ] && exit 0
out=$(adb install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -1)
echo "$out"
case "$out" in
    Success*) ;;
    *UPDATE_INCOMPATIBLE*)
        echo "The copy on the phone is signed with a different key. Uninstall it first:"
        echo "  adb uninstall dev.hyperdesk"
        echo "(This also forgets HyperDesk's Wireless debugging pairing; pair again in the app.)"
        exit 1 ;;
    *) exit 1 ;;
esac
./scripts/start-helper.sh
