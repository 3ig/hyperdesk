package dev.hyperdesk

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect

object Prefs {
    private lateinit var sp: SharedPreferences
    lateinit var files: java.io.File
        private set

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("hyperdesk", Context.MODE_PRIVATE)
            files = ctx.applicationContext.filesDir
            C.load()
        }
    }

    var pinned: List<String>
        get() = (sp.getString("pinned", null) ?: "com.android.chrome,com.android.settings,com.android.fileexplorer,com.mi.android.globalFileexplorer")
            .split(',').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("pinned", v.joinToString(",")).apply()

    /** Bind external mice and touchpads to the monitor while it is connected. */
    var autoMouse: Boolean
        get() = sp.getBoolean("autoMouse", true)
        set(v) = sp.edit().putBoolean("autoMouse", v).apply()

    /** Force every new window on the monitor to 1:1 scale instead of Xiaomi's shrunken floating window. */
    var fullScale: Boolean
        get() = sp.getBoolean("fullScale", true)
        set(v) = sp.edit().putBoolean("fullScale", v).apply()

    /** Turn the phone panel off while the monitor is connected. */
    var panelOff: Boolean
        get() = sp.getBoolean("panelOff", false)
        set(v) = sp.edit().putBoolean("panelOff", v).apply()

    /** True while we are the ones holding Xiaomi's window dots off, so we know to turn them back on. */
    var dotsManaged: Boolean
        get() = sp.getBoolean("dotsManaged", false)
        set(v) = sp.edit().putBoolean("dotsManaged", v).apply()

    /** Interface size on the monitor, in percent of its native density. */
    var scale: Int
        get() = sp.getInt("scale", 100)
        set(v) = sp.edit().putInt("scale", v).apply()

    /** Show the phone's notifications on the monitor. */
    var notifications: Boolean
        get() = sp.getBoolean("notifications", true)
        set(v) = sp.edit().putBoolean("notifications", v).apply()

    /** Open the touchpad on the phone when a monitor is connected and no mouse is attached. */
    var autoTouchpad: Boolean
        get() = sp.getBoolean("autoTouchpad", true)
        set(v) = sp.edit().putBoolean("autoTouchpad", v).apply()

    /** Bring up the phone's keyboard when a text field is picked on the monitor and no keyboard is attached. */
    var autoKeyboard: Boolean
        get() = sp.getBoolean("autoKeyboard", false)
        set(v) = sp.edit().putBoolean("autoKeyboard", v).apply()

    /** HyperDesk has paired with the phone's Wireless debugging at least once. */
    var paired: Boolean
        get() = sp.getBoolean("paired", false)
        set(v) { if (paired != v) sp.edit().putBoolean("paired", v).apply() }

    /** The first-run setup guide has been finished or skipped. */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var accent: Int
        get() = sp.getInt("accent", 0xFFFF6900.toInt())
        set(v) = sp.edit().putInt("accent", v).apply()

    var taskbarLeft: Boolean
        get() = sp.getBoolean("taskbarLeft", false)
        set(v) = sp.edit().putBoolean("taskbarLeft", v).apply()

    /** Switch the phone to do-not-disturb while a monitor is connected. */
    var dockDnd: Boolean
        get() = sp.getBoolean("dockDnd", false)
        set(v) = sp.edit().putBoolean("dockDnd", v).apply()

    /** Navigation settings as they were before full screen changed them ("" = nothing to restore). */
    var navSaved: String
        get() = sp.getString("navSaved", "") ?: ""
        set(v) = sp.edit().putString("navSaved", v).apply()

    var dndManaged: Boolean
        get() = sp.getBoolean("dndManaged", false)
        set(v) = sp.edit().putBoolean("dndManaged", v).apply()

    /** Apps the user last left maximized open maximized again. */
    fun maximized(pkg: String) = sp.getBoolean("m:$pkg", false)
    fun setMaximized(pkg: String, v: Boolean) { if (maximized(pkg) != v) sp.edit().putBoolean("m:$pkg", v).apply() }

    /** -1 = the user's own photo (stored as wallpaper.jpg), otherwise a built-in preset. */
    var wallpaper: Int
        get() = sp.getInt("wallpaper", 0)
        set(v) = sp.edit().putInt("wallpaper", v).apply()

    fun bounds(pkg: String): Rect? = sp.getString("b3:$pkg", null)?.let(Rect::unflattenFromString)

    fun saveBounds(pkg: String, r: Rect) = sp.edit().putString("b3:$pkg", r.flattenToString()).apply()
}
