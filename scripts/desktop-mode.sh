#!/bin/sh
# Stock Android desktop mode on external displays for HyperOS 3 (Android 16). No root needed.
# Usage:
#   ./desktop-mode.sh on        enable freeform desktop on external displays
#   ./desktop-mode.sh off       restore the phone's original values
#   ./desktop-mode.sh sim       add a simulated 1080p external display (for testing without a monitor)
#   ./desktop-mode.sh sim-off   remove the simulated display
#   ./desktop-mode.sh status
#   ./desktop-mode.sh launch <display-id> <package/activity>   open an app as a window on that display
set -e
s() { adb shell settings "$@"; }

case "$1" in
  on)
    s put global force_desktop_mode_on_external_displays 1
    s put global enable_freeform_support 1
    s put global force_resizable_activities 1
    echo "Enabled. Connect the monitor (or run: $0 sim)."
    ;;
  off)
    # Values read from the phone before anything was changed.
    s put global force_desktop_mode_on_external_displays 0
    s put global enable_freeform_support 0
    s put global force_resizable_activities 0
    s delete global overlay_display_devices >/dev/null
    echo "Restored original values."
    ;;
  sim)
    s put global overlay_display_devices "1920x1080/160,should_show_system_decorations"
    ;;
  sim-off)
    s delete global overlay_display_devices
    ;;
  status)
    for k in force_desktop_mode_on_external_displays enable_freeform_support force_resizable_activities overlay_display_devices; do
      printf '%s=%s\n' "$k" "$(s get global $k)"
    done
    printf 'mirror_built_in_display=%s\n' "$(s get secure mirror_built_in_display)"
    adb shell 'dumpsys activity activities' | grep -E '^Display #|^  \* Task\{' | cut -c1-140 | awk '/^Display/{d=$0; print; n=0; next} n<6{print; n++}'
    ;;
  launch)
    adb shell am start --display "$2" --windowingMode 5 -n "$3"
    ;;
  *)
    sed -n '2,9p' "$0"; exit 1
    ;;
esac
