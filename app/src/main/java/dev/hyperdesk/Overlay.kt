package dev.hyperdesk

import android.graphics.PixelFormat
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** A Compose surface shown as an accessibility overlay window on the desk's display. */
class OverlayWindow(
    private val desk: Desk,
    private val focusable: Boolean,
    private val onOutside: (() -> Unit)? = null,
    private val touchable: Boolean = true,
    private val content: @Composable () -> Unit,
) {
    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    }

    private var view: ComposeView? = null
    private var owner: Owner? = null

    val showing get() = view != null

    fun show(width: Int, height: Int, gravity: Int, x: Int, y: Int, keepScreenOn: Boolean = false) {
        if (view != null) return
        val o = Owner()
        o.controller.performRestore(null)
        o.registry.currentState = Lifecycle.State.RESUMED
        val v = ComposeView(desk.ctx)
        v.setViewTreeLifecycleOwner(o)
        v.setViewTreeSavedStateRegistryOwner(o)
        v.setContent { DeskTheme { content() } }
        if (onOutside != null) {
            v.setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) { onOutside.invoke(); true } else false
            }
        }
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (keepScreenOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val lp = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = gravity
        lp.x = x
        lp.y = y
        lp.setFitInsetsTypes(0)
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.title = "HyperDesk"
        try {
            desk.wm.addView(v, lp)
            view = v
            owner = o
        } catch (e: Exception) {
            android.util.Log.w("HyperDesk", "addView failed", e)
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try { desk.wm.removeViewImmediate(v) } catch (_: Exception) {}
        owner?.registry?.currentState = Lifecycle.State.DESTROYED
        owner = null
    }
}
