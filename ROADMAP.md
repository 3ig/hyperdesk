# HyperDesk roadmap

A desktop for an external monitor on Xiaomi HyperOS, without root.

Status key: **Done** = built and seen working · **Built** = implemented, not yet verified on the device ·
**Next** = being worked on · **Planned** = not started.

## Foundations

| Item | Status | Notes |
| --- | --- | --- |
| Monitor as a separate desktop (no mirroring) | Done | Three developer flags, switched on by the app (or by `scripts/desktop-mode.sh`). |
| Shell helper for window control | Done | `ShellServer`, started by the app over Wireless debugging (or by `scripts/start-helper.sh`). |
| Start the helper without a computer | Done | Verified: with the Mac not involved, the phone started the helper itself over Wireless debugging. Not yet tried straight after a reboot. |
| Setup without a computer | Done | After pairing, HyperDesk grants its own permission and finishes setup; verified by revoking the permission and letting the guide restore everything. Needs Wi-Fi, and on Xiaomi "USB debugging (Security settings)". |
| Pairing code typed into a notification | Built | Needs a real pairing dialog to test. |
| Recover when HyperOS kills the app | Built | Service is re-bound automatically; settings show the live state. |
| Phone navigation bar repair | Built | Helper command `fixnav`; the manual version of the fix worked. |

## Window management

| Item | Status | Notes |
| --- | --- | --- |
| Real desktop windows (no Xiaomi floating frame, dots or scaling) | Done | Multi-window mode + Xiaomi's `disable_decoration_dot` while docked. |
| Title bar, shadow, border on every window; wallpaper | Done | One overlay layer (`StageView`). |
| Drag by any title bar, snapshot follows the pointer | Done | Verified in recordings with a scripted drag; needs a real-mouse check. |
| Correct stacking, click-to-raise, new windows open in front | Done | Uses the system's true stacking order. |
| Maximize / restore with a covering transition | Done | |
| Minimize, close, with animations | Built | |
| Resize by edges and corners | Built | Outline while dragging, applied on release. |
| Snap to halves and quarters by dragging to edges and corners | Built | |
| Snap layouts: halves, thirds, two-thirds | Built | In the window menu (right-click a title bar). Untested by hand. |
| Exclusive full screen for games and remote-desktop apps | Done | Meta+F, F11 or the window menu; leave with the same keys or the top edge. Hides the system navigation buttons by switching the phone to gesture navigation while active. |
| Multiple windows per app ("New window") | Built | In the window and taskbar menus; depends on the app allowing a second window. Untested. |
| Per-app memory: size, position and "opens maximized" | Built | Layout restore on re-dock is still planned. |
| Virtual desktops and an overview screen | Planned | |
| Tidy handling of apps that refuse to resize | Planned | |

## Shell

| Item | Status | Notes |
| --- | --- | --- |
| Taskbar: pinned and running apps, clock, battery | Done | |
| Start menu with search | Done | |
| Quick settings: Wi-Fi, Bluetooth, volume, media, phone screen, screenshot | Done | Seen on the monitor; the toggles themselves are not yet exercised. |
| Alt+Tab, Meta shortcuts | Built | Cannot be driven over adb; needs a keyboard check. |
| Taskbar tooltips, middle-click close, stepping through an app's windows | Built | |
| Wallpaper picker | Built | Four built-in wallpapers. |
| Notifications on the monitor | Done | Card in the top right and a list in quick settings, both seen working. Click-to-open and dismiss are untested. |
| Window previews in Alt+Tab and on taskbar hover | Built | Needs a real keyboard and mouse to check. |
| Interface size (monitor scaling) | Done | 85–150%. Fixed a rebuild loop at 100%; density verified at every step. |
| Phone as touchpad and keyboard | Done | Confirmed working by hand. Typing non-Latin text (pasted rather than typed) is untested. |
| Phone keyboard on demand when a monitor text field is picked | Built | Off by default (the touchpad has a Keyboard button). Untested. |
| Desktop right-click menu and desktop icons | Planned | The wallpaper is click-through today. |
| Universal search (apps, settings, calculator, web) | Built | Files are not searched. Untested by hand. |
| Clipboard history | Planned | Android does not let a background app read the clipboard; needs a workaround. |
| Region screenshot and screen recording | Planned | |
| Night light | Planned | |

## Phone and dock

| Item | Status | Notes |
| --- | --- | --- |
| Mouse pointer kept on the monitor | Done | |
| Monitor stays awake while docked | Built | |
| Turn the phone screen off while docked | Built | Experimental. |
| Dock profile: do-not-disturb while docked, phone screen off | Built | Both optional, in settings. Untested. |
| Send a window to the phone / pull it to the monitor | Planned | "Move to phone screen" exists in the taskbar menu, untested. |
| Incoming call banner on the monitor | Planned | |

## Polish

| Item | Status | Notes |
| --- | --- | --- |
| Entrance animations for panels, hover feedback | Done | |
| Wallpaper from your own photo | Built | Settings → Look. Untested. |
| Accent colour choice | Built | Seven colours. A light theme is still planned. |
| Taskbar alignment (centre or left) | Built | Auto-hide and reordering pinned apps are still planned. |
| Open and maximize animations | Planned | |
| First-run setup guide | Done | Four steps, all on the phone. Seen in the already-set-up state and through the automatic finish; the unpaired states are unseen. |

## Known limits

- Anything HyperDesk does not know about (a system dialog, an on-screen keyboard on the monitor) can be hidden behind the wallpaper layer.
- Xiaomi's own desktop mode exists in the firmware but is locked behind a read-only property; it needs root.
- A second external monitor and per-window audio routing depend on HyperOS and are untested.
- The on-device ADB library (libadb-android) has not had a security audit, and HyperDesk keeps a debugging key on the phone.
