# Changelog

All notable changes to HyperDesk. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow [Semantic Versioning](https://semver.org/).

Each `vX.Y.Z` tag publishes a GitHub Release whose notes are the matching section below.

## [Unreleased]

## [0.1.1] - 2026-11-25

### Added
- HyperOS 4 / Android 17 support. Android 17 keeps a connected monitor in mirroring mode, where apps cannot open;
  HyperDesk now runs the desktop on a display of its own and shows it full-screen on the monitor. The mouse and
  keyboard follow automatically.

### Fixed
- Brief glitches on both screens: HyperDesk re-applied the layout of its resize handles every 2.5 seconds even when
  nothing had moved, which made HyperOS re-check the displays and reset the phone's refresh rate each time.
- After a system update forgets HyperDesk's Wireless debugging pairing, HyperDesk now says so and offers to pair
  again, instead of showing an SSL error.

## [0.1.0] - 2026-11-11

First public release. Tested on a Xiaomi 15 Ultra (HyperOS 3.0, Android 16); see ROADMAP.md for which features
have been verified on a device.

### Added
- Desktop on an external monitor: apps open as real, resizable desktop windows instead of Xiaomi's phone-shaped
  floating windows, with title bars, shadows and a wallpaper.
- Window management: drag by the title bar, resize from the edges, double-click to maximize, snap to halves and
  quarters by dragging to an edge or corner, and a window menu (right-click the title bar) with halves, thirds,
  two-thirds, full screen and "New window".
- Exclusive full screen for games and remote-desktop apps (Meta+F or F11).
- Taskbar with pinned and running apps, window previews on hover, tooltips, and middle-click to close.
- Start menu with search across apps, phone settings, sums and the web.
- Quick settings: Wi-Fi, Bluetooth, volume, media controls, phone screen, screenshot, and notifications.
- The phone's notifications on the monitor.
- Keyboard shortcuts: Alt+Tab with previews, Meta for the start menu, Meta+arrows, Meta+1…9, Meta+D, Meta+A,
  Meta+Shift+S and Alt+F4.
- Phone as touchpad and keyboard when no mouse is attached.
- Interface size for the monitor (85–150%), four wallpapers or your own photo, seven accent colours, and a
  centred or left-aligned taskbar.
- Optional do-not-disturb and phone screen off while docked.
- Setup guide that sets everything up on the phone alone, through Android's Wireless debugging: no computer and
  no root. The pairing code can be typed straight into a notification.
- The mouse pointer stays on the monitor while it is connected.

[Unreleased]: https://github.com/3ig/hyperdesk/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/3ig/hyperdesk/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/3ig/hyperdesk/releases/tag/v0.1.0
