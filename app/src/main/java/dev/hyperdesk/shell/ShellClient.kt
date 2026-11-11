package dev.hyperdesk.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Rect
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import org.json.JSONArray
import java.util.concurrent.Executors

data class Task(
    val id: Int, val display: Int, val pkg: String, val visible: Boolean, val focused: Boolean,
    val bounds: Rect, val scale: Float, val mode: Int, val ffmode: Int,
)

/** App-side connection to [ShellServer]. All requests run on one background thread. */
object ShellClient {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var remote: IBinder? = null
    private val self = Binder()

    val connected get() = remote?.isBinderAlive == true

    /** Listeners told (on the main thread) when the helper appears. */
    val onConnected = ArrayList<() -> Unit>()

    fun attach(binder: IBinder) {
        remote = binder
        val data = Parcel.obtain()
        try {
            data.writeStrongBinder(self)
            binder.transact(ShellServer.TRANSACT_REGISTER, data, null, 0)
        } catch (_: Exception) {
        } finally {
            data.recycle()
        }
        main.post { onConnected.toList().forEach { it() } }
    }

    private fun requestSync(line: String): String? {
        val b = remote ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(line)
            if (!b.transact(ShellServer.TRANSACT_REQUEST, data, reply, 0)) return null
            val r = reply.readString() ?: return null
            if (r.startsWith("ok\t")) r.substring(3) else {
                android.util.Log.w("HyperDesk", "helper: $line -> $r")
                null
            }
        } catch (e: Exception) {
            if (!b.isBinderAlive) remote = null
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Sends a request; [cb] gets the payload on the main thread, or null on failure. */
    fun request(vararg parts: Any, cb: ((String?) -> Unit)? = null) {
        val line = parts.joinToString("\t")
        io.execute {
            val r = requestSync(line)
            if (cb != null) main.post { cb(r) }
        }
    }

    /** Captures what a window currently shows, for drawing a drag preview. Null if unavailable. */
    fun snapshot(taskId: Int, cb: (android.graphics.Bitmap?) -> Unit) {
        io.execute {
            val b = remote
            var bmp: android.graphics.Bitmap? = null
            if (b != null) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInt(taskId)
                    if (b.transact(ShellServer.TRANSACT_SNAPSHOT, data, reply, 0) && reply.readInt() == 1) {
                        val hb = android.hardware.HardwareBuffer.CREATOR.createFromParcel(reply)
                        bmp = android.graphics.Bitmap.wrapHardwareBuffer(hb, null)
                    }
                } catch (_: Exception) {
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
            main.post { cb(bmp) }
        }
    }

    /** One-way pointer event for the virtual mouse; cheap enough to send for every touch move. */
    fun pointer(kind: Int, a: Int, b: Int) {
        val r = remote ?: return
        val data = Parcel.obtain()
        try {
            data.writeInt(kind); data.writeInt(a); data.writeInt(b)
            r.transact(ShellServer.TRANSACT_POINTER, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            data.recycle()
        }
    }

    private val pending = HashMap<String, String>()

    /** Like [request], but while one is queued under [key] a newer one replaces it. For per-frame updates. */
    fun latest(key: String, vararg parts: Any) {
        val line = parts.joinToString("\t")
        synchronized(pending) { if (pending.put(key, line) != null) return }
        io.execute {
            val l = synchronized(pending) { pending.remove(key) } ?: return@execute
            requestSync(l)
        }
    }

    fun tasks(display: Int, cb: (List<Task>?) -> Unit) = request("tasks", display) { r ->
        cb(r?.let(::parseTasks))
    }

    private fun parseTasks(json: String): List<Task> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val b = o.optJSONArray("bounds")
            Task(
                o.getInt("id"), o.optInt("display"), o.optString("pkg"), o.optBoolean("visible"), o.optBoolean("focused"),
                if (b != null) Rect(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)) else Rect(),
                o.optDouble("scale", 1.0).toFloat(), o.optInt("mode"), o.optInt("ffmode", -1),
            )
        }
    }
}

/** Receives the helper's binder. Only the shell user (or root) may deliver it. */
class HelperProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != 2000 && uid != 0) throw SecurityException("not shell")
        if (method == "binder") {
            extras?.getBinder("binder")?.let(ShellClient::attach)
            // The helper calls us whenever our process has (re)started: make sure the taskbar service came back too.
            val ctx = context
            if (ctx != null) Handler(Looper.getMainLooper()).postDelayed({ dev.hyperdesk.Sys.ensureServiceBound(ctx) }, 3000)
        }
        // Test hook for the shell user only (checked above): drive features that need a real keyboard or mouse.
        if (method == "debug" && arg != null) {
            Handler(Looper.getMainLooper()).post { dev.hyperdesk.DeskService.instance?.desk?.debug(arg) }
        }
        return Bundle()
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
