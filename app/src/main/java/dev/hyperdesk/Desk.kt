package dev.hyperdesk

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import dev.hyperdesk.shell.ShellClient
import dev.hyperdesk.shell.Task
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.view.Gravity
import androidx.compose.ui.graphics.asImageBitmap

/** Everything that lives on one external display: taskbar, start menu, window bookkeeping. */
class Desk(val svc: DeskService, val display: Display) {
    val ctx: Context = svc.createDisplayContext(display)
        .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        .also { it.setTheme(android.R.style.Theme_DeviceDefault) }
    val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
    val id get() = display.displayId

    private val density get() = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * density + 0.5f).toInt()
    val barH get() = dp(48)

    val screen: Rect get() = wm.maximumWindowMetrics.bounds
    /** Everything above the taskbar. */
    val deskArea: Rect get() = screen.let { Rect(0, 0, it.width(), it.height() - barH) }
    /** Where an app's own rectangle may go: the desk minus room for the title bar drawn above it. */
    val workArea: Rect get() = deskArea.also { it.top = decor.barH }

    /** Windows on this display, in the order they were first seen. */
    var tasks: List<Task> = emptyList()
        private set
    var helper by mutableStateOf(false)
        private set
    var entries by mutableStateOf(listOf<BarEntry>())
        private set
    var menuOpen by mutableStateOf(false)
        private set
    var panelOff by mutableStateOf(false)
        private set
    var taskbarLeft by mutableStateOf(Prefs.taskbarLeft)

    // Exclusive full screen: one window covers the whole monitor, with no title bar and no taskbar.
    var fullscreenId = -1
        private set
    private var fullscreenSince = 0L
    private val fsEdge = OverlayWindow(this, focusable = false) { FullscreenEdge(this) }
    private val fsPill = OverlayWindow(this, focusable = false) { FullscreenPill(this) }
    private val hideFsPill = Runnable { fsPill.hide() }

    /** See HelperProvider: lets a developer exercise keyboard- and mouse-only features over adb. */
    fun debug(command: String) {
        val a = command.split(' ')
        fun task() = tasks.firstOrNull { it.pkg == a.getOrNull(1) } ?: focusedTask()
        when (a[0]) {
            "fullscreen" -> task()?.let(::toggleFullscreen)
            "exitfs" -> exitFullscreen()
            "menu" -> toggleMenu()
            "switch" -> switchNext(false)
            "commit" -> switchCommit()
            "winmenu" -> task()?.let { showWindowMenu(it.bounds.left + dp(40), it.bounds.top, it) }
            "layout" -> task()?.let { t -> layouts().getOrNull(a.getOrNull(2)?.toIntOrNull() ?: 2)?.let { rememberNormal(t, t.bounds); place(t, it.second) } }
            "maximize" -> task()?.let(::toggleMaximize)
            "minimize" -> task()?.let(::minimize)
            "apps" -> android.util.Log.i("HyperDesk", "apps=${Apps.all.size} has(${a.getOrNull(1)})=${Apps.all.any { it.pkg == a.getOrNull(1) }}")
            "scale" -> { Prefs.scale = a.getOrNull(1)?.toIntOrNull() ?: 100; applyScale() }
        }
    }

    /**
     * The system's own navigation buttons sit on top of apps at the bottom of the monitor (our taskbar normally
     * covers them). The only way to remove them is the phone-wide switch to gesture navigation with the gesture
     * line hidden, so full screen flips that on and puts the previous values back afterwards.
     */
    private fun hideSystemNav(hide: Boolean) {
        if (hide) {
            if (Prefs.navSaved.isNotEmpty()) return
            ShellClient.request("sh", "settings get global force_fsg_nav_bar; settings get global hide_gesture_line") { r ->
                val v = r?.lines()?.map { it.trim().let { x -> if (x == "1") "1" else "0" } } ?: return@request
                if (fullscreenId == -1) return@request
                Prefs.navSaved = v.getOrElse(0) { "0" } + "," + v.getOrElse(1) { "0" }
                ShellClient.request("sh", "settings put global force_fsg_nav_bar 1; settings put global hide_gesture_line 1")
            }
        } else {
            val saved = Prefs.navSaved.split(',')
            if (saved.size != 2) return
            Prefs.navSaved = ""
            ShellClient.request("sh", "settings put global force_fsg_nav_bar ${saved[0]}; settings put global hide_gesture_line ${saved[1]}")
        }
    }

    fun isFullscreen(taskId: Int) = taskId == fullscreenId

    fun toggleFullscreen(t: Task) = if (fullscreenId == t.id) exitFullscreen() else enterFullscreen(t)

    private fun showTaskbar() = taskbar.show(WindowManager.LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM, 0, 0, keepScreenOn = true)

    private fun enterFullscreen(t: Task) {
        exitFullscreen()
        if (!helper) return
        rememberNormal(t, t.bounds)
        fullscreenId = t.id
        fullscreenSince = android.os.SystemClock.uptimeMillis()
        dismissPopup(); hideMenu(); hideQuick(); hideTip()
        taskbar.hide()
        minimized.remove(t.id)
        val s = screen
        ShellClient.request("resize", t.id, 0, 0, s.width(), s.height())
        ShellClient.request("focus", t.id)
        // A hair-thin strip along the top edge: pushing the pointer against it offers the way out.
        fsEdge.show(WindowManager.LayoutParams.MATCH_PARENT, dp(2), Gravity.TOP, 0, 0)
        hideSystemNav(true)
        toast("Full screen · Meta+F, F11 or the top edge to leave")
        svc.scheduleRefresh(60)
    }

    fun exitFullscreen() {
        val id = fullscreenId
        if (id == -1) return
        fullscreenId = -1
        hideSystemNav(false)
        fsEdge.hide()
        ui.removeCallbacks(hideFsPill)
        fsPill.hide()
        showTaskbar()
        tasks.firstOrNull { it.id == id }?.let { t ->
            applyBounds(t, restore.remove(id) ?: fit(Prefs.bounds(t.pkg) ?: defaultBounds()))
        }
        svc.scheduleRefresh(60)
    }

    fun offerFullscreenExit() {
        if (fullscreenId == -1) return
        fsPill.show(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(10))
        ui.removeCallbacks(hideFsPill)
        ui.postDelayed(hideFsPill, 2800)
    }
    private companion object { const val MODE_WINDOW = 6 }
    private val order = ArrayList<Int>()
    /** Minimized windows are parked below the screen edge; this remembers where they came from. */
    private val minimized = HashMap<Int, Rect>()
    private val normalized = HashSet<Int>()
    private val firstSeen = HashMap<Int, Long>()
    private val attempts = HashMap<Int, Int>()
    private var topId = -1
    private var zOrder: List<Int> = emptyList()
    val decor = Decor(this)
    private val restore = HashMap<Int, Rect>()
    private var fallbackPkgs: List<String> = emptyList()
    private var launches = 0

    // The taskbar also hears every click elsewhere on the monitor, which is our cue that focus may have changed.
    private val taskbar = OverlayWindow(this, focusable = false, onOutside = { svc.burstRefresh() }) { TaskbarContent(this) }
    private val startMenu = OverlayWindow(this, focusable = true, onOutside = { hideMenu() }) { StartMenuContent(this) }
    private var popup: OverlayWindow? = null

    // Alt+Tab switcher
    var switcher by mutableStateOf(listOf<Task>())
        private set
    var switcherIndex by mutableStateOf(0)
        private set
    val switcherOpen get() = switcher.isNotEmpty()
    private val switcherWin = OverlayWindow(this, focusable = false, touchable = false) { SwitcherContent(this) }

    // Quick settings, toast, tooltip
    var quickOpen by mutableStateOf(false)
        private set
    private val quickWin = OverlayWindow(this, focusable = false, onOutside = { hideQuick() }) { QuickPanelContent(this) }
    var toastText by mutableStateOf("")
        private set
    private val toastWin = OverlayWindow(this, focusable = false, touchable = false) { ToastContent(toastText) }
    var tipText by mutableStateOf("")
        private set
    private var tipWin: OverlayWindow? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    // Notification toast (top right) and window previews
    var toastNotif by mutableStateOf<Notif?>(null)
        private set
    private val notifWin = OverlayWindow(this, focusable = false) { NotifToastContent(this) }
    private val hideNotif = Runnable { notifWin.hide(); toastNotif = null }
    var previews by mutableStateOf(mapOf<Int, androidx.compose.ui.graphics.ImageBitmap>())
        private set
    var tipTask by mutableStateOf(-1)
        private set
    private val onTop = HashSet<Int>()
    /** Windows that appeared after we started: they open behind our always-on-top windows and must be raised once. */
    private val fresh = HashSet<Int>()
    private var known: Set<Int>? = null
    private val raiseAgain = HashSet<Int>()
    /** New windows still owed a re-layout nudge: id → how many have been done. */
    private val kicks = HashMap<Int, Int>()

    fun start() {
        setXiaomiDots(false)
        decor.start()
        showTaskbar()
        bindMice(offerTouchpad = true)
        if (Prefs.panelOff) setPanel(false)
        ShellClient.request("fixnav")
        ensureNotificationAccess()
        applyScale()
        setMonitorIme(true)
        setDockDnd(true)
        refresh()
    }

    private var touchpadOffered = false

    /** Bind attached mice to this display; with none attached, open the phone touchpad once. */
    private fun bindMice(offerTouchpad: Boolean) {
        if (!Prefs.autoMouse) return
        ShellClient.request("mouse", id) { n ->
            if (offerTouchpad && n == "0" && Prefs.autoTouchpad && !touchpadOffered) {
                touchpadOffered = true
                TouchpadActivity.open(svc)
            }
        }
    }

    private var keyboardFor = 0L

    /**
     * A view on the monitor was focused or clicked. An editable one calls up the phone's keyboard (unless a real
     * keyboard is attached); a click on anything else puts it away again.
     */
    fun onFieldFocus(editable: Boolean, clicked: Boolean) {
        if (!Prefs.autoKeyboard || !helper) return
        val now = android.os.SystemClock.uptimeMillis()
        if (editable) {
            if (svc.hasHardwareKeyboard()) return
            keyboardFor = now
            TouchpadActivity.showKeyboard(svc)
        } else if (clicked && now - keyboardFor > 600) {
            TouchpadActivity.hideKeyboard()
        }
    }

    private fun setDockDnd(docked: Boolean) {
        if (docked) {
            if (Prefs.dockDnd && !Prefs.dndManaged) {
                ShellClient.request("sh", "cmd notification set_dnd priority") { r -> if (r != null) Prefs.dndManaged = true }
            }
        } else if (Prefs.dndManaged) {
            ShellClient.request("sh", "cmd notification set_dnd off")
            Prefs.dndManaged = false
        }
    }

    fun applyDockDnd() = setDockDnd(Prefs.dockDnd).also { if (!Prefs.dockDnd) setDockDnd(false) }

    /** The system's own on-screen keyboard would open on the monitor itself; keep it off there while docked. */
    private fun setMonitorIme(hidden: Boolean) = ShellClient.request("imepolicy", id, if (hidden) 2 else 0)

    /** Type text into whatever has keyboard focus on the monitor: as key presses if possible, otherwise by pasting. */
    fun typeText(text: String) {
        ShellClient.request("text", id, text) { r -> if (r != "ok") svc.pasteOn(id, text) }
    }

    fun sendKey(code: Int, meta: Int = 0) = ShellClient.request("key", id, code, meta)

    fun stop() {
        dismissPopup()
        hideMenu()
        hideQuick()
        hideTip()
        switchCancel()
        toastWin.hide()
        notifWin.hide()
        fsEdge.hide()
        fsPill.hide()
        fullscreenId = -1
        hideSystemNav(false)
        ui.removeCallbacksAndMessages(null)
        decor.stop()
        taskbar.hide()
        setXiaomiDots(true)
        ShellClient.request("mouse", -1)
        ShellClient.request("pointer_stop")
        setMonitorIme(false)
        setDockDnd(false)
        if (panelOff) setPanel(true)
    }

    /**
     * Xiaomi draws three dots at the top of every window and reserves a strip for them, which leaves a gap under
     * our title bar. Its own secure setting turns that off; we hold it off only while the monitor is in use.
     */
    private fun setXiaomiDots(on: Boolean) {
        try {
            val cr = svc.contentResolver
            if (!on) {
                if (android.provider.Settings.Secure.getInt(cr, "disable_decoration_dot", 0) == 0) {
                    android.provider.Settings.Secure.putInt(cr, "disable_decoration_dot", 1)
                    Prefs.dotsManaged = true
                }
            } else if (Prefs.dotsManaged) {
                android.provider.Settings.Secure.putInt(cr, "disable_decoration_dot", 0)
                Prefs.dotsManaged = false
            }
        } catch (e: SecurityException) {
            android.util.Log.w("HyperDesk", "cannot change disable_decoration_dot (grant WRITE_SECURE_SETTINGS)")
        }
    }

    fun onHelperConnected() {
        if (fullscreenId == -1) hideSystemNav(false)
        ShellClient.request("fixnav")
        ensureNotificationAccess()
        applyScale()
        setMonitorIme(true)
        bindMice(offerTouchpad = true)
        if (Prefs.panelOff) setPanel(false)
        refresh()
    }

    fun toggleMenu() = if (menuOpen) hideMenu() else showMenu()

    fun showMenu() {
        // Cheap safety net in case an install was missed (the menu updates in place when the list arrives).
        Apps.load(svc)
        dismissPopup()
        hideQuick()
        if (taskbarLeft) startMenu.show(dp(640), minOf(dp(700), deskArea.height() - dp(24)), Gravity.BOTTOM or Gravity.LEFT, dp(12), barH + dp(12))
        else startMenu.show(dp(640), minOf(dp(700), deskArea.height() - dp(24)), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, barH + dp(12))
        menuOpen = true
    }

    fun hideMenu() {
        startMenu.hide()
        menuOpen = false
    }

    fun showPopup(anchorX: Int, items: List<MenuItem>) {
        dismissPopup()
        hideTip()
        val w = OverlayWindow(this, focusable = false, onOutside = { dismissPopup() }) {
            PopupContent(items) { dismissPopup() }
        }
        val x = anchorX.coerceIn(dp(8), maxOf(dp(8), screen.width() - dp(260)))
        w.show(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.LEFT, x, barH + dp(8))
        popup = w
    }

    fun dismissPopup() {
        popup?.hide()
        popup = null
    }

    // ---- Alt+Tab ----

    fun switchNext(reverse: Boolean) {
        if (switcher.isEmpty()) {
            val list = tasks.sortedBy { zOrder.indexOf(it.id) }
            if (list.isEmpty()) return
            switcher = list
            loadPreviews(list)
            switcherIndex = if (reverse) list.size - 1 else minOf(1, list.size - 1)
            switcherWin.show(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER, 0, 0)
        } else {
            val n = switcher.size
            switcherIndex = (switcherIndex + (if (reverse) -1 else 1) + n) % n
        }
    }

    fun switchCommit() {
        val t = switcher.getOrNull(switcherIndex)
        switchCancel()
        if (t != null) activate(t)
    }

    fun switchCancel() {
        switcherWin.hide()
        switcher = emptyList()
    }

    // ---- quick settings, toast, tooltip ----

    // ---- notifications ----

    fun showNotification(n: Notif) {
        if (!Prefs.notifications) return
        ui.post {
            toastNotif = n
            notifWin.show(dp(380), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.RIGHT, dp(16), dp(16))
            ui.removeCallbacks(hideNotif)
            ui.postDelayed(hideNotif, 5500)
        }
    }

    fun dismissNotifToast() {
        ui.removeCallbacks(hideNotif)
        hideNotif.run()
    }

    fun openNotification(n: Notif) {
        dismissNotifToast()
        hideQuick()
        if (!Notifs.open(svc, n, id)) launch(n.pkg)
        Notifs.dismiss(n)
        svc.scheduleRefresh(200)
    }

    private fun ensureNotificationAccess() {
        if (Prefs.notifications && !Notifs.allowed(svc)) {
            ShellClient.request("sh", "cmd notification allow_listener ${svc.packageName}/${NotifService::class.java.name}")
        }
    }

    // ---- interface size ----

    /**
     * Apply the chosen interface size as a density override on this display. Our own overlays are built for the
     * density they were created at, so the desk is rebuilt once when it actually changes.
     */
    fun applyScale() {
        if (scaleBusy) return
        scaleBusy = true
        ShellClient.request("density", id) { out ->
            val v = out?.split(' ')?.mapNotNull { it.toIntOrNull() }
            if (v == null || v.size != 2) { scaleBusy = false; return@request }
            val (native, current) = v
            val want = (native * Prefs.scale.coerceIn(70, 200) / 100).coerceIn(96, 640)
            if (kotlin.math.abs(current - want) <= 1) { scaleBusy = false; return@request }
            ShellClient.request("density", id, if (want == native) 0 else want) { r ->
                // Rebuild only if the change really happened; otherwise we would loop.
                val now = r?.split(' ')?.getOrNull(1)?.toIntOrNull()
                if (now != null && kotlin.math.abs(now - want) <= 1) ui.postDelayed({ svc.restartDesk() }, 700) else scaleBusy = false
            }
        }
    }

    private var scaleBusy = false

    // ---- window previews ----

    /** Fetch fresh pictures of the given windows for the switcher and taskbar hover cards. */
    private fun loadPreviews(list: List<Task>) {
        for (t in list) ShellClient.snapshot(t.id) { bmp ->
            if (bmp != null) previews = previews + (t.id to bmp.asImageBitmap())
        }
    }

    fun toggleQuick() = if (quickOpen) hideQuick() else {
        dismissPopup(); hideMenu()
        quickWin.show(dp(340), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.RIGHT, dp(12), barH + dp(12))
        quickOpen = true
    }

    fun hideQuick() {
        quickWin.hide()
        quickOpen = false
    }

    fun toast(text: String) {
        toastText = text
        toastWin.show(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, barH + dp(28))
        ui.removeCallbacks(hideToast)
        ui.postDelayed(hideToast, 2200)
    }

    private val hideToast = Runnable { toastWin.hide() }

    fun showTip(anchorX: Int, text: String, pkg: String? = null) {
        hideTip()
        if (popup != null || menuOpen) return
        tipText = text
        val t = pkg?.let { p -> tasks.filter { it.pkg == p }.minByOrNull { zOrder.indexOf(it.id) } }
        tipTask = t?.id ?: -1
        if (t != null) loadPreviews(listOf(t))
        val w = OverlayWindow(this, focusable = false, touchable = false) { TipContent(tipText, previews[tipTask]) }
        w.show(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.LEFT, maxOf(dp(4), anchorX - dp(8)), barH + dp(6))
        tipWin = w
    }

    fun hideTip() {
        tipWin?.hide()
        tipWin = null
    }

    fun screenshot() {
        hideQuick()
        // Let the panel disappear before the capture.
        ui.postDelayed({
            ShellClient.request("shot", id) { path -> toast(if (path != null) "Screenshot saved to Pictures/Screenshots" else "Screenshot failed") }
        }, 250)
    }

    /** Middle-click on a taskbar icon: close every window of that app. */
    fun closeApp(pkg: String) = tasks.filter { it.pkg == pkg }.forEach(::close)

    /** Meta+1…9: the nth taskbar entry. */
    fun launchIndex(n: Int) {
        entries.getOrNull(n)?.let { onBarClick(it.pkg) }
    }

    fun setPanel(on: Boolean) {
        ShellClient.request("panel", if (on) 2 else 0) { r -> if (r != null) panelOff = !on }
    }

    fun menuFor(e: BarEntry): List<MenuItem> {
        val t = tasks.firstOrNull { it.pkg == e.pkg }
        val items = ArrayList<MenuItem>()
        if (t != null && helper) {
            items += MenuItem(if (isMaximized(t.bounds)) "Restore size" else "Maximize") { toggleMaximize(t) }
            items += MenuItem("Snap left") { snap(t, true) }
            items += MenuItem("Snap right") { snap(t, false) }
            items += MenuItem(if (isMinimized(t)) "Show" else "Minimize") { if (isMinimized(t)) activate(t) else minimize(t) }
            items += MenuItem("New window") { newWindow(e.pkg) }
            items += MenuItem("Move to phone screen") { moveToPhone(t) }
        } else if (t == null) {
            items += MenuItem("Open") { launch(e.pkg) }
        }
        items += MenuItem(if (e.pinned) "Unpin from taskbar" else "Pin to taskbar") { togglePin(e.pkg) }
        if (t != null && helper) items += MenuItem("Close", danger = true) { close(t) }
        return items
    }

    /** Right-click on a title bar: the window's own menu, with every snap layout. */
    fun showWindowMenu(x: Int, y: Int, t: Task) {
        val items = ArrayList<MenuItem>()
        items += MenuItem(if (isMaximized(t.bounds)) "Restore size" else "Maximize") { toggleMaximize(t) }
        items += MenuItem("Full screen") { toggleFullscreen(t) }
        items += MenuItem("Minimize") { minimize(t) }
        for ((name, r) in layouts()) items += MenuItem(name) { rememberNormal(t, t.bounds); place(t, r) }
        items += MenuItem("New window") { newWindow(t.pkg) }
        items += MenuItem("Move to phone screen") { moveToPhone(t) }
        items += MenuItem("Close", danger = true) { close(t) }
        dismissPopup()
        hideTip()
        val w = OverlayWindow(this, focusable = false, onOutside = { dismissPopup() }) { PopupContent(items) { dismissPopup() } }
        val px = x.coerceIn(dp(8), maxOf(dp(8), screen.width() - dp(260)))
        val py = y.coerceIn(dp(8), maxOf(dp(8), deskArea.bottom - dp(44) * items.size - dp(24)))
        w.show(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT, px, py)
        popup = w
    }

    /** Ask the app for another window of itself. Apps that only allow one will just come to the front. */
    fun newWindow(pkg: String) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        startOnDesk(intent)
    }

    /** Start any intent as a window on this monitor. */
    fun startOnDesk(intent: Intent) {
        hideMenu()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeBasic()
        opts.launchDisplayId = id
        opts.launchBounds = defaultBounds()
        try {
            svc.startActivity(intent, opts.toBundle().apply { putInt("android.activity.windowingMode", MODE_WINDOW) })
        } catch (e: Exception) {
            toast("Nothing on the phone can open that")
        }
        svc.scheduleRefresh(150)
    }

    fun isMinimized(t: Task) = t.id in minimized

    // ---- state ----

    fun refresh() {
        ShellClient.tasks(id) { list ->
            helper = list != null
            if (list == null) {
                tasks = emptyList()
                fallbackPkgs = accessibilityPackages()
            } else {
                val mine = list.filter { it.pkg.isNotEmpty() && it.pkg != "com.miui.home" }
                val ids = mine.map { it.id }.toSet()
                order.retainAll(ids)
                mine.forEach { if (it.id !in order) order.add(it.id) }
                minimized.keys.retainAll(ids)
                // Parked windows are recognised by position, so the state survives a service restart.
                mine.forEach { if (it.bounds.top >= screen.height() && it.id !in minimized) minimized[it.id] = Prefs.bounds(it.pkg) ?: defaultBounds() }
                normalized.retainAll(ids)
                firstSeen.keys.retainAll(ids)
                kicks.keys.retainAll(ids)
                mine.forEach { firstSeen.putIfAbsent(it.id, android.os.SystemClock.uptimeMillis()) }
                attempts.keys.retainAll(ids)
                restore.keys.retainAll(ids)
                onTop.retainAll(ids)
                known?.let { k -> fresh.addAll(ids - k) }
                fresh.retainAll(ids)
                known = ids
                // getTasks returns top-most first: the first visible, non-minimized window is the active one.
                topId = mine.firstOrNull { it.visible && it.id !in minimized && it.bounds.left < screen.width() }?.id ?: topId.takeIf { id -> id in ids } ?: -1
                zOrder = mine.map { it.id }
                if (fullscreenId != -1) {
                    val settled = android.os.SystemClock.uptimeMillis() - fullscreenSince > 1500
                    if (fullscreenId !in ids || (settled && topId != fullscreenId)) exitFullscreen()
                }
                tasks = mine.sortedBy { order.indexOf(it.id) }
                tasks.forEach(::adopt)
            }
            decor.update(
                tasks.filter { it.visible && it.id !in minimized && it.bounds.top < screen.height() }
                    .sortedBy { zOrder.indexOf(it.id) },
            )
            entries = computeEntries()
        }
    }

    /**
     * Xiaomi's floating-window mode draws its own frame and scales content unpredictably, so every window
     * on the monitor is moved into plain multi-window mode, where it is exactly the rectangle we set.
     */
    private fun adopt(t: Task) {
        if (t.id in minimized) return
        // First sighting (or not yet in window mode): window mode, a sane rectangle, and kept above the home screen.
        if (t.mode != MODE_WINDOW || t.id !in onTop) {
            normalized.remove(t.id)
            val n = (attempts[t.id] ?: 0) + 1
            attempts[t.id] = n
            if (n > 8) return
            val keep = t.mode == MODE_WINDOW && onScreen(t.bounds) && t.id !in fresh && t.id !in raiseAgain
            val target = when {
                keep -> t.bounds
                Prefs.maximized(t.pkg) -> workArea
                else -> fit(Prefs.bounds(t.pkg) ?: defaultBounds())
            }
            ShellClient.request("wmode", t.id, MODE_WINDOW, *rectArgs(target))
            if (t.mode == MODE_WINDOW) onTop.add(t.id)
            if (t.id in fresh || t.id in raiseAgain) kicks.putIfAbsent(t.id, 0)
            if (fresh.remove(t.id) || t.id in raiseAgain) {
                // Raise it now, and once more after the mode switch has landed.
                ShellClient.request("focus", t.id)
                if (!raiseAgain.remove(t.id)) raiseAgain.add(t.id)
            }
            svc.scheduleRefresh(120)
            return
        }
        normalized.add(t.id)
        attempts.remove(t.id)
        kicks[t.id]?.let { done ->
            // A freshly converted window keeps the layout (and Xiaomi's crop) of whatever size it was launched at
            // until its size really changes. Nudge it by two pixels and back: once early, once after Xiaomi's
            // launch animation has finished.
            val age = android.os.SystemClock.uptimeMillis() - (firstSeen.getOrPut(t.id) { android.os.SystemClock.uptimeMillis() })
            val due = if (done == 0) 250 else 1100
            if (decor.owns(t.id) || !onScreen(t.bounds)) return@let
            if (age >= due) {
                val b = Rect(t.bounds)
                ShellClient.request("resize", t.id, b.left, b.top, b.right + 2, b.bottom)
                ui.postDelayed({ ShellClient.request("resize", t.id, b.left, b.top, b.right, b.bottom) }, 90)
                if (done >= 1) kicks.remove(t.id) else kicks[t.id] = done + 1
            }
            svc.scheduleRefresh(200)
        }
        if (t.bounds.left >= screen.width() && !decor.owns(t.id)) {
            // Left parked off-screen by an interrupted transition: bring it back.
            ShellClient.request("resize", t.id, *rectArgs(fit(Prefs.bounds(t.pkg) ?: defaultBounds())))
            svc.scheduleRefresh(120)
            return
        }
    }

    /** False for windows parked off-screen (minimized, or mid-transition). */
    private fun onScreen(b: Rect) = b.left < screen.width() && b.right > 0 && b.top < workArea.bottom

    /** A sane opening rectangle: fully inside the work area, and never a stale near-full-screen one. */
    private fun fit(r: Rect): Rect {
        val wa = workArea
        if (r.width() >= wa.width() * 0.95f && r.height() >= wa.height() * 0.95f) return defaultBounds()
        val w = minOf(r.width(), wa.width())
        val h = minOf(r.height(), wa.height())
        val l = r.left.coerceIn(wa.left, wa.right - w)
        val t = r.top.coerceIn(wa.top, wa.bottom - h)
        return Rect(l, t, l + w, t + h)
    }

    private fun accessibilityPackages(): List<String> = try {
        val windows = svc.windowsOnAllDisplays.get(id) ?: emptyList()
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root?.packageName?.toString() }
            .filter { it != "com.miui.home" && it != ctx.packageName }.distinct()
    } catch (e: Exception) { emptyList() }

    /** Taskbar entries: pinned apps first, then other running apps. */
    private fun computeEntries(): List<BarEntry> {
        val running = if (helper) tasks.map { it.pkg }.distinct() else fallbackPkgs
        val pinned = Prefs.pinned.filter { Apps.get(ctx, it) != null }
        return (pinned + running.filter { it !in pinned }).map { pkg ->
            val t = tasks.filter { it.pkg == pkg }
            BarEntry(pkg, pkg in running, t.any { it.id == topId }, pkg in pinned)
        }
    }

    // ---- geometry ----

    private fun rectArgs(r: Rect): Array<Any> = arrayOf(r.left, r.top, r.right, r.bottom)
    private fun frac(a: Float, b: Float) = workArea.let { Rect((it.width() * a).toInt(), it.top, (it.width() * b).toInt(), it.bottom) }

    /** Named snap layouts across the width of the work area. */
    private fun layouts(): List<Pair<String, Rect>> = listOf(
        "Left half" to leftHalf(), "Right half" to rightHalf(),
        "Left third" to frac(0f, 1 / 3f), "Middle third" to frac(1 / 3f, 2 / 3f), "Right third" to frac(2 / 3f, 1f),
        "Left two thirds" to frac(0f, 2 / 3f), "Right two thirds" to frac(1 / 3f, 1f),
    )

    /** True for maximized and snapped rectangles, which are states rather than sizes worth remembering. */
    private fun isLayout(r: Rect) = isMaximized(r) || layouts().any { it.second == r }

    private fun leftHalf() = workArea.let { Rect(0, it.top, it.width() / 2, it.bottom) }
    private fun rightHalf() = workArea.let { Rect(it.width() / 2, it.top, it.right, it.bottom) }

    private fun defaultBounds(): Rect {
        val wa = workArea
        val h = (wa.height() * 0.82f).toInt()
        val w = minOf((wa.width() * 0.62f).toInt(), (h * 1.6f).toInt())
        val step = dp(36) * (launches++ % 6)
        val l = (wa.width() - w) / 2 - dp(90) + step
        val t = maxOf(wa.top, wa.top + (wa.height() - h) / 2 - dp(40) + step)
        return Rect(l, t, l + w, t + h)
    }

    // ---- actions ----

    /** Covers the whole work area (our maximize) or the whole display (Xiaomi's own fullscreen). */
    fun isMaximized(b: Rect) = workArea.let { b.left <= 0 && b.top <= it.top && b.right >= it.right && b.bottom >= it.bottom }

    fun focusedTask(): Task? = tasks.firstOrNull { it.id == topId }

    /** The size a maximized or snapped window returns to, if it is currently in one of those states. */
    fun normalSizeFor(t: Task): Rect? {
        val b = t.bounds
        if (!isLayout(b)) return null
        return restore[t.id] ?: Prefs.bounds(t.pkg) ?: defaultBounds()
    }

    fun rememberNormal(t: Task, r: Rect) {
        if (!isLayout(r)) restore[t.id] = Rect(r)
    }

    /** Put the window there now, with no transition (used by drags, which draw their own). */
    fun applyBounds(t: Task, r: Rect) {
        minimized.remove(t.id)
        if (!isLayout(r) && onScreen(r) && r.top >= workArea.top) Prefs.saveBounds(t.pkg, r)
        Prefs.setMaximized(t.pkg, isMaximized(r))
        ShellClient.request("resize", t.id, *rectArgs(r))
        if (t.id != topId) ShellClient.request("focus", t.id)
        svc.scheduleRefresh(60)
    }

    fun launch(pkg: String) {
        hideMenu()
        tasks.firstOrNull { it.pkg == pkg }?.let { activate(it); return }
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeBasic()
        opts.launchDisplayId = id
        opts.launchBounds = fit(Prefs.bounds(pkg) ?: defaultBounds())
        try {
            // Ask for multi-window mode up front so the window does not flash in Xiaomi's phone-shaped frame first.
            val bundle = opts.toBundle().apply { putInt("android.activity.windowingMode", MODE_WINDOW) }
            svc.startActivity(intent, bundle)
        } catch (e: Exception) {
            android.util.Log.w("HyperDesk", "launch $pkg failed", e)
        }
        svc.scheduleRefresh(150)
    }

    /** Taskbar click: open the app, bring it forward, step through its windows, or minimize it if it is already in front. */
    fun onBarClick(pkg: String) {
        val mine = tasks.filter { it.pkg == pkg }
        val top = mine.firstOrNull { it.id == topId }
        when {
            mine.isEmpty() -> launch(pkg)
            top == null -> activate(mine.minByOrNull { zOrder.indexOf(it.id) } ?: mine.first())
            mine.size > 1 -> activate(mine[(mine.indexOf(top) + 1) % mine.size])
            else -> minimize(top)
        }
    }

    fun activate(t: Task) {
        minimized.remove(t.id)?.let { ShellClient.request("resize", t.id, *rectArgs(fit(it))) }
        ShellClient.request("focus", t.id)
        svc.scheduleRefresh(60)
    }

    fun minimize(t: Task) {
        if (t.id in minimized) return
        val b = t.bounds
        val toward = Rect(b.centerX() - dp(60), deskArea.bottom - dp(8), b.centerX() + dp(60), deskArea.bottom + dp(40))
        decor.vanish(t, toward) {
            minimized[t.id] = Rect(b)
            val y = screen.height() + dp(200)
            ShellClient.request("resize", t.id, b.left, y, b.right, y + b.height())
            tasks.firstOrNull { it.id != t.id && it.id !in minimized && it.visible }?.let { ShellClient.request("focus", it.id) }
            svc.scheduleRefresh(60)
        }
    }

    fun close(t: Task) {
        decor.vanish(t, null) {
            ShellClient.request("close", t.id)
            svc.scheduleRefresh(80)
        }
    }

    private fun place(t: Task, r: Rect) {
        if (t.id in minimized) applyBounds(t, r) else decor.animateTo(t, r)
    }

    fun toggleMaximize(t: Task) {
        val wa = workArea
        if (isMaximized(t.bounds)) {
            place(t, restore.remove(t.id) ?: Prefs.bounds(t.pkg) ?: defaultBounds())
        } else {
            rememberNormal(t, t.bounds)
            place(t, wa)
        }
    }

    fun snap(t: Task, left: Boolean) {
        val target = if (left) leftHalf() else rightHalf()
        rememberNormal(t, t.bounds)
        place(t, target)
    }

    fun restoreSize(t: Task) = place(t, restore.remove(t.id) ?: Prefs.bounds(t.pkg) ?: defaultBounds())

    fun moveToPhone(t: Task) {
        ShellClient.request("move", t.id, 0)
        svc.scheduleRefresh(400)
    }

    fun showDesktop() {
        val open = tasks.filter { it.id !in minimized }
        if (open.isEmpty()) tasks.forEach(::activate) else open.forEach(::minimize)
    }

    fun back() = ShellClient.request("sh", "input -d $id keyevent BACK")

    fun togglePin(pkg: String) {
        val p = Prefs.pinned
        Prefs.pinned = if (pkg in p) p - pkg else p + pkg
        entries = computeEntries()
    }
}

data class BarEntry(val pkg: String, val running: Boolean, val focused: Boolean, val pinned: Boolean)

class MenuItem(val label: String, val danger: Boolean = false, val action: () -> Unit)
