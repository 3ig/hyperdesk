package dev.hyperdesk

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.hyperdesk.shell.ShellClient
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.util.concurrent.Executors

/**
 * Starts the shell helper from the phone itself, with no computer: the app talks to the phone's own
 * Wireless debugging as an ADB client (the technique Shizuku uses) and runs the helper's start command.
 *
 * One-time: pair with a pairing code from Developer options → Wireless debugging. After that the app can
 * switch Wireless debugging on by itself (needs Wi-Fi and the WRITE_SECURE_SETTINGS grant) and start the
 * helper whenever it is missing, including after a reboot.
 */
object Adb {
    var status by mutableStateOf("")
        private set
    var working by mutableStateOf(false)
        private set
    var pairingPort by mutableStateOf(0)
        private set

    private val io = Executors.newSingleThreadExecutor()
    private var lastAttempt = 0L

    private class Manager(ctx: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            val keyFile = File(ctx.filesDir, "adb_key.pk8")
            val certFile = File(ctx.filesDir, "adb_cert.der")
            if (keyFile.exists() && certFile.exists()) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
            } else {
                val gen = KeyPairGenerator.getInstance("RSA")
                gen.initialize(2048, SecureRandom())
                val pair = gen.generateKeyPair()
                val name = X500Name("CN=HyperDesk")
                val from = Date()
                val to = Date(from.time + 20L * 365 * 24 * 3600 * 1000)
                val ext = CertificateExtensions()
                ext.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(pair.public).identifier))
                val info = X509CertInfo()
                info.set("version", CertificateVersion(2))
                info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
                info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get("SHA512withRSA")))
                info.set("subject", CertificateSubjectName(name))
                info.set("key", CertificateX509Key(pair.public))
                info.set("validity", CertificateValidity(from, to))
                info.set("issuer", CertificateIssuerName(name))
                info.set("extensions", ext)
                val impl = X509CertImpl(info)
                impl.sign(pair.private, "SHA512withRSA")
                key = pair.private
                cert = impl
                keyFile.writeBytes(pair.private.encoded)
                certFile.writeBytes(impl.encoded)
            }
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "HyperDesk"
    }

    private var manager: Manager? = null
    private fun manager(ctx: Context) = manager ?: Manager(ctx.applicationContext).also { manager = it }

    /**
     * The command that launches the helper. It must outlive the debugging connection that runs it: setsid puts it
     * in a session of its own, so it is not taken down with the shell when that connection closes.
     */
    private fun startCommand(ctx: Context): String {
        val apk = ctx.applicationContext.packageCodePath
        return "killall hyperdesk_shell 2>/dev/null; sleep 0.3; " +
            "CLASSPATH=$apk setsid nohup app_process /system/bin --nice-name=hyperdesk_shell " +
            "dev.hyperdesk.shell.ShellServer ${Process.myUid()} </dev/null >/data/local/tmp/hyperdesk.log 2>&1 & sleep 0.5; echo started"
    }

    fun wirelessOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1

    /** One line of the "finishing up" checklist. state: 0 waiting, 1 working, 2 done, 3 failed. */
    class Step(val label: String, val state: Int)

    var steps by mutableStateOf(listOf<Step>())
        private set
    /** Set when granting the permission was refused, which on Xiaomi means "USB debugging (Security settings)" is off. */
    var needsSecurityToggle by mutableStateOf(false)
        private set

    /**
     * Connect to the phone's own Wireless debugging. Switches it on first if we are allowed to; [turnedOn] tells
     * the caller to switch it back off afterwards.
     */
    private class Link(val m: Manager, val turnedOn: Boolean)

    private fun connect(app: Context): Link {
        var turnedOn = false
        if (!wirelessOn(app)) {
            if (!Sys.canWrite(app)) throw IllegalStateException("Turn on Wireless debugging in Developer options first.")
            Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 1)
            turnedOn = true
            Thread.sleep(1500)
            if (!wirelessOn(app)) throw IllegalStateException("Wireless debugging needs Wi-Fi to be connected.")
        }
        val m = manager(app)
        val ok = try {
            m.isConnected || m.connectTls(app, 6000)
        } catch (e: io.github.muntashirakon.adb.AdbPairingRequiredException) {
            false
        } catch (t: Throwable) {
            if (turnedOn) try { Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 0) } catch (_: Throwable) {}
            throw t
        }
        if (!ok) {
            if (turnedOn) try { Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 0) } catch (_: Throwable) {}
            Prefs.paired = false
            throw IllegalStateException("Not paired yet. Pair with Wireless debugging first.")
        }
        Prefs.paired = true
        return Link(m, turnedOn)
    }

    private fun release(app: Context, link: Link?) {
        try { manager?.disconnect() } catch (_: Throwable) {}
        if (link?.turnedOn == true) try { Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 0) } catch (_: Throwable) {}
    }

    /** Run one command as the shell user and return what it printed. */
    private fun shell(m: Manager, cmd: String): String {
        val sb = StringBuilder()
        m.openStream("shell:$cmd").use { stream ->
            val input = stream.openInputStream()
            val buf = ByteArray(4096)
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                val n = try { input.read(buf) } catch (e: java.io.IOException) { -1 }
                if (n < 0) break
                if (n > 0) sb.append(String(buf, 0, n))
            }
        }
        return sb.toString().trim()
    }

    private fun waitForHelper(): Boolean {
        var waited = 0
        while (!ShellClient.connected && waited < 7000) { Thread.sleep(250); waited += 250 }
        return ShellClient.connected
    }

    /**
     * Try to start the helper. [quiet] is for automatic attempts (rate-limited, no status noise).
     */
    fun start(ctx: Context, quiet: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (working || ShellClient.connected) return
        if (quiet && now - lastAttempt < 20_000) return
        lastAttempt = now
        working = true
        if (!quiet) status = "Connecting to Wireless debugging…"
        val app = ctx.applicationContext
        io.execute {
            var link: Link? = null
            try {
                link = connect(app)
                shell(link.m, startCommand(app))
                val up = waitForHelper()
                if (!quiet || up) status = if (up) "Helper started." else "Sent the start command, but the helper has not connected yet."
            } catch (t: Throwable) {
                if (!quiet) status = (t.message ?: t.toString()).take(200)
            } finally {
                release(app, link)
                working = false
            }
        }
    }

    /**
     * Everything setup needs after pairing, with no computer: grant ourselves the settings permission through the
     * phone's own debugging connection, switch on desktop mode and the taskbar service, allow notifications,
     * ask the system not to put us to sleep, and start the helper.
     */
    fun provision(ctx: Context) {
        if (working) return
        working = true
        needsSecurityToggle = false
        status = ""
        val app = ctx.applicationContext
        val labels = listOf("Permission to change settings", "Desktop mode", "Taskbar service", "Notifications on the monitor", "Keep running in the background", "Window helper")
        val state = IntArray(labels.size)
        fun publish() { steps = labels.mapIndexed { i, l -> Step(l, state[i]) } }
        publish()
        io.execute {
            var link: Link? = null
            var at = 0
            fun step(i: Int, block: () -> Boolean) {
                at = i
                state[i] = 1; publish()
                val ok = block()
                state[i] = if (ok) 2 else 3; publish()
                if (!ok) throw IllegalStateException(status.ifEmpty { labels[i] + " could not be set up." })
            }
            try {
                link = connect(app)
                val m = link.m
                val pkg = app.packageName
                step(0) {
                    if (Sys.canWrite(app)) return@step true
                    val out = shell(m, "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>&1")
                    Thread.sleep(400)
                    if (Sys.canWrite(app)) return@step true
                    needsSecurityToggle = out.contains("Security", ignoreCase = true) || out.contains("GRANT_RUNTIME_PERMISSIONS")
                    status = if (needsSecurityToggle) "The phone refused the permission. Turn on “USB debugging (Security settings)” in Developer options, then try again."
                    else "The permission was not granted: " + out.take(140)
                    false
                }
                step(1) { Sys.enableFlags(app); Sys.flagsOn(app) }
                step(2) {
                    if (Sys.serviceOn(app)) Sys.ensureServiceBound(app) else Sys.enableService(app)
                    var waited = 0
                    while (DeskService.instance == null && waited < 5000) { Thread.sleep(250); waited += 250 }
                    DeskService.instance != null
                }
                step(3) { shell(m, "cmd notification allow_listener $pkg/${NotifService::class.java.name} 2>&1"); true }
                step(4) { shell(m, "dumpsys deviceidle whitelist +$pkg 2>&1"); true }
                step(5) {
                    if (ShellClient.connected) return@step true
                    shell(m, startCommand(app))
                    waitForHelper().also { if (!it) status = "The helper was started but has not connected yet. Try again in a moment." }
                }
                status = "Setup finished."
            } catch (t: Throwable) {
                if (state.none { it == 3 } && at < state.size) { state[at] = 3; publish() }
                if (status.isEmpty()) status = (t.message ?: t.toString()).take(200)
            } finally {
                release(app, link)
                working = false
            }
        }
    }

    /** Quietly check whether we are already paired (records the answer in [Prefs.paired]). Changes nothing else. */
    fun probe(ctx: Context) {
        val app = ctx.applicationContext
        if (working || !java.io.File(app.filesDir, "adb_key.pk8").exists() || !wirelessOn(app)) return
        io.execute {
            var link: Link? = null
            try { link = connect(app) } catch (_: Throwable) {} finally { release(app, link) }
        }
    }

    /** Show a message from outside (the setup notification uses this for input mistakes). */
    fun note(text: String) { status = text }

    /** Pair with the code shown under Wireless debugging → "Pair device with pairing code", then finish setup. */
    fun pair(ctx: Context, port: Int, code: String) {
        if (working) return
        working = true
        status = "Pairing…"
        val app = ctx.applicationContext
        io.execute {
            try {
                manager(app).pair("127.0.0.1", port, code.trim())
                Prefs.paired = true
                status = "Paired. Finishing setup…"
                working = false
                provision(app)
            } catch (t: Throwable) {
                status = "Pairing failed: " + (t.message ?: t.toString()).take(160)
                working = false
            }
        }
    }

    // ---- find the pairing port, which Android picks at random each time the pairing dialog opens ----

    private var discovery: NsdManager.DiscoveryListener? = null

    fun watchPairingPort(ctx: Context) {
        if (discovery != null) return
        val nsd = ctx.applicationContext.getSystemService(NsdManager::class.java) ?: return
        val mine = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.map { it.hostAddress }.toSet()
        } catch (_: Throwable) { emptySet() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) { pairingPort = 0 }
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        if (i.host?.hostAddress in mine) pairingPort = i.port
                    }
                })
            }
        }
        try {
            nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, l)
            discovery = l
        } catch (_: Throwable) {
        }
    }

    fun stopWatching(ctx: Context) {
        val l = discovery ?: return
        discovery = null
        try { ctx.applicationContext.getSystemService(NsdManager::class.java)?.stopServiceDiscovery(l) } catch (_: Throwable) {}
    }
}
