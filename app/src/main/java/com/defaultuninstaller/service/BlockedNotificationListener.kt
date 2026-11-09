package com.defaultuninstaller.service

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.defaultuninstaller.App

/**
 * Dismisses notifications from blocked apps: clears what they already posted and anything
 * that slips past the system-level block. Foreground-service notifications can't be dismissed
 * by any app; Android doesn't allow it.
 */
class BlockedNotificationListener : NotificationListenerService() {

    private val blocked: Set<String>
        get() = (application as App).container.settings.settings.value.blockedPackages

    override fun onListenerConnected() {
        instance = this
        sweep(blocked)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in blocked && sbn.isClearable) cancelNotification(sbn.key)
    }

    private fun sweep(packages: Set<String>) {
        if (packages.isEmpty()) return
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.filter { it.packageName in packages && it.isClearable }.forEach { cancelNotification(it.key) }
    }

    companion object {
        @Volatile private var instance: BlockedNotificationListener? = null

        fun clearFor(pkg: String) {
            instance?.sweep(setOf(pkg))
        }

        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun component(context: Context) = ComponentName(context, BlockedNotificationListener::class.java)
    }
}
