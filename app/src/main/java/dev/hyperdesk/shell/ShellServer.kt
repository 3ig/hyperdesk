package dev.hyperdesk.shell

import android.graphics.Rect
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.Looper
import android.view.InputDevice
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlin.concurrent.thread

/**
 * Runs as the shell user (uid 2000), started over adb with app_process. It gives the app the
 * window-management and input calls that only shell or system may make.
 *
 * Protocol: one request per line, fields separated by tabs; one reply line, "ok\t<payload>" or "err\t<message>".
 */
object ShellServer {
    const val SOCKET = "hyperdesk_shell"
    const val VERSION = 1

    private val associated = HashSet<String>()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--call") {
            println(call(args.drop(1).joinToString("\t")))
            return
        }
        val allowedUid = args.firstOrNull()?.toIntOrNull() ?: -1
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        val server = try {
            LocalServerSocket(SOCKET)
        } catch (e: Exception) {
            System.err.println("already running? $e")
            return
        }
        println("hyperdesk shell server v$VERSION up, serving uid $allowedUid")
        appUid = allowedUid
        thread(isDaemon = true) {
            while (true) {
                try { if (!appLinked) sendBinder() } catch (t: Throwable) { System.err.println("sendBinder: $t") }
                Thread.sleep(3000)
            }
        }
        while (true) {
            val s = server.accept()
            val uid = try { s.peerCredentials.uid } catch (e: Exception) { -1 }
            if (uid != allowedUid && uid != 0 && uid != 2000) {
                s.close()
                continue
            }
            thread(isDaemon = true) { serve(s) }
        }
    }

    // ---- binder channel to the app (SELinux forbids apps from connecting to a shell socket) ----

    const val AUTHORITY = "dev.hyperdesk.helper"
    const val TRANSACT_REQUEST = 1
    const val TRANSACT_REGISTER = 2
    const val TRANSACT_SNAPSHOT = 3
    const val TRANSACT_POINTER = 4
    private var appUid = -1
    @Volatile private var appLinked = false

    private val service = object : android.os.Binder() {
        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            val uid = getCallingUid()
            if (uid != appUid && uid != 0 && uid != 2000) return false
            when (code) {
                TRANSACT_REQUEST -> {
                    val line = data.readString() ?: ""
                    val r = try {
                        "ok\t" + handle(line.split('\t'))
                    } catch (t: Throwable) {
                        val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                        "err\t$c"
                    }
                    reply?.writeString(r)
                    return true
                }
                TRANSACT_SNAPSHOT -> {
                    val buffer = try {
                        val snap = atmCall("takeTaskSnapshot", data.readInt(), false)
                        snap?.javaClass?.getMethod("getHardwareBuffer")?.invoke(snap) as? android.hardware.HardwareBuffer
                    } catch (t: Throwable) { null }
                    if (buffer == null) reply?.writeInt(0) else {
                        reply?.writeInt(1)
                        buffer.writeToParcel(reply!!, 0)
                    }
                    return true
                }
                TRANSACT_POINTER -> {
                    Pointer.event(data.readInt(), data.readInt(), data.readInt())
                    return true
                }
                TRANSACT_REGISTER -> {
                    val client = data.readStrongBinder()
                    appLinked = true
                    client?.linkToDeath({ appLinked = false }, 0)
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    /** Hands our binder to the app by calling its content provider (which also starts the app if needed). */
    private fun sendBinder() {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = android.os.Binder()
        val get = am.javaClass.methods.first { it.name == "getContentProviderExternal" && it.parameterTypes.size == 4 }
        val holder = get.invoke(am, AUTHORITY, 0, token, "hyperdesk") ?: return
        try {
            val provider = field(holder, "provider") ?: return
            val extras = android.os.Bundle()
            extras.putBinder("binder", service)
            val source = android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build()
            val call = provider.javaClass.methods.first { it.name == "call" && it.parameterTypes.size == 5 }
            call.invoke(provider, source, AUTHORITY, "binder", null, extras)
        } finally {
            try {
                am.javaClass.methods.first { it.name == "removeContentProviderExternalAsUser" }.invoke(am, AUTHORITY, token, 0)
            } catch (_: Throwable) {}
        }
    }

    private fun call(line: String): String {
        val s = LocalSocket()
        s.connect(LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
        val out = PrintWriter(s.outputStream, true)
        out.println(line)
        val r = BufferedReader(InputStreamReader(s.inputStream)).readLine()
        s.close()
        return r ?: "err\tno reply"
    }

    private fun serve(s: LocalSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.inputStream))
            val out = PrintWriter(s.outputStream, true)
            while (true) {
                val line = reader.readLine() ?: break
                val reply = try {
                    "ok\t" + handle(line.split('\t'))
                } catch (t: Throwable) {
                    val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                    "err\t" + c.toString().replace('\n', ' ').replace('\t', ' ')
                }
                out.println(reply.replace('\n', '\u0001'))
            }
        } catch (_: Throwable) {
        } finally {
            try { s.close() } catch (_: Throwable) {}
        }
    }

    private fun handle(a: List<String>): String = when (a[0]) {
        "ping" -> "$VERSION"
        "tasks" -> tasks(a.getOrNull(1)?.toIntOrNull() ?: -1)
        "resize" -> { atmCall("resizeTask", a[1].toInt(), Rect(a[2].toInt(), a[3].toInt(), a[4].toInt(), a[5].toInt()), 0); "" }
        "close" -> atmCall("removeTask", a[1].toInt()).toString()
        "focus" -> { focus(a[1].toInt()); "" }
        "reorder" -> { focus(a[1].toInt(), a11yStyle = false); "" }
        "move" -> { atmCall("moveRootTaskToDisplay", a[1].toInt(), a[2].toInt()); "" }
        "place" -> { place(a[1].toInt(), Rect(a[2].toInt(), a[3].toInt(), a[4].toInt(), a[5].toInt()), a[6].toFloat()); "" }
        "wmode" -> { wmode(a[1].toInt(), a[2].toInt(), Rect(a[3].toInt(), a[4].toInt(), a[5].toInt(), a[6].toInt())); "" }
        "hide" -> { hide(a[1].toInt(), a[2] == "1"); "" }
        "mouse" -> mouse(a[1].toInt())
        "panel" -> { panel(a[1].toInt()); "" }
        "fixnav" -> fixNav()
        "density" -> density(a[1].toInt(), a.getOrNull(2)?.toIntOrNull())
        "imepolicy" -> imePolicy(a[1].toInt(), a.getOrNull(2)?.toIntOrNull())
        "pointer_start" -> Pointer.start(a[1].toInt())
        "pointer_stop" -> { Pointer.stop(); "" }
        "pointer" -> { Pointer.event(a[1].toInt(), a[2].toInt(), a[3].toInt()); "" }
        "text" -> typeText(a[1].toInt(), a.drop(2).joinToString("\t"))
        "key" -> { key(a[1].toInt(), a[2].toInt(), a.getOrNull(3)?.toIntOrNull() ?: 0); "" }
        "shot" -> shot(a[1].toInt())
        "sh" -> sh(a.drop(1).joinToString(" "))
        else -> throw IllegalArgumentException("unknown command ${a[0]}")
    }

    // ---- activity task manager ----

    private val atm: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    private fun atmCall(name: String, vararg args: Any?): Any? {
        val m = atm.javaClass.methods.first { it.name == name && it.parameterTypes.size == args.size }
        return m.invoke(atm, *args)
    }

    /**
     * Bring a task to the front and give it focus. setFocusedTask only reorders; startActivityFromRecents
     * re-applies launch defaults, which on this display means Xiaomi's floating-window mode, so it is the fallback
     * only and is told to keep multi-window mode.
     */
    private fun focus(taskId: Int) = focus(taskId, a11yStyle = true)

    /**
     * Bring a task to the front. setFocusedTask is what the system itself calls when a window is clicked: it
     * reorders *and* runs the transition that restacks the surfaces. A bare WindowContainerTransaction reorder
     * changes the order on paper only, leaving the old window's pixels on top, so it is used only on request.
     */
    private fun focus(taskId: Int, a11yStyle: Boolean) {
        if (!a11yStyle) {
            val token = taskToken(taskId)
            val wctClass = Class.forName("android.window.WindowContainerTransaction")
            val tokenClass = Class.forName("android.window.WindowContainerToken")
            val wct = wctClass.getConstructor().newInstance()
            wctClass.getMethod("reorder", tokenClass, Boolean::class.javaPrimitiveType).invoke(wct, token, true)
            val org = Class.forName("android.window.WindowOrganizer")
            org.getMethod("applyTransaction", wctClass).invoke(org.getConstructor().newInstance(), wct)
            return
        }
        try {
            atmCall("setFocusedTask", taskId)
        } catch (t: Throwable) {
            val opts = android.os.Bundle().apply { putInt("android.activity.windowingMode", 6) }
            atmCall("startActivityFromRecents", taskId, opts)
        }
    }

    /** Task ids on a display in true stacking order, top-most first. */
    private fun stacking(displayId: Int): List<Int> = try {
        (atmCall("getAllRootTaskInfosOnDisplay", displayId) as List<*>).mapNotNull { field(it, "taskId") as? Int }
    } catch (t: Throwable) { emptyList() }

    // ---- window container transactions (bounds + Xiaomi floating-window scale / hidden state) ----

    private fun taskToken(taskId: Int): Any {
        val list = atmCall("getTasks", 200, false, false, -1) as List<*>
        val t = list.firstOrNull { field(it, "taskId") == taskId } ?: throw IllegalStateException("no task $taskId")
        return field(t, "token") ?: throw IllegalStateException("no token")
    }

    private fun applyChange(taskId: Int, bounds: Rect?, build: (Any) -> Unit) {
        val token = taskToken(taskId)
        val wctClass = Class.forName("android.window.WindowContainerTransaction")
        val tokenClass = Class.forName("android.window.WindowContainerToken")
        val wct = wctClass.getConstructor().newInstance()
        if (bounds != null) wctClass.getMethod("setBounds", tokenClass, Rect::class.java).invoke(wct, token, bounds)
        val changeClass = Class.forName("miui.app.MiuiFreeFormManager\$MiuiFreeFormInfoChange")
        val change = changeClass.getConstructor().newInstance()
        build(change)
        wctClass.getMethod("setMiuiFreeformInfoChange", tokenClass, changeClass).invoke(wct, token, change)
        val org = Class.forName("android.window.WindowOrganizer")
        org.getMethod("applyTransaction", wctClass).invoke(org.getConstructor().newInstance(), wct)
    }

    /** Scale goes through a window transaction (only when it differs); bounds go through resizeTask, which Xiaomi's frame follows cleanly. */
    private fun place(taskId: Int, bounds: Rect, scale: Float) {
        val cur = try { field(freeformInfo?.invoke(null, taskId), "freeFormScale") as? Float } catch (t: Throwable) { null }
        if (cur == null || kotlin.math.abs(cur - scale) > 0.01f) {
            applyChange(taskId, null) { c ->
                c.javaClass.getMethod("setMiuiFreeformScale", Float::class.javaPrimitiveType).invoke(c, scale)
            }
        }
        atmCall("resizeTask", taskId, bounds, 0)
    }

    /** Put a task in the given windowing mode with the given bounds, in one transaction. */
    private fun wmode(taskId: Int, mode: Int, bounds: Rect) {
        val token = taskToken(taskId)
        val wctClass = Class.forName("android.window.WindowContainerTransaction")
        val tokenClass = Class.forName("android.window.WindowContainerToken")
        val wct = wctClass.getConstructor().newInstance()
        wctClass.getMethod("setWindowingMode", tokenClass, Int::class.javaPrimitiveType).invoke(wct, token, mode)
        wctClass.getMethod("setBounds", tokenClass, Rect::class.java).invoke(wct, token, bounds)
        // Keep our windows above the display's home screen, so clicking the desktop does not bury them.
        try {
            wctClass.getMethod("setAlwaysOnTop", tokenClass, Boolean::class.javaPrimitiveType).invoke(wct, token, true)
        } catch (_: Throwable) {
        }
        val org = Class.forName("android.window.WindowOrganizer")
        org.getMethod("applyTransaction", wctClass).invoke(org.getConstructor().newInstance(), wct)
    }

    /** Saves a PNG of one display to the phone's Pictures/Screenshots folder and returns its path. */
    private fun shot(displayId: Int): String {
        val physical = (displayUniqueId(displayId) ?: throw IllegalStateException("no display")).substringAfter("local:")
        val name = "HyperDesk_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".png"
        val path = "/sdcard/Pictures/Screenshots/$name"
        sh("mkdir -p /sdcard/Pictures/Screenshots; screencap -d $physical -p $path; am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$path >/dev/null 2>&1")
        return path
    }

    /** Minimize = send the task behind the display's home screen; restore = bring it back to the top. */
    private fun hide(taskId: Int, hidden: Boolean) {
        val token = taskToken(taskId)
        val wctClass = Class.forName("android.window.WindowContainerTransaction")
        val tokenClass = Class.forName("android.window.WindowContainerToken")
        val wct = wctClass.getConstructor().newInstance()
        wctClass.getMethod("reorder", tokenClass, Boolean::class.javaPrimitiveType).invoke(wct, token, !hidden)
        val org = Class.forName("android.window.WindowOrganizer")
        org.getMethod("applyTransaction", wctClass).invoke(org.getConstructor().newInstance(), wct)
    }

    private fun field(o: Any?, name: String): Any? {
        if (o == null) return null
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(o)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    private val freeformInfo by lazy {
        try {
            Class.forName("miui.app.MiuiFreeFormManager").getMethod("getFreeFormStackInfoByStackId", Int::class.javaPrimitiveType)
        } catch (t: Throwable) { null }
    }

    private fun tasks(displayId: Int): String {
        val raw = atmCall("getTasks", 200, false, false, displayId) as List<*>
        // getTasks is in most-recently-used order; what is drawn on top is the stacking order.
        val z = if (displayId >= 0) stacking(displayId) else emptyList()
        val list = if (z.isEmpty()) raw else raw.sortedBy { t -> z.indexOf(field(t, "taskId") as Int).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        val arr = JSONArray()
        for (t in list) {
            val o = JSONObject()
            val id = field(t, "taskId") as Int
            o.put("id", id)
            o.put("display", field(t, "displayId") ?: 0)
            val top = field(t, "baseActivity") ?: field(t, "realActivity") ?: field(t, "topActivity")
            val cn = top as? android.content.ComponentName
            o.put("pkg", cn?.packageName ?: "")
            o.put("cls", cn?.className ?: "")
            o.put("visible", field(t, "isVisible") ?: false)
            o.put("focused", field(t, "isFocused") ?: false)
            val wc = field(field(t, "configuration"), "windowConfiguration")
            val b = wc?.javaClass?.getMethod("getBounds")?.invoke(wc) as? Rect
            o.put("mode", wc?.javaClass?.getMethod("getWindowingMode")?.invoke(wc) ?: 0)
            if (b != null) o.put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom)))
            try {
                val info = freeformInfo?.invoke(null, id)
                if (info != null) {
                    o.put("scale", (field(info, "freeFormScale") as? Float)?.toDouble() ?: 1.0)
                    o.put("ffmode", field(info, "windowState") ?: -1)
                }
            } catch (_: Throwable) {
            }
            arr.put(o)
        }
        return arr.toString()
    }

    // ---- input: keep mice and touchpads on the external display ----

    private val im: Any by lazy {
        val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "input") as IBinder
        Class.forName("android.hardware.input.IInputManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, b)!!
    }

    private fun displayUniqueId(displayId: Int): String? {
        val g = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)
        val info = g.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(g, displayId)
        return field(info, "uniqueId") as? String
    }

    /** displayId < 0 removes all associations made by this server. Returns the number of pointer devices bound. */
    private fun mouse(displayId: Int): String {
        val remove = im.javaClass.getMethod("removeUniqueIdAssociationByDescriptor", String::class.java)
        synchronized(associated) {
            if (displayId < 0) {
                for (d in associated) try { remove.invoke(im, d) } catch (_: Throwable) {}
                val n = associated.size
                associated.clear()
                return n.toString()
            }
            val unique = displayUniqueId(displayId) ?: throw IllegalStateException("no display $displayId")
            val add = im.javaClass.getMethod("addUniqueIdAssociationByDescriptor", String::class.java, String::class.java)
            val ids = im.javaClass.getMethod("getInputDeviceIds").invoke(im) as IntArray
            var n = 0
            for (id in ids) {
                val dev = im.javaClass.getMethod("getInputDevice", Int::class.javaPrimitiveType).invoke(im, id) as? InputDevice ?: continue
                if (!dev.isExternal) continue
                val pointer = dev.supportsSource(InputDevice.SOURCE_MOUSE) || dev.supportsSource(InputDevice.SOURCE_TOUCHPAD)
                if (!pointer) continue
                add.invoke(im, dev.descriptor, unique)
                associated.add(dev.descriptor)
                n++
            }
            return n.toString()
        }
    }

    // ---- phone panel power (the monitor keeps running) ----

    private fun panel(mode: Int) {
        val unique = displayUniqueId(0) ?: throw IllegalStateException("no default display")
        val physicalId = unique.substringAfter("local:").toLong()
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val cl = factory.getDeclaredMethod(
            "createClassLoader", String::class.java, String::class.java, String::class.java,
            ClassLoader::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java
        ).invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
        val dc = cl.loadClass("com.android.server.display.DisplayControl")
        val load = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        load.isAccessible = true
        try { load.invoke(Runtime.getRuntime(), dc, "android_servers") } catch (_: Throwable) {}
        val token = dc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, physicalId) as IBinder
        Class.forName("android.view.SurfaceControl")
            .getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType).invoke(null, token, mode)
    }

    /**
     * HyperOS sometimes gives the phone the external display's navigation bar (no recents button).
     * Flipping the gesture-navigation setting and restoring it makes SystemUI rebuild the bar.
     */
    private fun fixNav(): String {
        val out = sh("dumpsys window windows | grep -A3 -E 'Window\\{.* NavigationBar' | grep -E 'Window #|mDisplayId'")
        var name = ""
        var broken = false
        for (line in out.lines()) {
            Regex("NavigationBar(\\d+)").find(line)?.let { name = it.groupValues[1] }
            Regex("mDisplayId=(\\d+)").find(line)?.let { if (it.groupValues[1] == "0" && name != "0") broken = true }
        }
        if (!broken) return "ok"
        val cur = sh("settings get global force_fsg_nav_bar").trim().let { if (it == "1") "1" else "0" }
        sh("settings put global force_fsg_nav_bar ${if (cur == "1") "0" else "1"}")
        Thread.sleep(1500)
        sh("settings put global force_fsg_nav_bar $cur")
        return "fixed"
    }

    // ---- phone as touchpad: a virtual mouse the system treats like a real one ----

    /**
     * Injected motion events do not move the on-screen pointer, so we register a genuine input device through
     * the platform's `uinput` tool (the shell user may do this) and feed it relative motion.
     */
    object Pointer {
        const val MOVE = 0; const val SCROLL = 1; const val BUTTON = 2
        private var proc: Process? = null
        private var out: java.io.Writer? = null

        @Synchronized
        fun start(displayId: Int): String {
            if (proc?.isAlive != true) {
                val p = ProcessBuilder("uinput", "-").redirectErrorStream(true).start()
                proc = p
                out = p.outputStream.writer()
                thread(isDaemon = true) { try { p.inputStream.copyTo(java.io.OutputStream.nullOutputStream()) } catch (_: Throwable) {} }
                // EV_KEY + EV_REL; left/right/middle buttons; X, Y, horizontal wheel, wheel.
                write("""{"id":1,"command":"register","name":"HyperDesk Touchpad","vid":6353,"pid":20107,"bus":"usb","configuration":[{"type":100,"data":[1,2]},{"type":101,"data":[272,273,274]},{"type":102,"data":[0,1,6,8]}]}""")
                Thread.sleep(900)
            }
            return mouse(displayId)
        }

        @Synchronized
        fun stop() {
            try { out?.close() } catch (_: Throwable) {}
            try { proc?.destroy() } catch (_: Throwable) {}
            proc = null
            out = null
        }

        @Synchronized
        private fun write(line: String) {
            val o = out ?: return
            try {
                o.write(line)
                o.write("\n")
                o.flush()
            } catch (t: Throwable) {
                stop()
            }
        }

        fun event(kind: Int, a: Int, b: Int) {
            val ev = when (kind) {
                MOVE -> "2,0,$a,2,1,$b,0,0,0"
                SCROLL -> buildString {
                    if (a != 0) append("2,8,$a,")
                    if (b != 0) append("2,6,$b,")
                    append("0,0,0")
                }
                BUTTON -> "1,$a,$b,0,0,0"
                else -> return
            }
            write("""{"id":1,"command":"inject","events":[$ev]}""")
        }
    }

    // ---- keys and text for a specific display ----

    private val injector by lazy { im.javaClass.methods.first { it.name == "injectInputEvent" && it.parameterTypes.size == 2 } }

    private fun inject(ev: android.view.KeyEvent, displayId: Int) {
        try { ev.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(ev, displayId) } catch (_: Throwable) {}
        injector.invoke(im, ev, 0)
    }

    private fun key(displayId: Int, code: Int, meta: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        for (action in intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            inject(android.view.KeyEvent(now, now, action, code, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), displayId)
        }
    }

    /** Types text as key presses. Returns "partial" if some characters have no key (other scripts); the app then pastes instead. */
    private fun typeText(displayId: Int, text: String): String {
        val map = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = map.getEvents(text.toCharArray()) ?: return "partial"
        for (e in events) inject(android.view.KeyEvent(e), displayId)
        return "ok"
    }

    /**
     * Where the on-screen keyboard appears for text fields on a display:
     * 0 = on that display, 1 = on the phone's screen instead, 2 = nowhere. Returns the policy now in force.
     */
    private fun imePolicy(displayId: Int, set: Int?): String {
        val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
        if (set != null) {
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, displayId, set)
        }
        return wm.javaClass.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType).invoke(wm, displayId).toString()
    }

    /**
     * Reads, and optionally sets, one display's density through the window manager itself (no shell parsing, so it
     * can never act on a different display). [set] = 0 clears the override. Returns "<native> <current>".
     */
    private fun density(displayId: Int, set: Int?): String {
        if (displayId <= 0) throw IllegalArgumentException("not an external display")
        val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
        val int = Int::class.javaPrimitiveType
        val native = wm.javaClass.getMethod("getInitialDisplayDensity", int).invoke(wm, displayId) as Int
        if (native <= 0) throw IllegalStateException("display $displayId has no density")
        if (set != null) {
            // "Clearing" the override on an external display leaves it at the phone's density on HyperOS, not the
            // monitor's own, so native size is set explicitly instead.
            val value = if (set == 0) native else set.coerceIn(96, 640)
            wm.javaClass.getMethod("setForcedDisplayDensityForUser", int, int, int).invoke(wm, displayId, value, -2)
        }
        val current = wm.javaClass.getMethod("getBaseDisplayDensity", int).invoke(wm, displayId) as Int
        return "$native $current"
    }

    private fun sh(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trimEnd()
    }
}
