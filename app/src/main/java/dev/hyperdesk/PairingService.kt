package dev.hyperdesk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import dev.hyperdesk.shell.ShellClient

/**
 * Lets the pairing code be typed straight into a notification, so the system's pairing dialog (which closes as
 * soon as you leave it) can stay open. Runs as a foreground service so the app is not frozen while the user is
 * in Settings, and keeps the notification's text in step with what is happening.
 */
class PairingService : Service() {
    companion object {
        private const val CHANNEL = "setup"
        private const val ID = 41
        const val KEY_CODE = "code"
        const val ACTION_REPLY = "dev.hyperdesk.PAIR_REPLY"

        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PairingService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var started = 0L
    private var lastText = ""
    private var finishedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        started = SystemClock.uptimeMillis()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Setup", NotificationManager.IMPORTANCE_HIGH))
        Adb.watchPairingPort(this)
        startForeground(ID, build(text()), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        Adb.stopWatching(this)
        super.onDestroy()
    }

    private fun done() = ShellClient.connected && Sys.canWrite(this) && Sys.flagsOn(this)

    private fun text(): String = when {
        done() -> "Setup finished. HyperDesk is ready."
        Adb.working && Adb.status.isNotEmpty() -> Adb.status
        Adb.working -> "Finishing setup…"
        Adb.status.startsWith("Pairing failed") || Adb.needsSecurityToggle -> Adb.status
        Adb.steps.any { it.state == 3 } -> Adb.status
        Adb.pairingPort > 0 -> "Pairing dialog found. Enter the six-digit code below."
        else -> "Open Wireless debugging and tap “Pair device with pairing code”. Keep that dialog open."
    }

    private val tick = object : Runnable {
        override fun run() {
            val t = text()
            if (t != lastText) {
                lastText = t
                getSystemService(NotificationManager::class.java).notify(ID, build(t))
            }
            val now = SystemClock.uptimeMillis()
            if (done() && finishedAt == 0L) finishedAt = now
            // Leave the result up briefly, and never linger for more than ten minutes.
            if ((finishedAt != 0L && now - finishedAt > 6000) || now - started > 10 * 60_000) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            handler.postDelayed(this, 700)
        }
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra("guide", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("HyperDesk setup")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (!done() && !Adb.working) {
            val reply = PendingIntent.getBroadcast(
                this, 1, Intent(this, PairingReceiver::class.java).setAction(ACTION_REPLY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel("Six-digit pairing code").build()
            b.addAction(
                Notification.Action.Builder(null, "Enter pairing code", reply).addRemoteInput(input).build(),
            )
        }
        return b.build()
    }
}

/** Receives the code typed into the setup notification. */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != PairingService.ACTION_REPLY) return
        Prefs.init(ctx)
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingService.KEY_CODE)?.toString().orEmpty().filter(Char::isDigit)
        val port = Adb.pairingPort
        when {
            code.length != 6 -> Adb.note("The pairing code has six digits. Try again.")
            port <= 0 -> Adb.note("Open “Pair device with pairing code” first, then enter the code while that dialog is showing.")
            else -> Adb.pair(ctx, port, code)
        }
    }
}
