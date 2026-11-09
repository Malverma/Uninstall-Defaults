package com.defaultuninstaller.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.defaultuninstaller.App
import com.defaultuninstaller.MainActivity
import com.defaultuninstaller.R
import com.defaultuninstaller.privileged.AdbExecutor
import com.defaultuninstaller.privileged.BackendStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives one-time pairing with Wireless debugging. The pairing code dialog in Settings closes if
 * the user switches apps, so the code is typed into this notification's inline reply instead.
 */
class AdbPairing(private val context: Context, private val adb: AdbExecutor, private val scope: CoroutineScope) {
    private val nm = NotificationManagerCompat.from(context)
    private var watcher: Job? = null
    @Volatile private var submitting = false

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun start() {
        createChannel()
        adb.startPairingDiscovery()
        _active.value = true
        _message.value = null
        watcher?.cancel()
        watcher = scope.launch {
            launch {
                adb.pairingPort.collect { port ->
                    if (submitting) return@collect
                    if (port != null) promptForCode(null) else showWaiting()
                }
            }
            delay(TIMEOUT_MS)
            stop()
        }
    }

    fun stop() {
        watcher?.cancel()
        watcher = null
        adb.stopPairingDiscovery()
        _active.value = false
        nm.cancel(NOTIFICATION_ID)
    }

    suspend fun submit(code: String) {
        submitting = true
        _message.value = "Pairing…"
        notify(base().setContentTitle("Pairing…").setProgress(0, 0, true).setOngoing(true))
        val result = adb.pair(code)
        submitting = false
        result.onSuccess {
            stop()
            val connected = adb.status.value is BackendStatus.Ready
            val text = if (connected) "Paired and connected. You can go back to Uninstall Defaults." else "Paired. Open Uninstall Defaults to connect."
            _message.value = text
            notify(base().setContentTitle("Wireless debugging paired").setContentText(text).setAutoCancel(true).setContentIntent(openApp()))
        }.onFailure {
            val text = it.message ?: "Pairing failed"
            _message.value = text
            if (adb.pairingPort.value != null) promptForCode(text) else showWaiting(text)
        }
    }

    private fun showWaiting(error: String? = null) = notify(
        base()
            .setContentTitle(error ?: "Waiting for the pairing code")
            .setContentText("In Wireless debugging, tap \"Pair device with pairing code\".")
            .setOngoing(true)
            .addAction(0, "Cancel", cancelIntent()),
    )

    private fun promptForCode(error: String?) {
        val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
        val reply = PendingIntent.getBroadcast(
            context, 1, Intent(context, PairingCodeReceiver::class.java).setAction(ACTION_CODE),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        notify(
            base()
                .setContentTitle(error ?: "Pairing code found")
                .setContentText("Tap Enter code and type the 6 digits shown in Settings.")
                .setOngoing(true)
                .addAction(NotificationCompat.Action.Builder(0, "Enter code", reply).addRemoteInput(input).build())
                .addAction(0, "Cancel", cancelIntent()),
        )
    }

    private fun base() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)

    private fun cancelIntent() = PendingIntent.getBroadcast(
        context, 2, Intent(context, PairingCodeReceiver::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp() = PendingIntent.getActivity(
        context, 3, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(builder: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    private fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wireless debugging pairing", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val KEY_CODE = "code"
        const val ACTION_CODE = "com.defaultuninstaller.PAIRING_CODE"
        const val ACTION_CANCEL = "com.defaultuninstaller.PAIRING_CANCEL"
        private const val CHANNEL_ID = "pairing"
        private const val NOTIFICATION_ID = 7001
        private const val TIMEOUT_MS = 10 * 60_000L
    }
}

class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as App).container
        when (intent.action) {
            AdbPairing.ACTION_CANCEL -> container.pairing.stop()
            AdbPairing.ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AdbPairing.KEY_CODE)?.toString().orEmpty()
                container.scope.launch { container.pairing.submit(code) }
            }
        }
    }
}
