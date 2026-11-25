package dev.hyperdesk

import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.hyperdesk.shell.ShellClient

/**
 * Android 17 (HyperOS 4) keeps a connected monitor in mirroring mode, and a mirroring display may not show app
 * windows. HyperDesk works around that without root: the helper creates a desktop display of its own (a virtual
 * display, which is allowed to host apps), and this full-screen window shows that display's picture on the monitor.
 * The desk, the mouse and the keyboard all live on the desktop display.
 *
 * The window also keeps the monitor from mirroring the phone: Android does not mirror onto a display that has
 * content of its own.
 */
class Bridge(private val svc: DeskService, val monitor: Display, private val changed: () -> Unit) {
    private val ctx = svc.createDisplayContext(monitor)
        .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
    private val wm = ctx.getSystemService(WindowManager::class.java)

    private var root: FrameLayout? = null
    private var message: TextView? = null
    private var surface: Surface? = null
    private var requesting = false

    /** The desktop display, once the helper has created it; -1 until then. */
    var displayId = -1
        private set

    private val width get() = wm.maximumWindowMetrics.bounds.width()
    private val height get() = wm.maximumWindowMetrics.bounds.height()
    private val dpi get() = ctx.resources.displayMetrics.densityDpi

    fun start() = addWindow()

    private fun addWindow() {
        val r = FrameLayout(ctx)
        r.setBackgroundColor(Color.BLACK)
        val sv = SurfaceView(ctx)
        // Above the window itself; while the desktop display has not drawn yet the surface is empty and the
        // message below shows through.
        sv.setZOrderOnTop(true)
        sv.holder.setFormat(PixelFormat.RGBA_8888)
        sv.holder.setFixedSize(width, height)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                request()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
                releaseDisplay()
            }
        })
        val msg = TextView(ctx).apply {
            setTextColor(0xFF9AA0A6.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
        }
        r.addView(msg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(sv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        message = msg
        showStatus()

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        )
        lp.setFitInsetsTypes(0)
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.title = "HyperDesk bridge"
        lp.windowAnimations = 0
        try {
            wm.addView(r, lp)
            root = r
        } catch (e: Exception) {
            android.util.Log.e("HyperDesk", "bridge window failed", e)
        }
    }

    fun stop() {
        releaseDisplay()
        removeWindow()
    }

    private fun removeWindow() {
        root?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        root = null
        surface = null
    }

    /** The helper has (re)connected: create the desktop display if we have somewhere to draw it. */
    fun onHelperConnected() {
        showStatus()
        request()
    }

    /**
     * The desktop display disappeared (the helper stopped). It is created again when the helper is back, on a fresh
     * surface: a new virtual display on the surface the old one used is not drawn on HyperOS.
     */
    fun displayLost() {
        displayId = -1
        removeWindow()
        addWindow()
    }

    private fun request() {
        val s = surface ?: return
        if (requesting || displayId > 0 || !ShellClient.connected) return
        requesting = true
        ShellClient.createDisplay(s, width, height, dpi) { id ->
            requesting = false
            displayId = id
            showStatus(if (id <= 0) "HyperDesk could not create its desktop display." else null)
            changed()
        }
    }

    private fun releaseDisplay() {
        if (displayId > 0) ShellClient.request("display_release")
        displayId = -1
        changed()
    }

    private fun showStatus(error: String? = null) {
        message?.text = when {
            error != null -> error
            displayId > 0 -> ""
            !ShellClient.connected -> "HyperDesk is waiting for its window helper.\nOpen HyperDesk on the phone to start it."
            else -> "Starting the desktop…"
        }
    }
}
