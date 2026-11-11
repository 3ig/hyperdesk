package dev.hyperdesk

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import android.media.AudioManager
import android.view.KeyEvent
import dev.hyperdesk.shell.ShellClient
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object C {
    val bar = Color(0xFF14161A)
    val surface = Color(0xFF1B1E24)
    val field = Color(0x14FFFFFF)
    val hover = Color(0x1AFFFFFF)
    val active = Color(0x29FFFFFF)
    val stroke = Color(0x24FFFFFF)
    var accent by mutableStateOf(Color(0xFFFF6900))
        private set
    /** Text colour that reads on top of [accent]. */
    val onAccent: Color get() = if (accent.red * 0.299f + accent.green * 0.587f + accent.blue * 0.114f > 0.6f) Color.Black else Color.White
    val accents = listOf(0xFFFF6900, 0xFF3B82F6, 0xFF22C55E, 0xFFA855F7, 0xFFEC4899, 0xFF14B8A6, 0xFFEAB308).map { it.toInt() }
    fun load() { accent = Color(Prefs.accent) }
    val text = Color(0xFFF2F3F5)
    val dim = Color(0xFF9AA0A6)
    val danger = Color(0xFFFF7A7A)
    val ok = Color(0xFF4ADE80)
}

@Composable
fun DeskTheme(content: @Composable () -> Unit) = content()

/** Panels fade and rise into place from [origin] when they are shown. */
@Composable
fun Appear(origin: TransformOrigin = TransformOrigin(0.5f, 1f), content: @Composable () -> Unit) {
    val k = remember { Animatable(0f) }
    LaunchedEffect(Unit) { k.animateTo(1f, tween(170)) }
    Box(
        Modifier.graphicsLayer {
            alpha = k.value
            scaleX = 0.96f + 0.04f * k.value
            scaleY = 0.96f + 0.04f * k.value
            translationY = (1f - k.value) * 14.dp.toPx()
            transformOrigin = origin
        },
    ) { content() }
}

enum class Icon { Wifi, Bluetooth, Phone, Camera, Volume, Gear, Prev, Play, Next, Search, Globe, Equals }

/** Small line icons drawn in code, so they stay crisp at any density and need no assets. */
@Composable
fun Glyph(icon: Icon, color: Color, size: Dp = 18.dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    val h = this.size.height
    val st = Stroke(w * 0.09f, cap = StrokeCap.Round)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(color, Offset(w * x0, h * y0), Offset(w * x1, h * y1), st.width, StrokeCap.Round)
    when (icon) {
        Icon.Wifi -> {
            for ((i, r) in listOf(0.78f, 0.52f, 0.26f).withIndex()) {
                drawArc(color, 225f, 90f, false, Offset(w * (0.5f - r / 1.2f), h * (0.86f - r / 1.2f)), Size(w * r / 0.6f, h * r / 0.6f), style = st, alpha = 1f - i * 0f)
            }
            drawCircle(color, w * 0.06f, Offset(w * 0.5f, h * 0.84f))
        }
        Icon.Bluetooth -> {
            line(0.5f, 0.08f, 0.5f, 0.92f); line(0.5f, 0.08f, 0.74f, 0.3f); line(0.74f, 0.3f, 0.26f, 0.7f)
            line(0.5f, 0.92f, 0.74f, 0.7f); line(0.74f, 0.7f, 0.26f, 0.3f)
        }
        Icon.Phone -> {
            drawRoundRect(color, Offset(w * 0.27f, h * 0.06f), Size(w * 0.46f, h * 0.88f), CornerRadius(w * 0.1f), style = st)
            line(0.44f, 0.82f, 0.56f, 0.82f)
        }
        Icon.Camera -> {
            drawRoundRect(color, Offset(w * 0.08f, h * 0.26f), Size(w * 0.84f, h * 0.58f), CornerRadius(w * 0.1f), style = st)
            drawCircle(color, w * 0.15f, Offset(w * 0.5f, h * 0.55f), style = st)
            line(0.36f, 0.26f, 0.42f, 0.14f); line(0.42f, 0.14f, 0.58f, 0.14f); line(0.58f, 0.14f, 0.64f, 0.26f)
        }
        Icon.Volume -> {
            val p = Path().apply { moveTo(w * 0.1f, h * 0.38f); lineTo(w * 0.28f, h * 0.38f); lineTo(w * 0.5f, h * 0.18f); lineTo(w * 0.5f, h * 0.82f); lineTo(w * 0.28f, h * 0.62f); lineTo(w * 0.1f, h * 0.62f); close() }
            drawPath(p, color)
            drawArc(color, -45f, 90f, false, Offset(w * 0.38f, h * 0.3f), Size(w * 0.4f, h * 0.4f), style = st)
            drawArc(color, -45f, 90f, false, Offset(w * 0.3f, h * 0.14f), Size(w * 0.64f, h * 0.72f), style = st)
        }
        Icon.Gear -> {
            drawCircle(color, w * 0.14f, Offset(w / 2, h / 2), style = st)
            for (i in 0 until 8) rotate(i * 45f) { drawLine(color, Offset(w / 2, h * 0.08f), Offset(w / 2, h * 0.22f), st.width, StrokeCap.Round) }
            drawCircle(color, w * 0.3f, Offset(w / 2, h / 2), style = st)
        }
        Icon.Prev -> { drawRect(color, Offset(w * 0.12f, h * 0.2f), Size(w * 0.1f, h * 0.6f)); drawPath(Path().apply { moveTo(w * 0.9f, h * 0.2f); lineTo(w * 0.3f, h * 0.5f); lineTo(w * 0.9f, h * 0.8f); close() }, color) }
        Icon.Next -> { drawRect(color, Offset(w * 0.78f, h * 0.2f), Size(w * 0.1f, h * 0.6f)); drawPath(Path().apply { moveTo(w * 0.1f, h * 0.2f); lineTo(w * 0.7f, h * 0.5f); lineTo(w * 0.1f, h * 0.8f); close() }, color) }
        Icon.Play -> {
            drawPath(Path().apply { moveTo(w * 0.08f, h * 0.18f); lineTo(w * 0.5f, h * 0.5f); lineTo(w * 0.08f, h * 0.82f); close() }, color)
            drawRect(color, Offset(w * 0.6f, h * 0.2f), Size(w * 0.11f, h * 0.6f)); drawRect(color, Offset(w * 0.81f, h * 0.2f), Size(w * 0.11f, h * 0.6f))
        }
        Icon.Globe -> {
            drawCircle(color, w * 0.4f, Offset(w / 2, h / 2), style = st)
            drawOval(color, Offset(w * 0.32f, h * 0.1f), Size(w * 0.36f, h * 0.8f), style = st)
            line(0.1f, 0.5f, 0.9f, 0.5f)
        }
        Icon.Equals -> { line(0.2f, 0.38f, 0.8f, 0.38f); line(0.2f, 0.62f, 0.8f, 0.62f) }
        Icon.Search -> { drawCircle(color, w * 0.28f, Offset(w * 0.43f, h * 0.43f), style = st); line(0.64f, 0.64f, 0.88f, 0.88f) }
    }
}

@Composable
fun Label(
    text: String, size: TextUnit = 13.sp, color: Color = C.text, weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier, maxLines: Int = 1, align: TextAlign = TextAlign.Start,
) = BasicText(
    text, modifier, TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align),
    maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

/** Primary click, right-click (or long-press) as the secondary action, and middle-click as a third. */
fun Modifier.mouseClick(onSecondary: (() -> Unit)? = null, onTertiary: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
    pointerInput(onClick, onSecondary, onTertiary) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val secondary = currentEvent.buttons.isSecondaryPressed
            val tertiary = currentEvent.buttons.isTertiaryPressed
            var timedOut = false
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                ?: run { timedOut = true; null }
            when {
                tertiary && onTertiary != null -> onTertiary()
                (secondary || timedOut) && onSecondary != null -> onSecondary()
                up != null -> onClick()
            }
        }
    }

@Composable
fun HoverBox(
    modifier: Modifier = Modifier, selected: Boolean = false, radius: Dp = 10.dp,
    onSecondary: (() -> Unit)? = null, onTertiary: (() -> Unit)? = null, onHover: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit, content: @Composable () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    if (onHover != null) LaunchedEffect(hovered) { onHover(hovered) }
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(if (selected) C.active else if (hovered) C.hover else Color.Transparent)
            .hoverable(src)
            .mouseClick(onSecondary, onTertiary, onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---------------------------------------------------------------- taskbar

@Composable
fun TaskbarContent(desk: Desk) {
    Box(Modifier.fillMaxSize().background(C.bar)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1FFFFFFF)))
        // Start button and apps sit in the middle of the screen; the tray stays on the right.
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = if (desk.taskbarLeft) Arrangement.Start else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoverBox(Modifier.size(46.dp, 40.dp), selected = desk.menuOpen, onClick = { desk.toggleMenu() }) { StartGlyph() }
            Spacer(Modifier.width(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                desk.entries.forEach { e -> key(e.pkg) { AppButton(desk, e) } }
            }
        }
    }
    Row(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        if (!desk.helper) {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(C.accent.copy(alpha = 0.2f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                Label("Helper offline — window controls disabled", 12.sp, C.accent, FontWeight.Medium)
            }
            Spacer(Modifier.width(8.dp))
        }
        HoverBox(Modifier.size(40.dp), onClick = { desk.back() }) { BackGlyph() }
        HoverBox(Modifier.size(40.dp), onClick = { desk.showDesktop() }) { DesktopGlyph() }
        Spacer(Modifier.width(2.dp))
        HoverBox(Modifier.height(40.dp), selected = desk.quickOpen, onClick = { desk.toggleQuick() }) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (Notifs.list.isNotEmpty()) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(C.accent))
                    Spacer(Modifier.width(10.dp))
                }
                Clock()
            }
        }
    }
}

@Composable
private fun AppButton(desk: Desk, e: BarEntry) {
    val app = Apps.get(desk.ctx, e.pkg) ?: return
    var x by remember { mutableStateOf(0) }
    var hovered by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        if (hovered) { delay(450); desk.showTip(x, app.label, if (e.running) e.pkg else null) } else desk.hideTip()
    }
    val indicator by animateDpAsState(if (e.focused) 18.dp else if (e.running) 6.dp else 0.dp, label = "indicator")
    HoverBox(
        Modifier.padding(horizontal = 2.dp).size(46.dp, 42.dp).onGloballyPositioned { x = it.positionInWindow().x.toInt() },
        selected = e.focused,
        onSecondary = { desk.showPopup(x, desk.menuFor(e)) },
        onTertiary = { desk.closeApp(e.pkg) },
        onHover = { h -> hovered = h },
        onClick = { desk.dismissPopup(); desk.hideTip(); desk.onBarClick(e.pkg) },
    ) {
        val lift by animateFloatAsState(if (hovered) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "lift")
        Image(app.icon, app.label, Modifier.size(30.dp).graphicsLayer { translationY = -2.dp.toPx() * lift; scaleX = 1f + 0.08f * lift; scaleY = 1f + 0.08f * lift })
        Box(Modifier.fillMaxSize().padding(bottom = 2.dp), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.size(indicator, 3.dp).clip(RoundedCornerShape(2.dp)).background(if (e.focused) C.accent else C.dim))
        }
    }
}

@Composable
private fun StartGlyph() = Canvas(Modifier.size(20.dp)) {
    val s = size.width * 0.44f
    val g = size.width - 2 * s
    val r = CornerRadius(s * 0.28f)
    for (i in 0..3) {
        drawRoundRect(
            if (i == 0) C.accent else C.text,
            Offset((i % 2) * (s + g), (i / 2) * (s + g)), Size(s, s), r,
        )
    }
}

@Composable
private fun BackGlyph() = Canvas(Modifier.size(16.dp)) {
    val w = 1.8.dp.toPx()
    drawLine(C.text, Offset(size.width * 0.65f, size.height * 0.15f), Offset(size.width * 0.3f, size.height * 0.5f), w, StrokeCap.Round)
    drawLine(C.text, Offset(size.width * 0.3f, size.height * 0.5f), Offset(size.width * 0.65f, size.height * 0.85f), w, StrokeCap.Round)
}

@Composable
private fun DesktopGlyph() = Canvas(Modifier.size(18.dp, 14.dp)) {
    drawRoundRect(C.text, size = size, cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(1.6.dp.toPx()))
}

@Composable
private fun Clock() {
    val ctx = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    val battery by produceState(-1) {
        while (true) {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            value = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            delay(30_000)
        }
    }
    val time = remember(now / 1000) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) }
    val date = remember(now / 60000) { SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(now)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (battery >= 0) {
            Label("$battery%", 12.sp, C.dim)
            Spacer(Modifier.width(12.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Label(time, 13.sp, C.text, FontWeight.Medium)
            Label(date, 11.sp, C.dim)
        }
    }
}

// ---------------------------------------------------------------- start menu

@Composable
fun StartMenuContent(desk: Desk) {
    var q by remember { mutableStateOf("") }
    val apps = Apps.all
    val filtered = remember(q, apps) {
        if (q.isBlank()) apps else apps.filter { it.label.contains(q.trim(), ignoreCase = true) }
    }
    val extras = remember(q) { Search.results(q) }
    fun run(r: Search.Result) { desk.hideMenu(); r.action(desk) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
    val shape = RoundedCornerShape(20.dp)
    Appear {
    Column(
        Modifier.fillMaxSize().clip(shape).background(C.surface).border(1.dp, C.stroke, shape).padding(18.dp)
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.Escape -> { desk.hideMenu(); true }
                    Key.Enter, Key.NumPadEnter -> {
                        // Apps win when the text matches one; otherwise the first other result (sum, setting, web).
                        val a = if (q.isBlank()) null else filtered.firstOrNull()
                        if (a != null) desk.launch(a.pkg) else extras.firstOrNull()?.let(::run)
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.field).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Icon.Search, C.dim, 16.dp)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Label("Search apps, settings and the web, or type a sum", 15.sp, C.dim)
                BasicTextField(
                    q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    textStyle = TextStyle(color = C.text, fontSize = 15.sp), cursorBrush = SolidColor(C.accent),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (extras.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                extras.take(5).forEach { r ->
                    HoverBox(Modifier.fillMaxWidth().height(44.dp), radius = 12.dp, onClick = { run(r) }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(r.icon, C.dim, 16.dp)
                            Spacer(Modifier.width(12.dp))
                            Label(r.title, 14.sp, C.text, modifier = Modifier.weight(1f))
                            Label(r.hint, 12.sp, C.dim)
                        }
                    }
                }
            }
        }
        Label(if (q.isBlank()) "All apps" else "Apps", 12.sp, C.dim, FontWeight.Medium, Modifier.padding(start = 6.dp, bottom = 6.dp))
        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Label(if (apps.isEmpty()) "Loading apps…" else "No apps match \"$q\"", 13.sp, C.dim)
            }
        } else {
            LazyVerticalGrid(GridCells.Fixed(5), Modifier.weight(1f).fillMaxWidth()) {
                items(filtered, key = { it.pkg }) { app ->
                    HoverBox(
                        Modifier.padding(3.dp).fillMaxWidth().height(96.dp), radius = 12.dp,
                        onSecondary = { desk.togglePin(app.pkg) }, onClick = { desk.launch(app.pkg) },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                            Image(app.icon, null, Modifier.size(44.dp))
                            Spacer(Modifier.height(8.dp))
                            Label(app.label, 12.sp, C.text, maxLines = 1, align = TextAlign.Center)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("HyperDesk settings") {
                desk.hideMenu()
                desk.launch(desk.ctx.packageName)
            }
            if (desk.helper) {
                Pill(if (desk.panelOff) "Turn phone screen on" else "Turn phone screen off") { desk.setPanel(desk.panelOff) }
            }
            Spacer(Modifier.weight(1f))
            Label("Right-click an app to pin it", 11.sp, C.dim)
        }
    }
    }
}

@Composable
fun Pill(text: String, accent: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg = if (accent) C.accent else if (hovered) C.active else C.field
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(bg).hoverable(src).mouseClick(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) { Label(text, 13.sp, if (accent) C.onAccent else C.text, FontWeight.Medium) }
}

// ---------------------------------------------------------------- context menu

@Composable
fun PopupContent(items: List<MenuItem>, dismiss: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Appear(TransformOrigin(0f, 1f)) {
    Column(Modifier.width(240.dp).clip(shape).background(C.surface).border(1.dp, C.stroke, shape).padding(6.dp)) {
        items.forEach { item ->
            val src = remember { MutableInteractionSource() }
            val hovered by src.collectIsHoveredAsState()
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (hovered) C.hover else Color.Transparent)
                    .hoverable(src).mouseClick { dismiss(); item.action() }.padding(horizontal = 12.dp, vertical = 10.dp),
            ) { Label(item.label, 13.sp, if (item.danger) C.danger else C.text) }
        }
    }
    }
}

// ---------------------------------------------------------------- Alt+Tab switcher

@Composable
fun SwitcherContent(desk: Desk) {
    val shape = RoundedCornerShape(20.dp)
    Appear(TransformOrigin(0.5f, 0.5f)) {
        Row(
            Modifier.clip(shape).background(C.surface).border(1.dp, C.stroke, shape).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            desk.switcher.take(8).forEachIndexed { i, t ->
                val app = Apps.get(desk.ctx, t.pkg)
                val sel = i == desk.switcherIndex
                val cell = RoundedCornerShape(14.dp)
                val ring by animateColorAsState(if (sel) C.accent else Color.Transparent, tween(110), label = "ring")
                Column(
                    Modifier.width(236.dp).clip(cell).background(if (sel) C.active else Color.Transparent).border(2.dp, ring, cell).padding(10.dp),
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0E1013)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val p = desk.previews[t.id]
                        if (p != null) Image(p, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                        else if (app != null) Image(app.icon, null, Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (app != null) Image(app.icon, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Label(app?.label ?: t.pkg, 12.sp, if (sel) C.text else C.dim, if (sel) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- full screen

/** Invisible strip along the top edge while a window is full screen. */
@Composable
fun FullscreenEdge(desk: Desk) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) desk.offerFullscreenExit() }
    Box(Modifier.fillMaxSize().hoverable(src).mouseClick { desk.offerFullscreenExit() })
}

@Composable
fun FullscreenPill(desk: Desk) {
    Appear(TransformOrigin(0.5f, 0f)) { Pill("Exit full screen", accent = true) { desk.exitFullscreen() } }
}

// ---------------------------------------------------------------- toast and tooltip

@Composable
fun ToastContent(text: String) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.clip(shape).background(C.surface).border(1.dp, C.stroke, shape).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Label(text, 13.sp, C.text, FontWeight.Medium)
    }
}

@Composable
fun TipContent(text: String, preview: androidx.compose.ui.graphics.ImageBitmap? = null) {
    val shape = RoundedCornerShape(if (preview != null) 12.dp else 8.dp)
    Appear {
        Column(Modifier.clip(shape).background(Color(0xFF23262D)).border(1.dp, C.stroke, shape).padding(if (preview != null) 8.dp else 0.dp)) {
            if (preview != null) {
                Image(
                    preview, null, Modifier.size(240.dp, 140.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
                Spacer(Modifier.height(6.dp))
                Label(text, 12.sp, C.text, FontWeight.Medium, Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            } else {
                Label(text, 12.sp, C.text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- notifications

@Composable
fun NotifToastContent(desk: Desk) {
    val n = desk.toastNotif ?: return
    val shape = RoundedCornerShape(16.dp)
    key(n.key, n.time) {
        Appear(TransformOrigin(1f, 0f)) {
            Box(Modifier.fillMaxWidth().clip(shape).background(C.surface).border(1.dp, C.stroke, shape)) {
                NotifRow(desk, n, Modifier.padding(4.dp), big = true)
            }
        }
    }
}

@Composable
private fun NotifRow(desk: Desk, n: Notif, modifier: Modifier = Modifier, big: Boolean = false) {
    val app = Apps.get(desk.ctx, n.pkg)
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (hovered) C.hover else Color.Transparent)
            .hoverable(src).mouseClick(onSecondary = { Notifs.dismiss(n); desk.dismissNotifToast() }) { desk.openNotification(n) }
            .padding(horizontal = 12.dp, vertical = if (big) 12.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app != null) Image(app.icon, null, Modifier.size(if (big) 34.dp else 26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(n.title.ifEmpty { app?.label ?: n.pkg }, 13.sp, C.text, FontWeight.Medium, Modifier.weight(1f))
                if (big) Label(app?.label ?: "", 11.sp, C.dim)
            }
            if (n.text.isNotEmpty()) Label(n.text, 12.sp, C.dim, maxLines = if (big) 3 else 1)
        }
    }
}

// ---------------------------------------------------------------- quick settings

@Composable
fun QuickPanelContent(desk: Desk) {
    val ctx = LocalContext.current
    val audio = remember { ctx.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    var wifi by remember { mutableStateOf<Boolean?>(null) }
    var bt by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        ShellClient.request("sh", "settings get global wifi_on; settings get global bluetooth_on") { r ->
            val lines = r?.lines().orEmpty()
            wifi = lines.getOrNull(0)?.trim()?.let { it != "0" && it != "null" }
            bt = lines.getOrNull(1)?.trim()?.let { it != "0" && it != "null" }
        }
    }
    fun media(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    val time = remember(now / 1000) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) }
    val date = remember(now / 60000) { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now)) }
    val battery = remember {
        ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    }
    val shape = RoundedCornerShape(20.dp)
    Appear(TransformOrigin(1f, 1f)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(C.surface).border(1.dp, C.stroke, shape).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Label(time, 34.sp, C.text, FontWeight.Light)
                    Label(date, 13.sp, C.dim)
                }
                if (battery >= 0) BatteryBadge(battery)
            }

            if (desk.helper) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(Modifier.weight(1f), Icon.Wifi, "Wi-Fi", wifi == true) {
                        val on = wifi != true
                        wifi = on
                        ShellClient.request("sh", "svc wifi ${if (on) "enable" else "disable"}")
                    }
                    Tile(Modifier.weight(1f), Icon.Bluetooth, "Bluetooth", bt == true) {
                        val on = bt != true
                        bt = on
                        ShellClient.request("sh", "svc bluetooth ${if (on) "enable" else "disable"}")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(Modifier.weight(1f), Icon.Phone, "Phone screen", !desk.panelOff) { desk.setPanel(desk.panelOff) }
                    Tile(Modifier.weight(1f), Icon.Camera, "Screenshot", false) { desk.screenshot() }
                }
            }

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.field).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(Icon.Volume, C.text, 18.dp)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    Slider(volume) { v ->
                        volume = v
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max + 0.5f).toInt(), 0)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Label("${(volume * 100).toInt()}", 12.sp, C.dim, modifier = Modifier.width(24.dp), align = TextAlign.End)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MediaButton(Modifier.weight(1f), Icon.Prev) { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
                MediaButton(Modifier.weight(1f), Icon.Play) { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
                MediaButton(Modifier.weight(1f), Icon.Next) { media(KeyEvent.KEYCODE_MEDIA_NEXT) }
            }

            val notifs = Notifs.list
            if (notifs.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.field).padding(6.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label("Notifications", 12.sp, C.dim, FontWeight.Medium, Modifier.weight(1f))
                        HoverBox(Modifier.height(26.dp), radius = 8.dp, onClick = { Notifs.clear() }) {
                            Label("Clear all", 12.sp, C.dim, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                    notifs.take(4).forEach { n -> key(n.key) { NotifRow(desk, n) } }
                    if (notifs.size > 4) Label("+${notifs.size - 4} more on the phone", 11.sp, C.dim, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp, top = 2.dp))
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HoverBox(Modifier.height(36.dp), onClick = { desk.hideQuick(); desk.launch(desk.ctx.packageName) }) {
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(Icon.Gear, C.dim, 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Label("HyperDesk settings", 13.sp, C.text)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!desk.helper) Label("Helper offline", 12.sp, C.accent)
            }
        }
    }
}

@Composable
private fun BatteryBadge(level: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(26.dp, 13.dp)) {
            val st = 1.4.dp.toPx()
            drawRoundRect(C.dim, Offset(0f, 0f), Size(size.width - 3.dp.toPx(), size.height), CornerRadius(3.5.dp.toPx()), style = Stroke(st))
            drawRoundRect(C.dim, Offset(size.width - 2.dp.toPx(), size.height * 0.3f), Size(2.dp.toPx(), size.height * 0.4f), CornerRadius(1.dp.toPx()))
            val inner = (size.width - 3.dp.toPx() - st * 3) * level / 100f
            drawRoundRect(if (level <= 15) C.danger else C.text, Offset(st * 1.5f, st * 1.5f), Size(inner, size.height - st * 3), CornerRadius(2.dp.toPx()))
        }
        Spacer(Modifier.width(8.dp))
        Label("$level%", 13.sp, C.dim)
    }
}

/** A quick-settings tile: icon and label, filled with the accent colour when on. */
@Composable
private fun Tile(modifier: Modifier, icon: Icon, text: String, on: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(if (on) C.accent else if (hovered) C.active else C.field, tween(160), label = "tile")
    val fg by animateColorAsState(if (on) C.onAccent else C.text, tween(160), label = "tileFg")
    val press by animateFloatAsState(if (hovered) 1f else 0f, tween(120), label = "tilePress")
    Row(
        modifier.graphicsLayer { scaleX = 1f + 0.015f * press; scaleY = 1f + 0.015f * press }
            .clip(RoundedCornerShape(14.dp)).background(bg).hoverable(src).mouseClick(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, fg, 18.dp)
        Spacer(Modifier.width(10.dp))
        Label(text, 13.sp, fg, FontWeight.Medium)
    }
}

@Composable
private fun Slider(value: Float, onChange: (Float) -> Unit) {
    var width by remember { mutableStateOf(1) }
    fun at(x: Float) = onChange((x / width).coerceIn(0f, 1f))
    val shown by animateFloatAsState(value.coerceIn(0f, 1f), tween(90), label = "slider")
    Box(
        Modifier.fillMaxWidth().height(24.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { at(it.x) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> at(change.position.x) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.active))
        Box(Modifier.fillMaxWidth(shown).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.accent))
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White, 7.dp.toPx(), Offset((size.width * shown).coerceIn(7.dp.toPx(), size.width - 7.dp.toPx()), size.height / 2))
        }
    }
}

@Composable
private fun MediaButton(modifier: Modifier, icon: Icon, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) C.active else C.field, tween(140), label = "media")
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(bg).hoverable(src).mouseClick(onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, C.text, 16.dp) }
}
