package dev.hyperdesk

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

class AppEntry(val pkg: String, val label: String, drawable: Drawable) {
    val icon: ImageBitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888).also {
        drawable.setBounds(0, 0, 144, 144)
        drawable.draw(Canvas(it))
    }.asImageBitmap()
}

object Apps {
    var all: List<AppEntry> by androidx.compose.runtime.mutableStateOf(emptyList())
        private set
    private val byPkg = HashMap<String, AppEntry>()

    @Volatile private var loading = false

    /**
     * (Re)reads the launchable apps. Entries already known are kept as they are (their icons are the expensive
     * part), so this is cheap enough to run whenever something might have changed. [changed] names a package
     * whose label or icon should be read again, such as one that was just updated.
     */
    fun load(ctx: Context, changed: String? = null, done: (() -> Unit)? = null) {
        if (loading) return
        loading = true
        val app = ctx.applicationContext
        thread {
            try {
                val pm = app.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val known = synchronized(byPkg) { HashMap(byPkg) }
                if (changed != null) known.remove(changed)
                val list = pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }.distinct()
                    .filter { it != app.packageName }
                    .mapNotNull { pkg ->
                        known[pkg] ?: try {
                            val ai = pm.getApplicationInfo(pkg, 0)
                            AppEntry(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
                        } catch (e: Exception) { null }
                    }
                    .sortedBy { it.label.lowercase() }
                synchronized(byPkg) {
                    byPkg.clear()
                    list.forEach { byPkg[it.pkg] = it }
                }
                all = list
                done?.invoke()
            } finally {
                loading = false
            }
        }
    }

    fun get(ctx: Context, pkg: String): AppEntry? {
        synchronized(byPkg) { byPkg[pkg]?.let { return it } }
        return try {
            val pm = ctx.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            AppEntry(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai)).also {
                synchronized(byPkg) { byPkg[pkg] = it }
            }
        } catch (e: Exception) { null }
    }
}
