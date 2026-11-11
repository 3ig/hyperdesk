package dev.hyperdesk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hyperdesk.shell.ShellClient
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.aspectRatio

object Sys {
    private val FLAGS = listOf("force_desktop_mode_on_external_displays", "enable_freeform_support", "force_resizable_activities")
    private const val SERVICE = "dev.hyperdesk/dev.hyperdesk.DeskService"

    fun canWrite(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun flagsOn(ctx: Context) = FLAGS.all { Settings.Global.getInt(ctx.contentResolver, it, 0) == 1 }

    fun enableFlags(ctx: Context) = FLAGS.forEach { Settings.Global.putInt(ctx.contentResolver, it, 1) }

    fun serviceOn(ctx: Context) =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').any { it.startsWith("dev.hyperdesk/") }

    fun enableService(ctx: Context) {
        val cr = ctx.contentResolver
        val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isEmpty()) SERVICE else "$cur:$SERVICE")
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /**
     * HyperOS kills the app when it is swiped away from recents (or by "clear all"), and Android then lists the
     * taskbar service as crashed without starting it again. If the service is switched on but not running,
     * switch it off and on, which makes the system bind it afresh.
     */
    fun ensureServiceBound(ctx: Context) {
        val app = ctx.applicationContext
        if (!canWrite(app) || !serviceOn(app) || DeskService.instance != null) return
        val cr = app.contentResolver
        val others = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotEmpty() && !it.startsWith("dev.hyperdesk/") }.joinToString(":")
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (others.isEmpty()) SERVICE else "$others:$SERVICE")
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }, 700)
    }

    fun devOptionsOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun wifiConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    fun open(ctx: Context, action: String) {
        try { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    fun monitor(ctx: Context): Display? = ctx.getSystemService(DisplayManager::class.java).displays
        .firstOrNull { it.displayId != 0 && it.flags and Display.FLAG_PRESENTATION != 0 }
}

class MainActivity : ComponentActivity() {
    private var showGuide by mutableStateOf(false)
    private val askNotifications = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        // With or without the permission the service runs; without it the code is typed in the app instead.
        PairingService.start(this)
        Sys.open(this, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    }

    /** Start pairing: a notification takes the code while the system's pairing dialog stays open. */
    private fun startPairing() {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            PairingService.start(this)
            Sys.open(this, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        } else {
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private var onPhotoPicked: (() -> Unit)? = null
    private val pickPhoto = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { input -> Wallpaper.photo.outputStream().use { input.copyTo(it) } }
                Prefs.wallpaper = -1
                DeskService.instance?.desk?.decor?.reloadWallpaper()
                onPhotoPicked?.invoke()
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        window.decorView.postDelayed({ Sys.ensureServiceBound(this) }, 2500)
        // Someone who already has everything working does not need the guide.
        if (!Prefs.onboarded && Sys.canWrite(this) && Sys.flagsOn(this) && Sys.serviceOn(this) && ShellClient.connected) Prefs.onboarded = true
        // Installs from before pairing was recorded: find out quietly whether we are already paired.
        if (!Prefs.paired) Adb.probe(this)
        showGuide = !Prefs.onboarded || intent.getBooleanExtra("guide", false)
        setContent { DeskTheme { if (showGuide) SetupGuide() else SettingsScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("guide", false)) showGuide = true
    }

    override fun onStart() {
        super.onStart()
        Adb.watchPairingPort(this)
    }

    override fun onStop() {
        Adb.stopWatching(this)
        super.onStop()
    }

    @Composable
    private fun SettingsScreen() {
        var flags by remember { mutableStateOf(Sys.flagsOn(this)) }
        var service by remember { mutableStateOf(Sys.serviceOn(this)) }
        var helper by remember { mutableStateOf(ShellClient.connected) }
        var running by remember { mutableStateOf(DeskService.instance != null) }
        var monitor by remember { mutableStateOf<String?>(null) }
        var autoMouse by remember { mutableStateOf(Prefs.autoMouse) }
        var panelOff by remember { mutableStateOf(Prefs.panelOff) }
        var wallpaper by remember { mutableStateOf(Prefs.wallpaper) }
        var scale by remember { mutableStateOf(Prefs.scale) }
        var accent by remember { mutableStateOf(Prefs.accent) }
        var taskbarLeft by remember { mutableStateOf(Prefs.taskbarLeft) }
        var dockDnd by remember { mutableStateOf(Prefs.dockDnd) }
        var photoVersion by remember { mutableStateOf(0) }
        onPhotoPicked = { wallpaper = -1; photoVersion++ }
        val photoPreview = remember(photoVersion) { if (Wallpaper.photo.exists()) Wallpaper.render(480, 200, -1).asImageBitmap() else null }
        var autoTouchpad by remember { mutableStateOf(Prefs.autoTouchpad) }
        var autoKeyboard by remember { mutableStateOf(Prefs.autoKeyboard) }
        var notifications by remember { mutableStateOf(Prefs.notifications) }
        var showPair by remember { mutableStateOf(false) }
        var code by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("") }
        val canWrite = Sys.canWrite(this)

        LaunchedEffect(Unit) {
            while (true) {
                flags = Sys.flagsOn(this@MainActivity)
                service = Sys.serviceOn(this@MainActivity)
                monitor = Sys.monitor(this@MainActivity)?.let { d -> "${d.name} · ${d.mode.physicalWidth}×${d.mode.physicalHeight}" }
                helper = ShellClient.connected
                running = DeskService.instance != null
                delay(1000)
            }
        }
        // Android picks a new pairing port every time its pairing dialog opens; fill it in when we see it.
        LaunchedEffect(Adb.pairingPort) { if (Adb.pairingPort > 0) { port = Adb.pairingPort.toString(); showPair = true } }
        LaunchedEffect(Adb.status) { if (Adb.status.startsWith("Not paired")) showPair = true }

        Box(Modifier.fillMaxSize().background(Color(0xFF0D0F12)).systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("HyperDesk", 28.sp, C.text, FontWeight.SemiBold)
                        Label("A desktop on your external monitor", 14.sp, C.dim)
                    }
                    Pill("Setup guide") { showGuide = true }
                    Spacer(Modifier.width(10.dp))
                    val ready = monitor != null && flags && service && running && helper
                    Box(
                        Modifier.clip(CircleShape).background(if (ready) Color(0x334ADE80) else C.field).padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Label(if (ready) "Running" else if (monitor == null) "No monitor" else "Setup needed", 12.sp, if (ready) C.ok else C.dim, FontWeight.Medium) }
                }

                Card("Status") {
                    StatusRow("Monitor", monitor ?: "Not connected", monitor != null)
                    StatusRow("Desktop mode", if (flags) "On" else "Off", flags) {
                        if (!flags && canWrite) Pill("Turn on", accent = true) { Sys.enableFlags(this@MainActivity); flags = Sys.flagsOn(this@MainActivity) }
                    }
                    // "On" in Android's settings is not the same as actually running: check the live service too.
                    val live = service && running
                    StatusRow("Taskbar service", if (live) "Running" else if (service) "Switched on but not running" else "Off", live) {
                        if (!live) Pill(if (service && canWrite) "Restart" else if (canWrite) "Turn on" else "Open settings", accent = true) {
                            when {
                                service && canWrite -> Sys.ensureServiceBound(this@MainActivity)
                                canWrite -> Sys.enableService(this@MainActivity)
                                else -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        }
                    }
                    StatusRow("Window helper", if (helper) "Connected" else if (Adb.working) "Starting…" else "Not running", helper) {
                        if (!helper && !Adb.working) Pill("Start", accent = true) { Adb.start(this@MainActivity) }
                    }
                    if (!helper) {
                        Label(
                            "The helper moves, resizes and closes windows and keeps the mouse on the monitor. HyperDesk starts it through " +
                                "the phone's own Wireless debugging, so no computer is needed. It needs Wi-Fi, and pairing once.",
                            13.sp, C.dim, maxLines = 5,
                        )
                    }
                    if (Adb.status.isNotEmpty() && !helper) Label(Adb.status, 13.sp, C.accent, maxLines = 4)
                    if (!helper && (showPair || Adb.pairingPort > 0)) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B0C0E)).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Label("Pair once", 14.sp, C.text, FontWeight.Medium)
                            Label(
                                "1. Open Developer options → Wireless debugging → Pair device with pairing code, and leave that dialog open " +
                                    "(use split screen, a floating window, or the other display).\n2. Type the six-digit code here. The port fills in by itself.",
                                13.sp, C.dim, maxLines = 6,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Field(code, "Pairing code", Modifier.weight(1f)) { code = it.filter(Char::isDigit).take(6) }
                                Field(port, "Port", Modifier.width(110.dp)) { port = it.filter(Char::isDigit).take(5) }
                                Pill("Pair", accent = code.length == 6 && port.isNotEmpty()) {
                                    val p = port.toIntOrNull()
                                    if (code.length == 6 && p != null) Adb.pair(this@MainActivity, p, code)
                                }
                            }
                            Pill("Open Developer options") {
                                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                            }
                        }
                    } else if (!helper) {
                        Pill("Pair with Wireless debugging") { showPair = true }
                    }
                    val incomplete = !canWrite || !flags || !(service && running) || !helper
                    if (incomplete && Prefs.paired && !Adb.working) {
                        Pill("Finish setup", accent = true) { Adb.provision(this@MainActivity) }
                    } else if (!canWrite && !Prefs.paired) {
                        Label("HyperDesk has not been set up yet. The setup guide does it in three steps, without a computer.", 13.sp, C.dim, maxLines = 3)
                        Pill("Open setup guide", accent = true) { showGuide = true }
                    }
                }

                Card("Wallpaper") {
                    val previews = remember { Wallpaper.names.indices.map { Wallpaper.render(480, 200, it).asImageBitmap() } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Wallpaper.names.forEachIndexed { i, name ->
                            val sel = wallpaper == i
                            val shape = RoundedCornerShape(12.dp)
                            Column(
                                Modifier.weight(1f).mouseClick {
                                    wallpaper = i
                                    Prefs.wallpaper = i
                                    DeskService.instance?.desk?.decor?.reloadWallpaper()
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Image(
                                    previews[i], name,
                                    Modifier.fillMaxWidth().aspectRatio(2.4f).clip(shape).border(2.dp, if (sel) C.accent else C.stroke, shape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                                Label(name, 12.sp, if (sel) C.text else C.dim, if (sel) FontWeight.Medium else FontWeight.Normal)
                            }
                        }
                    }
                }

                Card("Display") {
                    Label("Interface size on the monitor", 15.sp, C.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (v in listOf(85, 100, 115, 130, 150)) {
                            Pill("$v%", accent = scale == v) {
                                scale = v
                                Prefs.scale = v
                                DeskService.instance?.desk?.applyScale()
                            }
                        }
                    }
                    Label("Larger sizes make text and controls bigger in every app on the monitor. Needs the window helper.", 13.sp, C.dim, maxLines = 3)
                }

                Card("Phone as touchpad") {
                    Label("Use the phone's screen as a touchpad and keyboard for the monitor when no mouse is attached.", 13.sp, C.dim, maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Open touchpad", accent = true) { TouchpadActivity.open(this@MainActivity) }
                    }
                    Toggle("Open it automatically", "When a monitor is connected and no mouse is found.", autoTouchpad) {
                        autoTouchpad = it; Prefs.autoTouchpad = it
                    }
                    Toggle("Keyboard on demand", "Off by default: picking a text field on the monitor brings up the phone's keyboard when no keyboard is attached.", autoKeyboard) {
                        autoKeyboard = it; Prefs.autoKeyboard = it
                    }
                }

                Card("Look") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val shape = RoundedCornerShape(12.dp)
                        Box(Modifier.width(150.dp).aspectRatio(2.4f).clip(shape).background(C.field).border(2.dp, if (wallpaper == -1) C.accent else C.stroke, shape)
                            .mouseClick { if (photoPreview != null && wallpaper != -1) { wallpaper = -1; Prefs.wallpaper = -1; DeskService.instance?.desk?.decor?.reloadWallpaper() } else pickPhoto.launch("image/*") },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (photoPreview != null) Image(photoPreview, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            else Label("Your photo", 12.sp, C.dim)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Label("Use your own picture as the wallpaper", 15.sp, C.text, maxLines = 2)
                            Pill(if (photoPreview != null) "Choose another photo" else "Choose a photo") { pickPhoto.launch("image/*") }
                        }
                    }
                    Label("Accent colour", 15.sp, C.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        C.accents.forEach { a ->
                            val sel = accent == a
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(Color(a)).border(3.dp, if (sel) Color.White else Color.Transparent, CircleShape)
                                    .mouseClick {
                                        accent = a
                                        Prefs.accent = a
                                        C.load()
                                        DeskService.instance?.desk?.decor?.reloadWallpaper()
                                    },
                            )
                        }
                    }
                    Toggle("Taskbar on the left", "Start button and apps at the left edge instead of the middle.", taskbarLeft) {
                        taskbarLeft = it; Prefs.taskbarLeft = it
                        DeskService.instance?.desk?.taskbarLeft = it
                    }
                }

                Card("Behaviour") {
                    Toggle("Do not disturb while docked", "Silences the phone while a monitor is connected and turns it back when you unplug.", dockDnd) {
                        dockDnd = it; Prefs.dockDnd = it
                        DeskService.instance?.desk?.applyDockDnd()
                    }
                    Toggle("Show notifications on the monitor", "A card in the top right corner when one arrives, and a list in quick settings.", notifications) {
                        notifications = it; Prefs.notifications = it
                    }
                    Toggle("Keep the mouse on the monitor", "Bind external mice and touchpads to the monitor while it is connected.", autoMouse) {
                        autoMouse = it; Prefs.autoMouse = it
                        ShellClient.request("mouse", if (it) Sys.monitor(this@MainActivity)?.displayId ?: -1 else -1)
                    }
                    Toggle("Turn the phone screen off when docked", "The monitor stays on. Press the power button to bring the phone screen back.", panelOff) {
                        panelOff = it; Prefs.panelOff = it
                    }
                }

                Card("Keyboard shortcuts") {
                    Shortcut("Meta", "Open or close the start menu")
                    Shortcut("Alt + Tab", "Switch between windows")
                    Shortcut("Meta + 1 … 9", "Open or switch to that taskbar app")
                    Shortcut("Meta + ← / →", "Snap the window to the left or right half")
                    Shortcut("Meta + ↑ / ↓", "Maximize or restore / minimize")
                    Shortcut("Meta + F  or  F11", "Full screen for the current window")
                    Shortcut("Meta + D", "Show the desktop")
                    Shortcut("Meta + A", "Quick settings")
                    Shortcut("Meta + Shift + S", "Screenshot of the monitor")
                    Shortcut("Alt + F4", "Close the window")
                }

                Card("Mouse") {
                    Shortcut("Drag title bar", "Move; drag to an edge or corner to snap, to the top to maximize")
                    Shortcut("Double-click title", "Maximize or restore")
                    Shortcut("Drag edges", "Resize")
                    Shortcut("Right-click title", "Window menu with every snap layout")
                    Shortcut("Right-click icon", "Window and pin options")
                    Shortcut("Middle-click icon", "Close the app's windows")
                }

                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("HyperDesk ${BuildConfig.VERSION_NAME}", 12.sp, C.dim, modifier = Modifier.weight(1f))
                    HoverBox(Modifier.height(30.dp), onClick = {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/${BuildConfig.REPO}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {}
                    }) { Label("github.com/${BuildConfig.REPO}", 12.sp, C.dim, modifier = Modifier.padding(horizontal = 8.dp)) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- first-run setup guide

    @Composable
    private fun SetupGuide() {
        var step by remember { mutableStateOf(0) }
        var canWrite by remember { mutableStateOf(Sys.canWrite(this)) }
        var flags by remember { mutableStateOf(Sys.flagsOn(this)) }
        var service by remember { mutableStateOf(Sys.serviceOn(this) && DeskService.instance != null) }
        var helper by remember { mutableStateOf(ShellClient.connected) }
        var monitor by remember { mutableStateOf(Sys.monitor(this) != null) }
        var devOptions by remember { mutableStateOf(Sys.devOptionsOn(this)) }
        var wifi by remember { mutableStateOf(Sys.wifiConnected(this)) }
        var wireless by remember { mutableStateOf(Adb.wirelessOn(this)) }
        var paired by remember { mutableStateOf(Prefs.paired) }
        var code by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("") }
        var manual by remember { mutableStateOf(false) }
        var computer by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                canWrite = Sys.canWrite(this@MainActivity)
                flags = Sys.flagsOn(this@MainActivity)
                service = Sys.serviceOn(this@MainActivity) && DeskService.instance != null
                helper = ShellClient.connected
                monitor = Sys.monitor(this@MainActivity) != null
                devOptions = Sys.devOptionsOn(this@MainActivity)
                wifi = Sys.wifiConnected(this@MainActivity)
                wireless = Adb.wirelessOn(this@MainActivity)
                paired = Prefs.paired
                delay(700)
            }
        }
        LaunchedEffect(Adb.pairingPort) { if (Adb.pairingPort > 0) port = Adb.pairingPort.toString() }

        val all = canWrite && flags && service && helper
        val titles = listOf("Welcome", "Wireless debugging", "Pair", "Finish")
        val done = listOf(true, (devOptions && wireless) || paired || all, paired || all, all)
        fun finish() { Prefs.onboarded = true; showGuide = false }
        // Pairing succeeded (here or from the notification): move on to the last step by itself.
        LaunchedEffect(paired) { if (paired && step == 2) step = 3 }

        Box(Modifier.fillMaxSize().background(Color(0xFF0D0F12)).systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(22.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    titles.indices.forEach { i ->
                        Box(
                            Modifier.weight(1f).height(4.dp).clip(CircleShape)
                                .background(if (i == step) C.accent else if (i < step && done[i]) C.ok else if (i < step) C.dim else C.field),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Step ${step + 1} of ${titles.size}", 12.sp, C.dim, modifier = Modifier.weight(1f))
                    HoverBox(Modifier.height(30.dp), onClick = { finish() }) { Label("Skip setup", 12.sp, C.dim, modifier = Modifier.padding(horizontal = 8.dp)) }
                }
                Spacer(Modifier.height(18.dp))

                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (step) {
                        0 -> {
                            GuideTitle("Welcome to HyperDesk", "Turns an external monitor into a desktop: windows you can move and resize, a taskbar, a start menu and keyboard shortcuts.")
                            GuideCard {
                                Bullet("A monitor connected to the phone's USB-C port, directly or through a dock.")
                                Bullet("A mouse and keyboard are optional. Without them, the phone becomes the touchpad and keyboard.")
                                Bullet("Wi-Fi, for setup and after each restart of the phone. No computer is needed.")
                            }
                            Label("Setup is three short steps, all on this phone: switch on Wireless debugging, type one code, and HyperDesk does the rest.", 14.sp, C.dim, maxLines = 5)
                        }
                        1 -> {
                            GuideTitle("Switch on Wireless debugging", "HyperDesk sets itself up through the phone's own debugging connection. Nothing leaves the phone.")
                            GuideCard {
                                GuideStatus(devOptions, "Developer options are unlocked", "Developer options are locked")
                                if (!devOptions) {
                                    Label("Open About phone and tap “OS version” seven times.", 13.sp, C.dim, maxLines = 3)
                                    Pill("Open About phone", accent = true) { Sys.open(this@MainActivity, Settings.ACTION_DEVICE_INFO_SETTINGS) }
                                }
                                GuideStatus(wifi, "Wi-Fi is connected", "Wi-Fi is not connected")
                                if (!wifi) Pill("Open Wi-Fi settings") { Sys.open(this@MainActivity, Settings.ACTION_WIFI_SETTINGS) }
                                GuideStatus(wireless, "Wireless debugging is on", "Wireless debugging is off")
                                if (!wireless && devOptions) {
                                    Label("In Developer options, turn on “Wireless debugging” and allow it for this network.", 13.sp, C.dim, maxLines = 3)
                                    Pill("Open Developer options", accent = true) { Sys.open(this@MainActivity, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
                                }
                            }
                            if (paired) Label("Already paired, so HyperDesk can switch Wireless debugging on by itself from now on.", 13.sp, C.dim, maxLines = 3)
                        }
                        2 -> {
                            GuideTitle("Pair HyperDesk with this phone", "One six-digit code, typed once. You will not need to do this again.")
                            GuideStatus(paired, "Paired", if (Adb.working) "Pairing…" else "Not paired yet")
                            if (!paired) {
                                GuideCard {
                                    Bullet("Tap the button below. Developer options opens and a HyperDesk notification appears.")
                                    Bullet("Open Wireless debugging → “Pair device with pairing code”.")
                                    Bullet("Pull down the notifications and type the code into the HyperDesk one. Leave the pairing dialog open while you do.")
                                    Pill("Start pairing", accent = true) { startPairing() }
                                }
                                if (Adb.status.isNotEmpty()) Label(Adb.status, 13.sp, C.accent, maxLines = 4)
                                HoverBox(Modifier.height(34.dp), onClick = { manual = !manual }) {
                                    Label(if (manual) "Hide" else "Type the code here instead", 13.sp, C.dim, modifier = Modifier.padding(horizontal = 8.dp))
                                }
                                if (manual) GuideCard {
                                    Label("Keep the pairing dialog visible in split screen or a floating window. The port fills in by itself.", 13.sp, C.dim, maxLines = 3)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Field(code, "Pairing code", Modifier.weight(1f)) { code = it.filter(Char::isDigit).take(6) }
                                        Field(port, "Port", Modifier.width(110.dp)) { port = it.filter(Char::isDigit).take(5) }
                                        Pill("Pair", accent = code.length == 6 && port.isNotEmpty()) {
                                            val p = port.toIntOrNull()
                                            if (code.length == 6 && p != null) Adb.pair(this@MainActivity, p, code)
                                        }
                                    }
                                }
                                HoverBox(Modifier.height(34.dp), onClick = { computer = !computer }) {
                                    Label(if (computer) "Hide" else "Use a computer instead", 13.sp, C.dim, modifier = Modifier.padding(horizontal = 8.dp))
                                }
                                if (computer) GuideCard {
                                    Label("With the phone connected to a computer that has adb, run this once. HyperDesk can then switch on desktop mode and its taskbar itself; the helper is started with scripts/start-helper.sh from the HyperDesk repository.", 13.sp, C.dim, maxLines = 3)
                                    Code("adb shell pm grant dev.hyperdesk android.permission.WRITE_SECURE_SETTINGS")
                                }
                            }
                        }
                        else -> {
                            // Run the automatic part once on arriving here, if there is anything left to do.
                            LaunchedEffect(Unit) { if (!all && paired && !Adb.working) Adb.provision(this@MainActivity) }
                            GuideTitle(
                                if (all) "You're set" else if (Adb.working) "Finishing up" else "Almost there",
                                if (all) "Connect a monitor and the desktop appears on it."
                                else if (Adb.working) "HyperDesk is setting itself up. This takes a few seconds."
                                else "Something is not finished yet. Try again, or go back a step.",
                            )
                            GuideCard {
                                val live = Adb.steps
                                if (live.isNotEmpty() && !all) {
                                    live.forEach { st -> GuideStatus(st.state == 2, st.label, st.label + if (st.state == 1) " …" else if (st.state == 3) " — failed" else "") }
                                } else {
                                    GuideStatus(canWrite, "Permission to change settings", "Permission to change settings — missing")
                                    GuideStatus(flags, "Desktop mode", "Desktop mode — off")
                                    GuideStatus(service, "Taskbar service", "Taskbar service — not running")
                                    GuideStatus(helper, "Window helper", "Window helper — not running")
                                }
                                GuideStatus(monitor, "Monitor connected", "No monitor connected yet")
                            }
                            if (!all && !Adb.working) {
                                if (Adb.status.isNotEmpty()) Label(Adb.status, 13.sp, C.accent, maxLines = 5)
                                if (Adb.needsSecurityToggle) GuideCard {
                                    Label("One more switch on Xiaomi phones", 14.sp, C.text, FontWeight.Medium)
                                    Bullet("In Developer options, turn on “USB debugging (Security settings)”. The phone asks you to sign in to a Mi account and confirm three warnings.")
                                    Pill("Open Developer options") { Sys.open(this@MainActivity, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
                                }
                                Pill(if (paired) "Try again" else "Go back and pair", accent = true) {
                                    if (paired) Adb.provision(this@MainActivity) else step = 2
                                }
                            }
                            if (all) {
                                GuideCard {
                                    Label("Keep it running", 14.sp, C.text, FontWeight.Medium)
                                    Bullet("HyperOS closes apps it thinks are idle. Lock HyperDesk in the recent-apps screen, and set its battery use to “No restrictions”.")
                                    Pill("Open HyperDesk's app settings") {
                                        try {
                                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        } catch (_: Exception) {}
                                    }
                                }
                                GuideCard {
                                    Label("Good to know", 14.sp, C.text, FontWeight.Medium)
                                    Bullet("Meta opens the start menu, Alt+Tab switches windows, Meta+F is full screen.")
                                    Bullet("Drag a title bar to an edge to snap; right-click it for every layout.")
                                    Bullet("No mouse? The phone turns into a touchpad when the monitor connects.")
                                    Bullet("After restarting the phone, HyperDesk needs Wi-Fi once to start its helper again.")
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) Pill("Back") { step-- }
                    Spacer(Modifier.weight(1f))
                    val last = step == titles.size - 1
                    Pill(if (last) "Finish" else if (done[step]) "Next" else "Skip this step", accent = last || done[step]) {
                        if (last) finish() else step++
                    }
                }
            }
        }
    }

    @Composable
    private fun GuideTitle(title: String, body: String) {
        Label(title, 26.sp, C.text, FontWeight.SemiBold, maxLines = 2)
        Label(body, 15.sp, C.dim, maxLines = 8)
    }

    @Composable
    private fun GuideCard(content: @Composable () -> Unit) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Color(0xFF16191E)).border(1.dp, C.stroke, shape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }

    @Composable
    private fun GuideStatus(ok: Boolean, yes: String, no: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(if (ok) C.ok else C.field), contentAlignment = Alignment.Center) {
                if (ok) androidx.compose.foundation.Canvas(Modifier.size(11.dp)) {
                    val w = size.width
                    val h = size.height
                    val st = 2.dp.toPx()
                    drawLine(Color.Black, androidx.compose.ui.geometry.Offset(0f, h * 0.55f), androidx.compose.ui.geometry.Offset(w * 0.38f, h * 0.9f), st, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(Color.Black, androidx.compose.ui.geometry.Offset(w * 0.38f, h * 0.9f), androidx.compose.ui.geometry.Offset(w, h * 0.1f), st, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            Spacer(Modifier.width(12.dp))
            Label(if (ok) yes else no, 15.sp, if (ok) C.text else C.dim, if (ok) FontWeight.Medium else FontWeight.Normal, maxLines = 2)
        }
    }

    @Composable
    private fun Bullet(text: String) {
        Row {
            Box(Modifier.padding(top = 8.dp).size(5.dp).clip(CircleShape).background(C.dim))
            Spacer(Modifier.width(12.dp))
            Label(text, 14.sp, C.text, maxLines = 6)
        }
    }

    @Composable
    private fun Card(title: String, content: @Composable () -> Unit) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Color(0xFF16191E)).border(1.dp, C.stroke, shape).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Label(title.uppercase(), 11.sp, C.dim, FontWeight.SemiBold)
            content()
        }
    }

    @Composable
    private fun Field(value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
        Box(modifier.clip(RoundedCornerShape(10.dp)).background(C.field).padding(horizontal = 12.dp, vertical = 11.dp)) {
            if (value.isEmpty()) Label(hint, 14.sp, C.dim)
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = TextStyle(color = C.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(C.accent), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }

    @Composable
    private fun StatusRow(title: String, value: String, ok: Boolean, action: (@Composable () -> Unit)? = null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) C.ok else C.dim))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Label(title, 15.sp, C.text)
                Label(value, 13.sp, C.dim)
            }
            action?.invoke()
        }
    }

    @Composable
    private fun Toggle(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().mouseClick { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(title, 15.sp, C.text)
                Label(detail, 13.sp, C.dim, maxLines = 3)
            }
            Spacer(Modifier.width(12.dp))
            val knob by animateDpAsState(if (value) 20.dp else 2.dp, label = "knob")
            Box(Modifier.size(44.dp, 26.dp).clip(CircleShape).background(if (value) C.accent else C.active)) {
                Box(Modifier.offset(x = knob, y = 2.dp).size(22.dp).clip(CircleShape).background(Color.White))
            }
        }
    }

    @Composable
    private fun Code(text: String) {
        SelectionContainer {
            androidx.compose.foundation.text.BasicText(
                text,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0C0E)).padding(12.dp),
                TextStyle(color = C.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            )
        }
    }

    @Composable
    private fun Shortcut(keys: String, what: String) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(150.dp)) {
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(C.field).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Label(keys, 12.sp, C.text, FontWeight.Medium)
                }
            }
            Label(what, 14.sp, C.dim, maxLines = 2)
        }
    }
}
