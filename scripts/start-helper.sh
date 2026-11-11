#!/bin/sh
# Starts (or restarts) the HyperDesk shell helper from a computer over adb.
# Normally not needed: the app starts the helper itself through the phone's Wireless debugging.
set -e
PKG=dev.hyperdesk
APK=$(adb shell pm path $PKG | head -1 | cut -d: -f2 | tr -d '\r')
APPUID=$(adb shell pm list packages -U $PKG | grep "package:$PKG " | sed 's/.*uid://' | tr -d '\r')
[ -n "$APK" ] || { echo "HyperDesk is not installed"; exit 1; }
adb shell "killall hyperdesk_shell 2>/dev/null; sleep 0.3; CLASSPATH=$APK setsid nohup app_process /system/bin --nice-name=hyperdesk_shell dev.hyperdesk.shell.ShellServer $APPUID </dev/null >/data/local/tmp/hyperdesk.log 2>&1 &"
sleep 1
adb shell "CLASSPATH=$APK app_process /system/bin dev.hyperdesk.shell.ShellServer --call ping"
