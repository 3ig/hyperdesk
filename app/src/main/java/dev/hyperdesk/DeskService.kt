package dev.hyperdesk

import android.accessibilityservice.AccessibilityService
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.hyperdesk.shell.ShellClient

class DeskService : AccessibilityService(), DisplayManager.DisplayListener {
    companion object {
        @Volatile var instance: DeskService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var dm: DisplayManager
    var desk: Desk? = null
        private set

    private val packages = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            Apps.load(this@DeskService, changed = i.data?.schemeSpecificPart) { handler.post { desk?.refresh() } }
        }
    }

    private val onHelper: () -> Unit = { desk?.onHelperConnected() }
    private val refresh = Runnable { desk?.refresh() }
    private val tick = object : Runnable {
        override fun run() {
            desk?.refresh()
            // No helper yet (fresh boot, or it was killed): start it through Wireless debugging, quietly.
            if (desk != null && !ShellClient.connected) Adb.start(this@DeskService, quiet = true)
            handler.postDelayed(this, 2500)
        }
    }

    override fun onServiceConnected() {
        instance = this
        Prefs.init(this)
        Apps.load(this)
        dm = getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(this, handler)
        // Keep the start menu's app list current as apps are installed, updated and removed.
        registerReceiver(packages, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_CHANGED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        ShellClient.onConnected.add(onHelper)
        syncDisplays()
    }

    override fun onDestroy() {
        instance = null
        ShellClient.onConnected.remove(onHelper)
        handler.removeCallbacksAndMessages(null)
        try { dm.unregisterDisplayListener(this) } catch (_: Exception) {}
        try { unregisterReceiver(packages) } catch (_: Exception) {}
        desk?.stop()
        desk = null
        super.onDestroy()
    }

    private fun isDesktopDisplay(d: Display) =
        d.displayId != Display.DEFAULT_DISPLAY && d.flags and Display.FLAG_PRESENTATION != 0 && d.flags and Display.FLAG_PRIVATE == 0

    fun syncDisplays() {
        val target = dm.displays.firstOrNull(::isDesktopDisplay)
        val current = desk
        if (current?.id == target?.displayId) return
        current?.stop()
        handler.removeCallbacks(tick)
        desk = null
        if (target != null) {
            try {
                desk = Desk(this, target).also { it.start() }
                handler.postDelayed(tick, 2500)
            } catch (e: Exception) {
                android.util.Log.e("HyperDesk", "failed to start desk", e)
            }
        }
    }

    /** A click just landed somewhere on the monitor: re-read the window stack now and a few frames later. */
    fun burstRefresh() {
        desk?.refresh()
        for (d in longArrayOf(35, 90, 180)) handler.postDelayed({ desk?.refresh() }, d)
    }

    /** Paste [text] into the focused text field on the given display (for characters that have no key). */
    fun pasteOn(displayId: Int, text: String) {
        try {
            val node = (windowsOnAllDisplays.get(displayId) ?: emptyList()).firstNotNullOfOrNull {
                it.root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            } ?: return
            val clip = getSystemService(android.content.ClipboardManager::class.java)
            clip.setPrimaryClip(android.content.ClipData.newPlainText("", text))
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
        }
    }

    /** Tear the desk down and build it again (after the monitor's density changed). */
    fun restartDesk() {
        desk?.stop()
        handler.removeCallbacks(tick)
        desk = null
        syncDisplays()
    }

    fun scheduleRefresh(delay: Long = 30) {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, delay)
    }

    override fun onDisplayAdded(displayId: Int) = syncDisplays()
    override fun onDisplayRemoved(displayId: Int) = syncDisplays()
    override fun onDisplayChanged(displayId: Int) {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val d = desk ?: return
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // A text field picked on the monitor: bring up the phone's keyboard if there is no real one.
                if (event.displayId != d.id || event.packageName == packageName) return
                val editable = try { event.source?.isEditable == true } catch (_: Exception) { false }
                d.onFieldFocus(editable, clicked = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED)
            }
            else -> scheduleRefresh()
        }
    }

    /** True when a real alphabetic keyboard (USB or Bluetooth) is attached. */
    fun hasHardwareKeyboard(): Boolean {
        val im = getSystemService(android.hardware.input.InputManager::class.java)
        return im.inputDeviceIds.any { id ->
            val dev = im.getInputDevice(id) ?: return@any false
            !dev.isVirtual && dev.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                dev.supportsSource(android.view.InputDevice.SOURCE_KEYBOARD) && !dev.name.contains("HyperDesk")
        }
    }

    override fun onInterrupt() {}

    // ---- desktop keyboard shortcuts ----

    private var metaAlone = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val d = desk ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_META_RIGHT) {
            if (down) {
                if (event.repeatCount == 0) metaAlone = true
            } else if (metaAlone) {
                metaAlone = false
                d.toggleMenu()
            }
            return true
        }
        if (down) metaAlone = false

        // Alt+Tab: Tab steps while Alt is held, releasing Alt switches, Esc cancels.
        if (code == KeyEvent.KEYCODE_TAB && event.isAltPressed) {
            if (down) d.switchNext(event.isShiftPressed)
            return true
        }
        if ((code == KeyEvent.KEYCODE_ALT_LEFT || code == KeyEvent.KEYCODE_ALT_RIGHT) && !down && d.switcherOpen) {
            d.switchCommit()
            return false
        }
        if (code == KeyEvent.KEYCODE_ESCAPE && d.switcherOpen) {
            if (down) d.switchCancel()
            return true
        }

        if (code == KeyEvent.KEYCODE_F11 || (event.isMetaPressed && code == KeyEvent.KEYCODE_F)) {
            if (down && event.repeatCount == 0) {
                if (d.fullscreenId != -1) d.exitFullscreen() else d.focusedTask()?.let { d.toggleFullscreen(it) }
            }
            return true
        }

        if (event.isMetaPressed) {
            val action: (() -> Unit)? = when {
                code == KeyEvent.KEYCODE_DPAD_LEFT -> ({ d.focusedTask()?.let { d.snap(it, true) } })
                code == KeyEvent.KEYCODE_DPAD_RIGHT -> ({ d.focusedTask()?.let { d.snap(it, false) } })
                code == KeyEvent.KEYCODE_DPAD_UP -> ({ d.focusedTask()?.let { d.toggleMaximize(it) } })
                code == KeyEvent.KEYCODE_DPAD_DOWN -> ({ d.focusedTask()?.let { d.minimize(it) } })
                code == KeyEvent.KEYCODE_D -> ({ d.showDesktop() })
                code == KeyEvent.KEYCODE_S && event.isShiftPressed -> ({ d.screenshot() })
                code == KeyEvent.KEYCODE_A -> ({ d.toggleQuick() })
                code in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> ({ d.launchIndex(code - KeyEvent.KEYCODE_1) })
                else -> null
            }
            if (action != null) {
                if (down && event.repeatCount == 0) action()
                return true
            }
        }
        if (event.isAltPressed && code == KeyEvent.KEYCODE_F4) {
            if (down && event.repeatCount == 0) d.focusedTask()?.let { d.close(it) }
            return true
        }
        return false
    }
}
