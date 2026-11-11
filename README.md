# HyperDesk

A desktop for an external monitor on Xiaomi phones running HyperOS, without root.

Xiaomi dropped PC mode from its phones and HyperOS keeps Android's own desktop mode switched off. HyperDesk turns
it back on and builds a desktop around it: real resizable windows, a taskbar, a start menu and keyboard
shortcuts. Plug the phone into a USB-C dock or monitor and the desktop appears there; the phone keeps working as a
phone, or becomes the touchpad and keyboard.

> **Early release.** Developed and tested on one phone (Xiaomi 15 Ultra, HyperOS 3.0, Android 16) with a USB-C
> dock and an ultrawide monitor. Some features are built but not yet verified on a device; [ROADMAP.md](ROADMAP.md)
> says which. HyperDesk is not affiliated with Xiaomi.

## Features

- **Windows**: every app on the monitor is a real desktop window at its true size, with a title bar, shadow and
  border. Drag by the title bar, resize from the edges, double-click to maximize, drag to an edge or corner to
  snap. Right-click a title bar for halves, thirds and two-thirds layouts, full screen, or a new window.
- **Exclusive full screen** for games and remote-desktop apps (Meta+F or F11).
- **Taskbar** with pinned and running apps, window previews on hover, clock and battery.
- **Start menu** with search across apps, phone settings, sums and the web.
- **Quick settings**: Wi-Fi, Bluetooth, volume, media controls, phone screen, screenshot, and your notifications.
- **Notifications** from the phone appear on the monitor.
- **Keyboard shortcuts**: Alt+Tab with previews, Meta for the start menu, Meta+arrows to snap, Meta+1…9 for
  taskbar apps, Alt+F4 to close.
- **Phone as touchpad and keyboard** when no mouse is attached.
- **Interface size** for the monitor (85–150%), wallpapers (or your own photo) and accent colours.
- **Set up on the phone alone**: no computer and no root.

## Requirements

- A Xiaomi phone on HyperOS 3 (Android 16) that outputs video over USB-C (DisplayPort Alt Mode). Flagships do;
  most Redmi and POCO phones do not.
- A monitor, connected directly by USB-C or through a dock.
- Wi-Fi during setup and once after each restart of the phone (Android's Wireless debugging only runs on Wi-Fi).
- On Xiaomi phones, **USB debugging (Security settings)** in Developer options, which needs a Mi account. The
  setup guide only asks for it if the phone refuses HyperDesk's permission.

Other HyperOS versions and other Android phones may work but are untested.

## Install

1. Download the latest `hyperdesk-X.Y.Z.apk` from [Releases](../../releases) on the phone and open it.
   Allow installs from your browser or file manager when Android asks.
2. Open **HyperDesk** and follow the setup guide:
   1. Unlock Developer options and switch on **Wireless debugging** (the guide has buttons for each page).
   2. Tap **Start pairing**, open *Wireless debugging → Pair device with pairing code*, and type the six-digit
      code into the HyperDesk notification.
   3. HyperDesk finishes the rest by itself.
3. Connect the monitor.

To keep HyperDesk from being closed by HyperOS, lock it in the recent-apps screen and set its battery use to
**No restrictions** (the last step of the guide has a button for this).

## What HyperDesk changes on your phone

HyperDesk works by switching documented Android and HyperOS settings, mostly through Android's own debugging
connection. It does not modify the system.

| Change | When | Undone |
| --- | --- | --- |
| Developer options: *Force desktop mode*, *Enable freeform windows*, *Force activities to be resizable* | Setup | Turn them off in Developer options |
| HyperDesk's accessibility service (draws the desktop, tracks windows, handles shortcuts) | Setup | Settings → Accessibility, or uninstall |
| Notification access (to show notifications on the monitor) | Setup | Settings → Notification access, or uninstall |
| Battery optimisation exemption | Setup | Uninstall |
| Xiaomi's window "dots" (`disable_decoration_dot`) turned off | While a monitor is connected | On disconnect |
| Android's on-screen keyboard kept off the monitor | While a monitor is connected | On disconnect |
| Monitor density (Interface size) | When you change it | Set Interface size to 100% |
| Gesture navigation with the gesture line hidden | Only while a window is in full screen | On leaving full screen |
| Do not disturb | Only if you switch on "Do not disturb while docked" | On disconnect |

The accessibility service does not read or store what is on screen. HyperDesk has no analytics and sends nothing
anywhere; its internet permission is used only to talk to the phone's own Wireless debugging on the phone itself.
To be able to do that, HyperDesk keeps a debugging key on the phone; uninstalling HyperDesk deletes it, and you can
also revoke it under *Wireless debugging → Paired devices*.

## Shortcuts

| Keys | Action |
| --- | --- |
| Meta | Start menu |
| Alt+Tab | Switch windows |
| Meta+← / → | Snap left / right |
| Meta+↑ / ↓ | Maximize or restore / minimize |
| Meta+F or F11 | Full screen |
| Meta+D | Show the desktop |
| Meta+A | Quick settings |
| Meta+1 … 9 | Taskbar apps |
| Meta+Shift+S | Screenshot of the monitor |
| Alt+F4 | Close the window |

## Troubleshooting

- **The desktop disappeared from the monitor**: open HyperDesk. If HyperOS closed it, the app restarts its
  service, and the settings screen shows what is not running.
- **Windows cannot be moved after restarting the phone**: connect to Wi-Fi. HyperDesk restarts its window helper
  through Wireless debugging, which needs Wi-Fi.
- **"The phone refused the permission"**: switch on *USB debugging (Security settings)* in Developer options, then
  tap *Try again*.
- **Installing a release over a build you made yourself fails**: the two are signed with different keys. Uninstall
  HyperDesk first; you will need to pair again.

## How it works

- `DeskService` (an accessibility service) draws the taskbar, menus and window frames as overlays on the monitor
  and follows which windows are open.
- `ShellServer` is a small helper that runs with the shell user's rights, started from HyperDesk's own APK through
  `app_process`. It moves, resizes, raises and closes windows, keeps the mouse on the monitor, and provides the
  virtual mouse for the touchpad. It talks to the app over a Binder handed to it through a content provider.
- `Adb.kt` is an ADB client that connects to the phone's own Wireless debugging (the approach
  [Shizuku](https://github.com/RikkaApps/Shizuku) uses). It grants HyperDesk its permission during setup and starts
  the helper, so no computer is needed.

## Build

Needs JDK 17 and the Android SDK.

```bash
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
scripts/build.sh                 # build, install on a connected phone and restart the helper
```

Development scripts in `scripts/` (all use `adb`):

| Script | Purpose |
| --- | --- |
| `build.sh` | Build, install and restart the helper |
| `start-helper.sh` / `stop.sh` | Start the helper from a computer / turn HyperDesk off |
| `hd` | Send one request to the helper, e.g. `scripts/hd tasks -1` |
| `dbg` | Trigger keyboard- and mouse-only features, e.g. `scripts/dbg switch` |
| `probe.sh` | Read-only dump of the phone's desktop-mode state |
| `desktop-mode.sh` | Switch the desktop-mode flags by hand |

Releases are built and signed by GitHub Actions from version tags; see [docs/RELEASE.md](docs/RELEASE.md).

## Credits

- [libadb-android](https://github.com/MuntashirAkon/libadb-android) by Muntashir Al-Islam, for the on-device ADB
  client, with [sun-security-android](https://github.com/MuntashirAkon/sun-security-android) and
  [Conscrypt](https://github.com/google/conscrypt).
- [HyperDesktopUnlock](https://github.com/the-marox/HyperDesktopUnlock), an LSPosed module for HyperOS 4 that showed
  where Xiaomi switches desktop mode off.
