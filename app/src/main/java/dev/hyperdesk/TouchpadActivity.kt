package dev.hyperdesk

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.hyperdesk.shell.ShellClient
import dev.hyperdesk.shell.ShellServer.Pointer

/**
 * Turns the phone's screen into a touchpad and keyboard for the monitor.
 * One finger moves the pointer, tap clicks, double-tap-and-hold drags, two fingers scroll, two-finger tap right-clicks.
 */
class TouchpadActivity : Activity() {
    companion object {
        private const val BTN_LEFT = 272
        private const val BTN_RIGHT = 273

        @Volatile private var instance: TouchpadActivity? = null

        /** Bring the touchpad to the front on the phone with its keyboard up. */
        fun showKeyboard(ctx: Context) {
            val a = instance
            if (a != null && a.resumed) a.setKeyboard(true) else open(ctx, keyboard = true)
        }

        fun hideKeyboard() { instance?.setKeyboard(false) }

        fun open(ctx: Context, keyboard: Boolean = false) {
            val i = Intent(ctx, TouchpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("keyboard", keyboard)
            val o = ActivityOptions.makeBasic()
            o.launchDisplayId = 0
            try { ctx.startActivity(i, o.toBundle()) } catch (_: Exception) {}
        }
    }

    private lateinit var typer: EditText
    private var status: TextView? = null
    private var dim = true
    private var resumed = false
    private var wantKeyboard = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("keyboard", false)) { wantKeyboard = true; if (resumed) setKeyboard(true) }
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun setKeyboard(show: Boolean) {
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) {
            typer.requestFocus()
            // The window may have only just gained focus; the keyboard request is ignored before that.
            typer.postDelayed({ imm.showSoftInput(typer, 0) }, 120)
        } else {
            imm.hideSoftInputFromWindow(typer.windowToken, 0)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun desk() = DeskService.instance?.desk

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        instance = this
        wantKeyboard = intent.getBooleanExtra("keyboard", false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF000000.toInt())
        root.setPadding(dp(12), dp(12), dp(12), dp(12))

        val pad = PadView(this)
        root.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        fun button(text: String, weight: Float, onTouch: ((Boolean) -> Unit)? = null, onClick: (() -> Unit)? = null): TextView {
            val b = TextView(this)
            b.text = text
            b.gravity = Gravity.CENTER
            b.setTextColor(0xFFB8BDC6.toInt())
            b.textSize = 14f
            b.background = GradientDrawable().apply { setColor(0xFF15171B.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0x24FFFFFF) }
            if (onTouch != null) b.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.alpha = 0.6f; onTouch(true) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; onTouch(false) }
                }
                true
            }
            if (onClick != null) b.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(0, dp(58), weight)
            lp.setMargins(dp(4), dp(10), dp(4), 0)
            buttons.addView(b, lp)
            return b
        }
        // Hold these for a drag or a held right button, like the buttons under a laptop touchpad.
        button("Left", 2f, onTouch = { down -> ShellClient.pointer(Pointer.BUTTON, BTN_LEFT, if (down) 1 else 0) })
        button("Right", 2f, onTouch = { down -> ShellClient.pointer(Pointer.BUTTON, BTN_RIGHT, if (down) 1 else 0) })
        button("Keyboard", 1.6f, onClick = ::toggleKeyboard)
        button("Dim", 1f, onClick = { dim = !dim; applyDim() })
        root.addView(buttons)

        // Off-screen text field that only exists to bring up the phone's keyboard and catch what is typed.
        typer = EditText(this)
        typer.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typer.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        typer.setText(" ")
        typer.setSelection(1)
        typer.addTextChangedListener(object : TextWatcher {
            private var busy = false
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (busy) return
                // The field always holds one space: shorter means backspace, longer means new characters.
                val d = desk()
                if (s.isEmpty()) d?.sendKey(KeyEvent.KEYCODE_DEL)
                else if (s.length > 1) {
                    for (part in s.substring(1).split('\n').withIndex()) {
                        if (part.index > 0) d?.sendKey(KeyEvent.KEYCODE_ENTER)
                        if (part.value.isNotEmpty()) d?.typeText(part.value)
                    }
                }
                busy = true
                s.replace(0, s.length, " ")
                typer.setSelection(1)
                busy = false
            }
        })
        typer.setOnEditorActionListener { _, _, _ -> desk()?.sendKey(KeyEvent.KEYCODE_ENTER); true }
        root.addView(typer, LinearLayout.LayoutParams(1, 1))

        val frame = FrameLayout(this)
        frame.addView(root)
        status = TextView(this).apply {
            setTextColor(0xFF6B7280.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = false
        }
        frame.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { leftMargin = dp(40); rightMargin = dp(40); bottomMargin = dp(60) })
        setContentView(frame)
        // Keep clear of the status bar, the navigation buttons and the keyboard when it is up.
        frame.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        applyDim()
    }

    private fun applyDim() {
        val lp = window.attributes
        lp.screenBrightness = if (dim) 0.03f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun toggleKeyboard() =
        setKeyboard(window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true)

    override fun onResume() {
        super.onResume()
        resumed = true
        if (wantKeyboard) { wantKeyboard = false; setKeyboard(true) }
        val d = desk()
        when {
            d == null -> status?.text = "Connect a monitor to use the phone as a touchpad."
            !ShellClient.connected -> status?.text = "The window helper is not running. Start it in HyperDesk settings."
            else -> {
                status?.text = "Touchpad\n\nTap to click · double-tap and hold to drag\nTwo fingers to scroll · two-finger tap to right-click"
                ShellClient.request("pointer_start", d.id) { r -> if (r == null) status?.text = "Could not create the virtual mouse." }
            }
        }
    }

    /** The touch surface. Relative motion with a little acceleration, so slow moves are precise and flicks cover the monitor. */
    private inner class PadView(ctx: Context) : View(ctx) {
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF; style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0C0E.toInt() }
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var lastX = 0f
        private var lastY = 0f
        private var downTime = 0L
        private var moved = false
        private var fingers = 0
        private var maxFingers = 0
        private var lastTapUp = 0L
        private var dragging = false
        private var scrollX = 0f
        private var scrollY = 0f
        private var remX = 0f
        private var remY = 0f

        override fun onDraw(c: Canvas) {
            val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
            c.drawRoundRect(r, dp(22).toFloat(), dp(22).toFloat(), fill)
            r.inset(dp(1).toFloat(), dp(1).toFloat())
            c.drawRoundRect(r, dp(22).toFloat(), dp(22).toFloat(), outline)
        }

        private fun centroid(e: MotionEvent): Pair<Float, Float> {
            var x = 0f; var y = 0f
            for (i in 0 until e.pointerCount) { x += e.getX(i); y += e.getY(i) }
            return x / e.pointerCount to y / e.pointerCount
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val now = SystemClock.uptimeMillis()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y
                    downTime = now
                    moved = false
                    fingers = 1; maxFingers = 1
                    remX = 0f; remY = 0f
                    // A second touch right after a tap starts a drag.
                    if (now - lastTapUp < 280) {
                        dragging = true
                        ShellClient.pointer(Pointer.BUTTON, BTN_LEFT, 1)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    fingers = e.pointerCount - if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) 1 else 0
                    maxFingers = maxOf(maxFingers, e.pointerCount)
                    // Re-anchor so the pointer does not jump when the number of fingers changes.
                    val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
                    var x = 0f; var y = 0f; var n = 0
                    for (i in 0 until e.pointerCount) if (i != skip) { x += e.getX(i); y += e.getY(i); n++ }
                    if (n > 0) { lastX = x / n; lastY = y / n }
                    scrollX = 0f; scrollY = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    val (x, y) = centroid(e)
                    val dx = x - lastX
                    val dy = y - lastY
                    if (!moved && kotlin.math.hypot(e.x - lastX, e.y - lastY) < slop && e.pointerCount == 1 && kotlin.math.hypot(dx, dy) < slop) return true
                    moved = true
                    lastX = x; lastY = y
                    if (e.pointerCount >= 2) {
                        // Natural scrolling: content follows the fingers.
                        scrollX += dx; scrollY += dy
                        val step = dp(14).toFloat()
                        val v = (scrollY / step).toInt()
                        val h = (scrollX / step).toInt()
                        if (v != 0 || h != 0) {
                            ShellClient.pointer(Pointer.SCROLL, v, -h)
                            scrollY -= v * step
                            scrollX -= h * step
                        }
                    } else {
                        val speed = kotlin.math.hypot(dx, dy) / resources.displayMetrics.density
                        val gain = (1.3f + speed * 0.09f).coerceAtMost(4.2f)
                        remX += dx * gain
                        remY += dy * gain
                        val ix = remX.toInt()
                        val iy = remY.toInt()
                        if (ix != 0 || iy != 0) {
                            ShellClient.pointer(Pointer.MOVE, ix, iy)
                            remX -= ix
                            remY -= iy
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        ShellClient.pointer(Pointer.BUTTON, BTN_LEFT, 0)
                        // A quick second tap with no movement is a double-click: the press above plus this release was click two.
                        lastTapUp = 0
                    } else if (!moved && now - downTime < 260 && e.actionMasked == MotionEvent.ACTION_UP) {
                        val button = if (maxFingers >= 2) BTN_RIGHT else BTN_LEFT
                        ShellClient.pointer(Pointer.BUTTON, button, 1)
                        ShellClient.pointer(Pointer.BUTTON, button, 0)
                        lastTapUp = if (maxFingers >= 2) 0 else now
                    }
                    fingers = 0
                }
            }
            return true
        }
    }
}
