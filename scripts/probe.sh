#!/bin/sh
# Read-only probe of a Xiaomi/HyperOS device's desktop-mode state. Changes nothing on the phone.
# Usage: ./probe.sh > probe-output.txt   (run once without a monitor, once with the dock + monitor attached)
a() { adb shell "$@" 2>&1; }
sec() { printf '\n===== %s =====\n' "$1"; }

sec "build"
for p in ro.product.device ro.product.model ro.build.version.release ro.build.version.sdk \
         ro.mi.os.version.name ro.mi.os.version.incremental ro.build.fingerprint ro.miui.build.region \
         ro.boot.flash.locked ro.boot.verifiedbootstate ro.secureboot.lockstate ro.debuggable \
         persist.sys.miui.pcmode persist.sys.pc.freeform; do
  printf '%s=%s\n' "$p" "$(a getprop $p)"
done

sec "root"
a 'which su; su -c id 2>&1 | head -1; ls /data/adb 2>&1 | head -3'

sec "settings"
for k in "global force_desktop_mode_on_external_displays" "global enable_freeform_support" \
         "global force_resizable_activities" "global enable_non_resizable_multi_window" \
         "global development_settings_enabled" "secure mirror_built_in_display" \
         "global override_desktop_mode_features" "system pc_mode_open"; do
  printf '%s=%s\n' "$k" "$(a settings get $k)"
done
a 'settings list global; settings list secure; settings list system' | grep -iE 'desktop|freeform|mirror|pc_mode|external_disp|connected_disp'

sec "feature flags (desktop / connected display)"
a 'aflags list 2>/dev/null' | grep -iE 'desktop|connected_display|external_display|freeform' | head -80
a 'device_config list 2>/dev/null' | grep -iE 'desktop|connected_display|external_display|freeform' | head -60

sec "system features / config"
a 'pm list features' | grep -iE 'freeform|desktop|pc|activities_on_secondary|managed'
a 'cmd window get-ignore-orientation-request 2>&1 | head -2'

sec "packages"
a 'pm list packages -f' | grep -iE 'pcmode|pccontrol|desktop|miui\.home|miuifreeform|freeform|mirror|milink|taskbar|cast'

sec "platform signature (compare with PC mode APK: c9009d01ebf9...149025)"
a 'dumpsys package android' | grep -iE 'signatures|signing|sharedUser|versionName' | head -12
a 'pm path android'

sec "PC-mode framework remnants"
a 'service list' | grep -iE 'pc|desktop|freeform|miui' | head -60
a 'ls /system/framework /system_ext/framework 2>/dev/null' | grep -iE 'miui|pc' | head -40

sec "displays"
a 'dumpsys display' | grep -E 'mDisplayId=|DisplayDeviceInfo\{|mBaseDisplayInfo|type EXTERNAL|FLAG_|mirror|Mirror|shouldMirror|SecondaryDisplayPolicy|mIsFirstDisplay|canHostTasks' | cut -c1-400 | head -60
a 'dumpsys SurfaceFlinger --displays 2>/dev/null' | head -30
a 'cat /sys/class/typec/port0/data_role /sys/class/typec/port0/power_role 2>&1; ls /sys/class/typec 2>&1'
a 'ls /sys/class/drm 2>&1; for f in /sys/class/drm/*/status; do echo "$f: $(cat $f 2>&1)"; done'

sec "window manager / desktop state"
a 'dumpsys window displays' | grep -iE 'Display: mDisplayId|windowingMode|mDisplayWindowingMode|desktop|ShouldShowSystemDecors|imePolicy|isHomeSupported' | head -40
a 'dumpsys activity service SystemUIService WMShell 2>/dev/null' | grep -iE 'desktop|DesktopMode|canEnter|isDesktopModeSupported' | head -40
a 'cmd window shell desktopmode 2>&1 | head -20'
