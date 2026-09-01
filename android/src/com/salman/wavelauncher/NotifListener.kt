package com.salman.wavelauncher

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Live per-package notification counts, read by the home list adapter. */
object NotifCounts {
    @Volatile
    var counts: Map<String, Int> = emptyMap()
}

class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        update()
        notifyUi()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        update()
        notifyUi()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        update()
        notifyUi()
    }

    private fun update() {
        val m = HashMap<String, Int>()
        try {
            for (n in activeNotifications) {
                if (n.isOngoing) continue
                m[n.packageName] = (m[n.packageName] ?: 0) + 1
            }
        } catch (_: Exception) { }
        NotifCounts.counts = m
    }

    private fun notifyUi() {
        sendBroadcast(Intent(ACTION_COUNTS_CHANGED).setPackage(packageName))
    }

    companion object {
        const val ACTION_COUNTS_CHANGED = "com.salman.wavelauncher.COUNTS_CHANGED"
    }
}
