package dev.hyperdesk

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class Notif(val key: String, val pkg: String, val title: String, val text: String, val time: Long, val intent: PendingIntent?)

/** The phone's notifications, mirrored for the monitor: a toast when one arrives and a list in quick settings. */
object Notifs {
    var list by mutableStateOf(listOf<Notif>())
        private set

    fun allowed(ctx: Context) =
        (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").contains(ctx.packageName + "/")

    internal fun put(n: Notif) { list = (listOf(n) + list.filter { it.key != n.key }).take(30) }
    internal fun drop(key: String) { list = list.filter { it.key != key } }
    internal fun replace(all: List<Notif>) { list = all.sortedByDescending { it.time }.take(30) }

    fun dismiss(n: Notif) {
        try { NotifService.instance?.cancelNotification(n.key) } catch (_: Exception) {}
        drop(n.key)
    }

    fun clear() {
        try { NotifService.instance?.cancelAllNotifications() } catch (_: Exception) {}
        list = emptyList()
    }

    /** Opens what the notification points at, on the given display. Returns false if it could not. */
    fun open(ctx: Context, n: Notif, displayId: Int): Boolean {
        val pi = n.intent ?: return false
        return try {
            val opts = ActivityOptions.makeBasic()
            opts.launchDisplayId = displayId
            if (Build.VERSION.SDK_INT >= 34) {
                opts.pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            pi.send(ctx, 0, null, null, null, null, opts.toBundle())
            true
        } catch (e: Exception) {
            false
        }
    }
}

class NotifService : NotificationListenerService() {
    companion object {
        @Volatile var instance: NotifService? = null
    }

    private fun convert(sbn: StatusBarNotification): Notif? {
        val n = sbn.notification ?: return null
        if (sbn.isOngoing || sbn.packageName == packageName) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null
        return Notif(sbn.key, sbn.packageName, title, text, sbn.postTime, n.contentIntent)
    }

    override fun onListenerConnected() {
        instance = this
        try { Notifs.replace(activeNotifications.mapNotNull(::convert)) } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = convert(sbn) ?: return
        val known = Notifs.list.any { it.key == n.key && it.title == n.title && it.text == n.text }
        Notifs.put(n)
        if (!known) DeskService.instance?.desk?.showNotification(n)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Notifs.drop(sbn.key)
}
