package dev.hyperdesk

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.hyperdesk.shell.ShellClient
import dev.hyperdesk.shell.Task

/**
 * HyperDesk's window frames: title bars (every window), window buttons, resize handles (top window) and all
 * window transitions.
 *
 * Nothing waits on the system while the pointer moves. A drag is drawn in-process on the full-screen
 * [StageView] from a snapshot of the window, while the real window is parked off-screen; it is put back once,
 * on release. Size changes are covered by the snapshot until the app has redrawn at its new size.
 */
class Decor(private val desk: Desk) {
    private val ctx: Context get() = desk.ctx
    private val wm get() = desk.wm
    private fun dp(v: Int) = desk.dp(v)
    private val handler = Handler(Looper.getMainLooper())
    val barH get() = dp(38)

    /** The title bar sits above the app's window: "frame" is the app rectangle plus that bar. */
    private fun frameOf(body: Rect) = Rect(body.left, body.top - barH, body.right, body.bottom)
    private fun bodyOf(frame: Rect) = Rect(frame.left, frame.top + barH, frame.right, frame.bottom)

    private class Pending(val frame: Rect, val deadline: Long)

    private var wins: List<Task> = emptyList()       // top-most first, as last reported by the system
    private val pending = HashMap<Int, Pending>()    // frames we have applied that the system has not confirmed yet
    private var raised = -1                          // window we raised ourselves, ahead of the system reporting it
    private var raisedUntil = 0L
    private var liftedId = -1                        // window currently represented only by the stage proxy
    private var busy = false                         // a pointer gesture is in progress
    private var holdId = -1                          // proxy stays up until this window is confirmed in place
    private var hoverId = -1
    private var hover = -1

    private val bars = HashMap<Int, BarHit>()
    private val handles = ArrayList<Pair<Int, View>>()
    private var stage: StageView? = null
    private val painter by lazy { BarPainter(ctx.resources.displayMetrics.density) }

    private companion object {
        const val L = 1; const val R = 2; const val B = 4
    }

    // ---- lifecycle ----

    fun start() {
        if (stage != null) return
        val s = StageView(ctx, painter, barH, desk.deskArea)
        // The stage is the one window that is always there (the taskbar goes away in full screen), so it keeps the monitor awake.
        val lp = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, 0, 0, touchable = false)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        wm.addView(s, lp)
        stage = s
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        bars.values.forEach(::remove)
        bars.clear()
        handles.forEach { remove(it.second) }
        handles.clear()
        stage?.let { remove(it) }
        stage = null
    }

    fun reloadWallpaper() { stage?.reloadWallpaper() }

    /** [list] are the visible windows on the desk, top-most first. */
    fun update(list: List<Task>) {
        wins = list
        val now = SystemClock.uptimeMillis()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val t = list.firstOrNull { w -> w.id == e.key }
            if (t == null || t.bounds == bodyOf(e.value.frame) || now > e.value.deadline) it.remove()
        }
        if (raised != -1 && (list.firstOrNull()?.id == raised || now > raisedUntil || list.none { w -> w.id == raised })) raised = -1
        if (holdId != -1 && holdId !in pending) releaseProxy()
        relayout()
    }

    /** Windows to draw and hit-test, top-most first. A window parked off-screen mid-transition only counts once it has a pending frame. */
    private fun ordered(): List<Task> {
        val shown = wins.filter { it.id in pending || it.bounds.left < desk.screen.width() }
        val r = shown.firstOrNull { it.id == raised } ?: return shown
        return listOf(r) + shown.filter { it.id != raised }
    }

    /** True while a transition of ours has [id] parked off-screen or waiting to be confirmed. */
    fun owns(id: Int) = busy || id == liftedId || id == holdId || id in pending || SystemClock.uptimeMillis() < ownedUntil

    private var ownedUntil = 0L

    private fun find(id: Int) = wins.firstOrNull { it.id == id }
    /** Windows we draw a frame for. A full-screen window gets none. */
    private fun managed(t: Task) = t.mode == 6 && !desk.isFullscreen(t.id)
    private fun frame(t: Task): Rect = pending[t.id]?.frame ?: if (managed(t)) frameOf(t.bounds) else t.bounds
    private fun top(): Task? = ordered().firstOrNull { it.id != liftedId }?.takeIf(::managed)

    private fun relayout() {
        pushScene()
        if (!busy) { layoutBars(); layoutHandles() }
    }

    private fun pushScene() {
        val list = ordered()
        val topId = list.firstOrNull()?.id
        stage?.setScene(
            list.reversed().filter { it.id != liftedId }.map { t ->
                val app = Apps.get(ctx, t.pkg)
                val r = frame(t)
                StageView.Win(
                    r, app?.label ?: t.pkg, app?.icon?.asAndroidBitmap(), t.id == topId, plain = !managed(t),
                    hover = if (t.id == hoverId) hover else -1, maximized = managed(t) && desk.isMaximized(bodyOf(r)),
                )
            },
        )
    }

    private fun remove(v: View) = try { wm.removeViewImmediate(v) } catch (_: Exception) {}

    private fun params(w: Int, h: Int, x: Int, y: Int, touchable: Boolean = true): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x
            this.y = y
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "HyperDesk frame"
            windowAnimations = 0
        }
    }

    // ---- hit targets ----

    /** The widest part of [bar] that no window stacked above it covers. */
    private fun visibleSpan(bar: Rect, above: List<Rect>): Pair<Int, Int>? {
        var segs = listOf(maxOf(0, bar.left) to minOf(desk.screen.width(), bar.right))
        for (o in above) {
            if (o.bottom <= bar.top || o.top >= bar.bottom) continue
            segs = segs.flatMap { (a, b) ->
                buildList {
                    if (o.left > a) add(a to minOf(b, o.left))
                    if (o.right < b) add(maxOf(a, o.right) to b)
                }
            }
        }
        return segs.filter { it.second - it.first >= dp(40) }.maxByOrNull { it.second - it.first }
    }

    private fun layoutBars() {
        val list = ordered()
        val want = HashMap<Int, Rect>()
        val offsets = HashMap<Int, Int>()
        for ((i, t) in list.withIndex()) {
            if (!managed(t) || t.id == liftedId || !desk.helper) continue
            val f = frame(t)
            val barRect = Rect(f.left, f.top, f.right, f.top + barH)
            val span = visibleSpan(barRect, list.subList(0, i).map(::frame)) ?: continue
            want[t.id] = Rect(span.first, barRect.top, span.second, barRect.bottom)
            offsets[t.id] = span.first - f.left
        }
        val gone = bars.keys - want.keys
        for (id in gone) bars.remove(id)?.let(::remove)
        for ((id, r) in want) {
            val f = frame(list.first { it.id == id })
            val existing = bars[id]
            try {
                if (existing == null) {
                    val v = BarHit(id)
                    v.place(r, offsets[id]!!, f.width())
                    wm.addView(v, params(r.width(), r.height(), r.left, r.top))
                    bars[id] = v
                } else if (existing.at != r || existing.fullWidth != f.width() || existing.offsetX != offsets[id]) {
                    existing.place(r, offsets[id]!!, f.width())
                    wm.updateViewLayout(existing, params(r.width(), r.height(), r.left, r.top))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun layoutHandles() {
        val t = top()
        val show = t != null && desk.helper && !desk.isMaximized(bodyOf(frame(t)))
        if (!show) {
            handles.forEach { it.second.visibility = View.GONE }
            return
        }
        if (handles.isEmpty()) {
            for (edge in intArrayOf(L, R, B, L or B, R or B)) {
                val v = View(ctx)
                v.pointerIcon = PointerIcon.getSystemIcon(
                    ctx,
                    when (edge) {
                        L, R -> PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
                        B -> PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
                        L or B -> PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
                        else -> PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
                    },
                )
                v.setOnTouchListener(ResizeListener(edge))
                try { wm.addView(v, params(1, 1, 0, 0)) } catch (_: Exception) {}
                handles.add(edge to v)
            }
        }
        val rect = frame(t!!)
        val th = dp(10)
        val c = dp(24)
        for ((edge, v) in handles) {
            val p = when (edge) {
                L -> params(th, rect.height() - barH - c, rect.left - th / 2, rect.top + barH)
                R -> params(th, rect.height() - barH - c, rect.right - th / 2, rect.top + barH)
                B -> params(rect.width() - 2 * c, th, rect.left + c, rect.bottom - th / 2)
                L or B -> params(c, c, rect.left - th / 2, rect.bottom - c + th / 2)
                else -> params(c, c, rect.right - c + th / 2, rect.bottom - c + th / 2)
            }
            v.visibility = View.VISIBLE
            try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
        }
    }

    // ---- shared transition plumbing ----

    /** Somewhere far to the right of the screen, same size (so the app does not re-lay out). */
    private fun park(t: Task) {
        ownedUntil = SystemClock.uptimeMillis() + 1500
        val b = t.bounds
        val x = desk.screen.width() * 2
        ShellClient.request("resize", t.id, x, b.top, x + b.width(), b.bottom)
    }

    private fun label(t: Task) = Apps.get(ctx, t.pkg)?.label ?: t.pkg
    private fun icon(t: Task) = Apps.get(ctx, t.pkg)?.icon?.asAndroidBitmap()

    /** The proxy now stands in for [id] at [target] until the system reports the window there. */
    private fun holdAt(id: Int, target: Rect) {
        pending[id] = Pending(Rect(target), SystemClock.uptimeMillis() + 800)
        holdId = id
        handler.postDelayed({ if (holdId == id) releaseProxy() }, 850)
    }

    private fun releaseProxy() {
        holdId = -1
        // Give the app a moment to draw at its new size, then cross-fade to the real window.
        handler.postDelayed({ if (holdId == -1 && !busy) stage?.fadeOut(110) }, 90)
    }

    /** Bring [t] to the front right away in our own picture, and ask the system to do the same. */
    private fun raise(t: Task) {
        raised = t.id
        raisedUntil = SystemClock.uptimeMillis() + 500
        desk.activate(t)
        relayout()
    }

    /**
     * Move/resize [t] to [targetBody] with a short zoom: the snapshot animates to the new place while the real
     * window waits off-screen, then the real window is put there and the snapshot fades once it has redrawn.
     */
    fun animateTo(t: Task, targetBody: Rect) {
        val s = stage
        val from = frame(t)
        val to = frameOf(targetBody)
        if (s == null || find(t.id) == null || !managed(t) || busy) { desk.applyBounds(t, targetBody); return }
        ShellClient.snapshot(t.id) { shot ->
            if (shot == null || stage == null) { desk.applyBounds(t, targetBody); return@snapshot }
            liftedId = t.id
            s.showProxy(from, shot, label(t), icon(t), crop = false)
            park(t)
            pushScene()
            s.animateProxy(to, 1f, 170) {
                liftedId = -1
                holdAt(t.id, to)
                desk.applyBounds(t, targetBody)
                relayout()
            }
        }
    }

    /** Fade [t] away (towards [toward] if given), running [action] — the real minimize or close — underneath. */
    fun vanish(t: Task, toward: Rect?, action: () -> Unit) {
        val s = stage
        if (s == null || find(t.id) == null || !managed(t) || busy) { action(); return }
        val from = frame(t)
        ShellClient.snapshot(t.id) { shot ->
            if (shot == null || stage == null) { action(); return@snapshot }
            liftedId = t.id
            s.showProxy(from, shot, label(t), icon(t), crop = false)
            action()
            pushScene()
            val to = toward ?: Rect(from).apply { inset(from.width() / 40, from.height() / 40) }
            s.animateProxy(to, 0f, if (toward != null) 200 else 130) {
                s.clear()
                liftedId = -1
                relayout()
            }
        }
    }

    // ---- title bars ----

    /** Invisible hit target over the visible part of one window's title bar. The stage does the drawing. */
    private inner class BarHit(val taskId: Int) : View(ctx) {
        var at = Rect()
        var offsetX = 0
        var fullWidth = 0

        private var downX = 0f
        private var downY = 0f
        private var start = Rect()      // frame the drag is measured from
        private var origin = Rect()     // frame the window really had
        private var cur = Rect()
        private var moved = false
        private var lifted = false
        private var pressed = -1
        private var lastUp = 0L
        private var snap: Rect? = null
        private var grabbed: Task? = null   // the window as it was when the gesture began

        fun place(r: Rect, offset: Int, full: Int) { at = Rect(r); offsetX = offset; fullWidth = full }

        private fun button(e: MotionEvent) = painter.buttonAt(e.x + offsetX, fullWidth.toFloat())

        override fun onHoverEvent(e: MotionEvent): Boolean {
            val h = if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) -1 else button(e)
            val hid = if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) -1 else taskId
            if (h != hover || hid != hoverId) { hover = h; hoverId = hid; pushScene() }
            return true
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val t = (if (e.actionMasked == MotionEvent.ACTION_DOWN) find(taskId) else grabbed) ?: return false
            if (e.actionMasked == MotionEvent.ACTION_DOWN) grabbed = t
            val s = stage ?: return false
            val slop = ViewConfiguration.get(ctx).scaledTouchSlop
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
                        // Right-click: the window menu. Swallow the rest of this gesture.
                        pressed = 99
                        moved = false
                        desk.showWindowMenu(e.rawX.toInt(), e.rawY.toInt(), t)
                        return true
                    }
                    pressed = button(e)
                    downX = e.rawX; downY = e.rawY
                    origin = frame(t)
                    start = Rect(origin)
                    cur = Rect(origin)
                    moved = false
                    lifted = false
                    snap = null
                    if (ordered().firstOrNull()?.id != taskId) raise(t)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pressed >= 0) return true
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!moved && kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop) return true
                    if (!moved) {
                        moved = true
                        busy = true
                        // Dragging a maximized or snapped window pulls it back to its normal size under the pointer.
                        val normal = desk.normalSizeFor(t)?.let(::frameOf)
                        if (normal != null && (normal.width() != start.width() || normal.height() != start.height())) {
                            val fx = (downX - start.left) / start.width()
                            val left = (downX - normal.width() * fx).toInt()
                            start = Rect(left, start.top, left + normal.width(), start.top + normal.height())
                        }
                        ShellClient.snapshot(taskId) { shot ->
                            if (!busy || !moved) return@snapshot
                            lifted = true
                            liftedId = taskId
                            s.showProxy(cur, shot, label(t), icon(t), crop = true, naturalW = origin.width(), naturalH = origin.height() - barH)
                            s.moveProxy(cur, snap)
                            park(t)
                            pushScene()
                        }
                    }
                    val wa = frameOf(desk.workArea)
                    val top = (start.top + dy).coerceIn(0, wa.bottom - barH)
                    val left = (start.left + dx).coerceIn(dp(160) - start.width(), wa.right - dp(160))
                    cur = Rect(left, top, left + start.width(), top + start.height())
                    val edge = dp(3)
                    val hw = wa.width() / 2
                    val hh = wa.top + wa.height() / 2
                    val corner = dp(48)
                    val atLeft = e.rawX <= edge
                    val atRight = e.rawX >= wa.right - edge - 1
                    snap = when {
                        atLeft && e.rawY <= corner -> Rect(0, wa.top, hw, hh)
                        atLeft && e.rawY >= wa.bottom - corner -> Rect(0, hh, hw, wa.bottom)
                        atRight && e.rawY <= corner -> Rect(hw, wa.top, wa.right, hh)
                        atRight && e.rawY >= wa.bottom - corner -> Rect(hw, hh, wa.right, wa.bottom)
                        e.rawY <= edge -> wa
                        atLeft -> Rect(0, wa.top, hw, wa.bottom)
                        atRight -> Rect(hw, wa.top, wa.right, wa.bottom)
                        else -> null
                    }
                    if (lifted) s.moveProxy(cur, snap)
                }
                MotionEvent.ACTION_UP -> {
                    val now = SystemClock.uptimeMillis()
                    when {
                        pressed >= 0 -> if (button(e) == pressed) when (pressed) {
                            0 -> desk.minimize(t)
                            1 -> desk.toggleMaximize(t)
                            2 -> desk.close(t)
                        }
                        moved -> {
                            val target = snap ?: cur
                            if (snap != null) desk.rememberNormal(t, bodyOf(start))
                            busy = false
                            liftedId = -1
                            if (lifted) { s.moveProxy(target, null); holdAt(taskId, target) }
                            else pending[taskId] = Pending(Rect(target), now + 800)
                            desk.applyBounds(t, bodyOf(target))
                            relayout()
                        }
                        now - lastUp < 350 -> desk.toggleMaximize(t)
                    }
                    lastUp = if (moved || pressed >= 0) 0 else now
                    moved = false
                    pressed = -1
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        busy = false
                        liftedId = -1
                        if (lifted) desk.applyBounds(t, bodyOf(origin))
                        s.clear()
                        relayout()
                    }
                    moved = false
                    pressed = -1
                }
            }
            return true
        }
    }

    // ---- resizing (top window) ----

    private inner class ResizeListener(private val edge: Int) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var start = Rect()
        private var cur = Rect()
        private var id = -1
        private var shot: Bitmap? = null
        private var grabbed: Task? = null

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val s = stage ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val t = top() ?: return false
                    grabbed = t
                    id = t.id
                    downX = e.rawX; downY = e.rawY
                    start = frame(t)
                    cur = Rect(start)
                    busy = true
                    shot = null
                    ShellClient.snapshot(id) { b -> if (busy) shot = b }
                    s.outline(cur)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    val minW = dp(360)
                    val minH = dp(240)
                    val wa = desk.workArea
                    cur = Rect(start)
                    if (edge and L != 0) cur.left = minOf(start.left + dx, start.right - minW)
                    if (edge and R != 0) cur.right = maxOf(start.right + dx, start.left + minW)
                    if (edge and B != 0) cur.bottom = maxOf(start.bottom + dy, start.top + minH).coerceAtMost(wa.bottom)
                    s.outline(cur)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    busy = false
                    s.clearOutline()
                    val t = grabbed
                    if (t != null && cur != start && e.actionMasked == MotionEvent.ACTION_UP) {
                        // Cover the window with what it showed before, cropped to the new size, until it has redrawn.
                        s.showProxy(cur, shot, label(t), icon(t), crop = true, naturalW = start.width(), naturalH = start.height() - barH)
                        holdAt(id, cur)
                        desk.applyBounds(t, bodyOf(cur))
                    }
                    shot = null
                    relayout()
                }
            }
            return true
        }
    }
}

/** Draws the title bar; shared by the real bar and the drag preview so they look identical. */
class BarPainter(private val d: Float) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1E24.toInt() }
    private val line = Paint().apply { color = 0x1FFFFFFF }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F3F5.toInt(); textSize = 13.5f * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f * d; strokeCap = Paint.Cap.ROUND }
    private val hoverBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val btnW = 46f * d

    /** 0 = minimize, 1 = maximize, 2 = close, -1 = none. */
    fun buttonAt(x: Float, width: Float): Int {
        val i = ((x - (width - 3 * btnW - 4 * d)) / btnW).toInt()
        return if (x >= width - 3 * btnW - 4 * d && i in 0..2) i else -1
    }

    fun draw(
        c: Canvas, l: Float, t: Float, w: Float, h: Float, label: String, icon: Bitmap?, maximized: Boolean,
        radius: Float, hover: Int, active: Boolean = true,
    ) {
        text.color = if (active) 0xFFF2F3F5.toInt() else 0xFF8B9198.toInt()
        bg.color = if (active) 0xFF1B1E24.toInt() else 0xFF15171B.toInt()
        path.reset()
        path.addRoundRect(l, t, l + w, t + h, floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f), Path.Direction.CW)
        c.drawPath(path, bg)
        c.drawRect(l, t + h - d, l + w, t + h, line)
        val cy = t + h / 2
        var x = l + 14 * d + radius * 0.2f
        if (icon != null) {
            val s = 22 * d
            c.drawBitmap(icon, null, RectF(x, cy - s / 2, x + s, cy + s / 2), bmp)
            x += s + 11 * d
        }
        val room = w - (x - l) - 3 * btnW - 16 * d
        if (room > 20 * d) {
            val shown = TextUtils.ellipsize(label, android.text.TextPaint(text), room, TextUtils.TruncateAt.END).toString()
            c.drawText(shown, x, cy - (text.descent() + text.ascent()) / 2, text)
        }
        val s = 5 * d
        for (i in 0..2) {
            val bx = l + w - 4 * d - (3 - i) * btnW
            val cx = bx + btnW / 2
            if (hover == i) {
                hoverBg.color = if (i == 2) 0xFFE5484D.toInt() else 0x24FFFFFF
                c.drawRoundRect(bx + 3 * d, t + 9 * d, bx + btnW - 3 * d, t + h - 9 * d, 8 * d, 8 * d, hoverBg)
            }
            glyph.color = if (hover == 2 && i == 2) Color.WHITE else if (active) 0xFFDDE0E4.toInt() else 0xFF7A8088.toInt()
            when (i) {
                0 -> c.drawLine(cx - s, cy, cx + s, cy, glyph)
                1 -> if (maximized) {
                    c.drawRoundRect(cx - s, cy - s + 2 * d, cx + s - 2 * d, cy + s, 1.5f * d, 1.5f * d, glyph)
                    c.drawLine(cx - s + 2.5f * d, cy - s, cx + s, cy - s, glyph)
                    c.drawLine(cx + s, cy - s, cx + s, cy + s - 2.5f * d, glyph)
                } else c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, 1.5f * d, 1.5f * d, glyph)
                2 -> {
                    c.drawLine(cx - s, cy - s, cx + s, cy + s, glyph)
                    c.drawLine(cx + s, cy - s, cx - s, cy + s, glyph)
                }
            }
        }
    }
}

/**
 * Full-screen, click-through layer above the apps. It paints everything that makes the monitor look like a
 * desktop: wallpaper wherever no window is, shadows, borders and title bars for the windows (clipped by
 * whatever is stacked above them), and every window transition.
 */
class StageView(ctx: Context, private val painter: BarPainter, private val barH: Int, private val area: Rect) : View(ctx) {
    /** [plain] windows are not managed by us yet: keep the wallpaper off them but draw no chrome. */
    class Win(
        val rect: Rect, val label: String, val icon: Bitmap?, val top: Boolean, val plain: Boolean = false,
        val hover: Int = -1, val maximized: Boolean = false,
    )

    private val d = ctx.resources.displayMetrics.density
    private var wins: List<Win> = emptyList()

    // The proxy: a stand-in for one window, drawn from its snapshot.
    private var proxy: RectF? = null
    private var shot: Bitmap? = null
    private var crop = false
    private var naturalW = 0
    private var naturalH = 0
    private var alphaP = 1f
    private var label = ""
    private var icon: Bitmap? = null
    private var anim: ValueAnimator? = null

    private var snap: Rect? = null
    private var outline: Rect? = null

    private var wallpaper: Bitmap = Wallpaper.render(area.width(), area.height(), Prefs.wallpaper)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF16181C.toInt() }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); setShadowLayer(30 * d, 0f, 10 * d, 0x8C000000.toInt()) }
    private val lift = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); setShadowLayer(48 * d, 0f, 18 * d, 0xB3000000.toInt()) }
    private val border = Paint().apply { color = 0x2EFFFFFF; style = Paint.Style.STROKE; strokeWidth = d }
    private val ghostFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (Prefs.accent and 0x00FFFFFF) or 0x24000000 }
    private val ghostLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Prefs.accent; style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val sizeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * d; textAlign = Paint.Align.CENTER }
    private val f = RectF()

    fun reloadWallpaper() {
        ghostFill.color = (Prefs.accent and 0x00FFFFFF) or 0x24000000
        ghostLine.color = Prefs.accent
        wallpaper = Wallpaper.render(area.width(), area.height(), Prefs.wallpaper)
        invalidate()
    }

    fun setScene(w: List<Win>) { wins = w; invalidate() }

    /**
     * Show the stand-in at [r]. With [crop] the snapshot keeps its natural size ([naturalW] x [naturalH]) from
     * the top-left corner, as apps mostly lay out from there; without it the snapshot stretches to fit.
     */
    fun showProxy(r: Rect, snapshot: Bitmap?, label: String, icon: Bitmap?, crop: Boolean, naturalW: Int = 0, naturalH: Int = 0) {
        anim?.cancel()
        proxy = RectF(r)
        shot = snapshot
        this.label = label; this.icon = icon; this.crop = crop
        this.naturalW = naturalW; this.naturalH = naturalH
        alphaP = 1f
        invalidate()
    }

    fun moveProxy(r: Rect, snap: Rect?) {
        proxy?.set(r)
        this.snap = snap?.let(::Rect)
        invalidate()
    }

    fun animateProxy(to: Rect, alphaTo: Float, ms: Long, onEnd: () -> Unit) {
        val from = RectF(proxy ?: return onEnd())
        val a0 = alphaP
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val k = it.animatedValue as Float
                proxy?.set(
                    from.left + (to.left - from.left) * k, from.top + (to.top - from.top) * k,
                    from.right + (to.right - from.right) * k, from.bottom + (to.bottom - from.bottom) * k,
                )
                alphaP = a0 + (alphaTo - a0) * k
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) onEnd() }
            })
            start()
        }
    }

    fun fadeOut(ms: Long) {
        val p = proxy ?: return
        animateProxy(Rect(p.left.toInt(), p.top.toInt(), p.right.toInt(), p.bottom.toInt()), 0f, ms) { clear() }
    }

    fun outline(r: Rect) { outline = Rect(r); invalidate() }
    fun clearOutline() { outline = null; invalidate() }

    fun clear() {
        anim?.cancel()
        proxy = null; snap = null; outline = null; shot = null
        alphaP = 1f
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        // Wallpaper everywhere above the taskbar that no window covers.
        c.save()
        c.clipRect(area)
        for (w in wins) c.clipOutRect(w.rect)
        c.drawBitmap(wallpaper, area.left.toFloat(), area.top.toFloat(), null)
        c.restore()

        // Chrome for each window, bottom-most first, never painting over a window stacked above it.
        for ((i, w) in wins.withIndex()) {
            if (w.plain) continue
            c.save()
            c.clipRect(area)
            for (j in i + 1 until wins.size) c.clipOutRect(wins[j].rect)
            f.set(w.rect)
            c.save()
            c.clipOutRect(w.rect)
            c.drawRect(f, shadow)
            c.restore()
            painter.draw(c, f.left, f.top, f.width(), barH.toFloat(), w.label, w.icon, w.maximized, 0f, w.hover, active = w.top)
            f.inset(d / 2, d / 2)
            c.drawRect(f, border)
            c.restore()
        }

        snap?.let { ghost(c, it) }
        proxy?.let { p ->
            val layered = alphaP < 0.995f
            if (layered) c.saveLayerAlpha(p.left - 80 * d, p.top - 80 * d, p.right + 80 * d, p.bottom + 100 * d, (alphaP * 255).toInt())
            c.drawRect(p, lift)
            val top = p.top + barH
            c.drawRect(p.left, top, p.right, p.bottom, body)
            val s = shot
            if (s != null) {
                if (crop && naturalW > 0 && naturalH > 0) {
                    val w = minOf(naturalW.toFloat(), p.width())
                    val h = minOf(naturalH.toFloat(), p.bottom - top)
                    val src = Rect(0, 0, (s.width * w / naturalW).toInt(), (s.height * h / naturalH).toInt())
                    c.drawBitmap(s, src, RectF(p.left, top, p.left + w, top + h), bmp)
                } else c.drawBitmap(s, null, RectF(p.left, top, p.right, p.bottom), bmp)
            } else icon?.let {
                val z = 56 * d
                c.drawBitmap(it, null, RectF(p.centerX() - z / 2, p.centerY() - z / 2, p.centerX() + z / 2, p.centerY() + z / 2), bmp)
            }
            painter.draw(c, p.left, p.top, p.width(), barH.toFloat(), label, icon, false, 0f, -1)
            f.set(p)
            f.inset(d / 2, d / 2)
            c.drawRect(f, border)
            if (layered) c.restore()
        }
        outline?.let {
            ghost(c, it)
            c.drawText("${it.width()} × ${it.height() - barH}", it.exactCenterX(), it.exactCenterY(), sizeText)
        }
    }

    private fun ghost(c: Canvas, r: Rect) {
        f.set(r)
        f.inset(d, d)
        c.drawRoundRect(f, 10 * d, 10 * d, ghostFill)
        c.drawRoundRect(f, 10 * d, 10 * d, ghostLine)
    }
}

/** Dark, low-contrast desktop backgrounds built from soft glows. */
object Wallpaper {
    val names = listOf("Ember", "Aurora", "Graphite", "Dusk")

    val photo: java.io.File get() = java.io.File(Prefs.files, "wallpaper.jpg")

    /** The user's own picture, centre-cropped to the screen and darkened a little so windows and text stand out. */
    private fun photo(w: Int, h: Int): Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(photo.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val src = android.graphics.BitmapFactory.decodeFile(photo.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val dw = src.width * scale
        val dh = src.height * scale
        val c = Canvas(out)
        c.drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawColor(0x33000000)
        out
    } catch (t: Throwable) { null }

    fun render(w: Int, h: Int, preset: Int): Bitmap {
        if (preset == -1 && photo.exists()) photo(w, h)?.let { return it }
        // Rendered at reduced size and scaled up: it is all smooth gradients.
        val sw = maxOf(1, w / 4)
        val sh = maxOf(1, h / 4)
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        fun base(a: Int, b: Int) {
            p.shader = android.graphics.LinearGradient(0f, 0f, sw.toFloat(), sh.toFloat(), a, b, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), p)
        }
        fun glow(cx: Float, cy: Float, r: Float, color: Int) {
            p.shader = android.graphics.RadialGradient(sw * cx, sh * cy, sw * r, color, color and 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), p)
        }
        when (preset) {
            1 -> { base(0xFF06131A.toInt(), 0xFF04070C.toInt()); glow(0.2f, 0.9f, 0.55f, 0x5914B8A6); glow(0.85f, 0.1f, 0.5f, 0x663B82F6); glow(0.55f, 0.5f, 0.35f, 0x2610B981) }
            2 -> { base(0xFF16181C.toInt(), 0xFF0A0B0D.toInt()); glow(0.3f, 0.2f, 0.6f, 0x33FFFFFF); glow(0.8f, 0.9f, 0.5f, 0x1FFF6900) }
            3 -> { base(0xFF140B1E.toInt(), 0xFF06040A.toInt()); glow(0.15f, 0.15f, 0.5f, 0x66A855F7); glow(0.9f, 0.85f, 0.55f, 0x59EC4899); glow(0.5f, 0.6f, 0.35f, 0x263B82F6) }
            else -> { base(0xFF0B1020.toInt(), 0xFF05060A.toInt()); glow(0.22f, 0.95f, 0.55f, 0x59FF6900); glow(0.85f, 0.05f, 0.5f, 0x664A3AFF); glow(0.55f, 0.55f, 0.4f, 0x26147A8C) }
        }
        return Bitmap.createScaledBitmap(small, w, h, true)
    }
}
