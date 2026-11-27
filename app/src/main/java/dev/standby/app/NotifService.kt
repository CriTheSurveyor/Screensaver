package dev.standby.app

import android.app.Notification
import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap

data class Notif(val key: String, val app: String, val icon: ImageBitmap?, val title: String, val text: String, val time: Long)

/** Every notification on the phone, mirrored into StandBy's own list and pop-up. */
class NotifService : NotificationListenerService() {
    private val icons = HashMap<String, Pair<String, ImageBitmap?>>()

    override fun onListenerConnected() {
        Bus.notifs.value = activeNotifications.orEmpty().mapNotNull(::toNotif).sortedByDescending { it.time }
        // Android rebinds this listener by itself after the app's process is killed: the watcher comes back with it
        if (Prefs(this).enabled) runCatching { WatchService.start(this) }
        if (!Bus.shown) hush(this, false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = toNotif(sbn) ?: return
        val old = Bus.notifs.value
        val before = old.firstOrNull { it.key == n.key }
        Bus.notifs.value = listOf(n) + old.filter { it.key != n.key }
        // a chat that updates its one notification pops up again for each new message, unless it asks to alert once
        val alertOnce = sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        if (Bus.shown && (before == null || (before.text != n.text && !alertOnce))) Bus.incoming.tryEmit(n)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Bus.notifs.value = Bus.notifs.value.filter { it.key != sbn.key }
    }

    private fun toNotif(sbn: StatusBarNotification): Notif? {
        val n = sbn.notification
        // music players, downloads, our own watcher and the group headers are not messages
        if (sbn.packageName == packageName || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val e = n.extras
        val chat = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val title = (chat?.conversationTitle ?: e.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: e.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        // the whole text, never the one-line summary: big text, inbox lines or every message of a chat
        val text = chat?.messages?.takeIf { it.isNotEmpty() }?.joinToString("\n") { m ->
            val who = m.person?.name?.toString()
            val line = m.text?.toString().orEmpty()
            if (who != null && who != title) "$who: $line" else line
        } ?: e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            ?: e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val (app, icon) = icons.getOrPut(sbn.packageName) { appInfo(sbn) }
        return Notif(sbn.key, app, icon, title, text, sbn.postTime)
    }

    private fun appInfo(sbn: StatusBarNotification): Pair<String, ImageBitmap?> {
        val pm = packageManager
        val info = runCatching { pm.getApplicationInfo(sbn.packageName, 0) }.getOrNull()
        val label = info?.let { pm.getApplicationLabel(it).toString() } ?: sbn.packageName
        // the real full-colour app icon; the notification's own small icon only if the app can't be looked up
        val drawable = info?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() } ?: sbn.notification.smallIcon?.loadDrawable(this)
        return label to drawable?.let { runCatching { it.toBitmap(144, 144).asImageBitmap() }.getOrNull() }
    }

    companion object {
        fun enabled(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /**
         * The system's drop-down banner off while StandBy is on screen: Do Not Disturb that still lets alarms,
         * calls and media through and hides only the banner. The user's own settings are saved and put back.
         * A notification listener holds DND access, so no extra permission is asked.
         */
        fun hush(context: Context, on: Boolean) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) return
            val prefs = Prefs(context)
            runCatching {
                if (on) {
                    if (prefs.savedZen != null) return
                    val p = nm.notificationPolicy
                    val conv = if (Build.VERSION.SDK_INT >= 30) p.priorityConversationSenders else 0
                    prefs.savedZen = listOf(nm.currentInterruptionFilter, p.priorityCategories, p.priorityCallSenders, p.priorityMessageSenders, p.suppressedVisualEffects, conv).joinToString(",")
                    val allow = Policy.PRIORITY_CATEGORY_ALARMS or Policy.PRIORITY_CATEGORY_MEDIA or Policy.PRIORITY_CATEGORY_SYSTEM or
                        Policy.PRIORITY_CATEGORY_CALLS or Policy.PRIORITY_CATEGORY_REPEAT_CALLERS
                    nm.notificationPolicy = policy(allow, Policy.PRIORITY_SENDERS_ANY, Policy.PRIORITY_SENDERS_ANY, Policy.SUPPRESSED_EFFECT_PEEK, Policy.CONVERSATION_SENDERS_NONE)
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                } else {
                    val s = prefs.savedZen?.split(",")?.map(String::toInt) ?: return
                    nm.notificationPolicy = policy(s[1], s[2], s[3], s[4], s[5])
                    nm.setInterruptionFilter(s[0])
                    prefs.savedZen = null
                }
            }
        }

        private fun policy(categories: Int, calls: Int, messages: Int, effects: Int, conversations: Int) =
            if (Build.VERSION.SDK_INT >= 30) Policy(categories, calls, messages, effects, conversations)
            else Policy(categories, calls, messages, effects)
    }
}
