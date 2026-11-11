#!/bin/sh
# Kill switch: stops the shell helper and turns the HyperDesk taskbar service off.
# The taskbar, window frames and shortcuts disappear; nothing is uninstalled.
adb shell 'killall hyperdesk_shell 2>/dev/null
cur=$(settings get secure enabled_accessibility_services)
new=$(echo "$cur" | tr ":" "\n" | grep -v "^dev.hyperdesk/" | paste -sd: -)
settings put secure enabled_accessibility_services "$new"
settings delete secure disable_decoration_dot >/dev/null
am force-stop dev.hyperdesk'
echo "HyperDesk stopped. Start it again from the app (Settings → Finish setup) or with scripts/build.sh."
