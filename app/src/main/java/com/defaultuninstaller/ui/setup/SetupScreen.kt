package com.defaultuninstaller.ui.setup

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.defaultuninstaller.App
import com.defaultuninstaller.privileged.AdbExecutor
import com.defaultuninstaller.privileged.BackendStatus
import com.defaultuninstaller.privileged.ShizukuExecutor
import com.defaultuninstaller.service.BlockedNotificationListener
import com.defaultuninstaller.ui.components.BackScaffold
import kotlinx.coroutines.launch

private const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
private const val FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"
private const val ADB_START = "adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh"

@Composable
fun SetupScreen(firstRun: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val c = (context.applicationContext as App).container
    val adb by c.adb.status.collectAsStateWithLifecycle()
    val adbProblem by c.adb.problem.collectAsStateWithLifecycle()
    val pairingPort by c.adb.pairingPort.collectAsStateWithLifecycle()
    val pairingActive by c.pairing.active.collectAsStateWithLifecycle()
    val pairingMessage by c.pairing.message.collectAsStateWithLifecycle()
    val shizuku by c.shizuku.status.collectAsStateWithLifecycle()
    val root by c.root.status.collectAsStateWithLifecycle()
    val active by c.backend.active.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var listenerOn by remember { mutableStateOf(BlockedNotificationListener.isEnabled(context)) }
    var checkingRoot by remember { mutableStateOf(false) }
    var rootAsked by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val suPresent = remember { c.root.suBinaryPresent() }
    fun developerOptionsOn() = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    var devOptionsOn by remember { mutableStateOf(developerOptionsOn()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        devOptionsOn = developerOptionsOn()
        c.adb.refresh()
        c.shizuku.refresh()
        listenerOn = BlockedNotificationListener.isEnabled(context)
    }

    fun open(intent: Intent) = try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Couldn't open that screen", Toast.LENGTH_SHORT).show()
    }

    fun openWirelessDebugging() = open(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(FRAGMENT_ARGS_KEY, WIRELESS_DEBUGGING_KEY)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(FRAGMENT_ARGS_KEY, WIRELESS_DEBUGGING_KEY) }),
    )

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "Without notifications, use split screen and type the code here", Toast.LENGTH_LONG).show()
        c.pairing.start()
        openWirelessDebugging()
    }

    fun startPairing() {
        if (!developerOptionsOn()) {
            // Settings silently bounces back when Developer options are off.
            Toast.makeText(context, "Turn on Developer options first: tap Build number 7 times", Toast.LENGTH_LONG).show()
            open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            c.pairing.start()
            openWirelessDebugging()
        }
    }

    fun finish() = scope.launch {
        c.pairing.stop()
        c.settings.setOnboardingDone(true)
        onDone()
    }

    BackScaffold(title = if (firstRun) "Welcome" else "Setup access", onBack = if (firstRun) null else onDone) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Android doesn't let normal apps remove preloaded apps like YouTube. Uninstall Defaults gets that power by connecting to your phone's own Wireless debugging. No root, computer or extra app needed.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("Active now: ${active.label}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            StepCard(
                title = "Wireless debugging (recommended)",
                done = adb is BackendStatus.Ready,
                status = when (adb) {
                    is BackendStatus.Ready -> "Connected"
                    BackendStatus.NeedsPermission -> "Not paired yet"
                    BackendStatus.NotRunning -> "Paired, not connected"
                    BackendStatus.NotInstalled -> "Needs Android 11 or newer"
                    BackendStatus.Unknown -> "Connecting…"
                },
            ) {
                when (adb) {
                    BackendStatus.NotInstalled ->
                        Text("This phone is older than Android 11, so the built-in option can't pair. Use Shizuku started from a computer, or root.")
                    BackendStatus.NeedsPermission -> {
                        Text(
                            "One-time setup, about a minute:\n" +
                                "1. Turn on Developer options (Settings › About phone › tap Build number 7 times).\n" +
                                "2. Tap Start pairing. Developer options opens.\n" +
                                "3. Turn on Wireless debugging, then tap \"Pair device with pairing code\".\n" +
                                "4. Pull down the notification shade, tap Enter code on the Uninstall Defaults notification, and type the 6 digits.",
                        )
                        if (devOptionsOn) {
                            Button(onClick = { startPairing() }) { Text(if (pairingActive) "Open Wireless debugging" else "Start pairing") }
                        } else {
                            Text("Developer options are off. Open About phone and tap Build number 7 times, then come back.", color = MaterialTheme.colorScheme.error)
                            Button(onClick = { open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }) { Text("Open About phone") }
                        }
                        if (pairingActive) {
                            Text(
                                if (pairingPort != null) "Pairing dialog found. Enter the code in the notification, or here if you're in split screen."
                                else "Waiting for the \"Pair device with pairing code\" dialog…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (pairingPort != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = code,
                                        onValueChange = { v -> code = v.filter(Char::isDigit).take(6) },
                                        label = { Text("Pairing code") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Button(enabled = code.length == 6, onClick = { scope.launch { c.pairing.submit(code) } }) { Text("Pair") }
                                }
                            }
                            TextButton(onClick = { c.pairing.stop() }) { Text("Cancel pairing") }
                        }
                        pairingMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    BackendStatus.NotRunning -> {
                        adbProblem?.let { Text(it) }
                        Text(
                            if (c.adb.canToggleWirelessDebugging) "Connect to Wi-Fi, then tap Connect. The app turns Wireless debugging on by itself."
                            else "Turn Wireless debugging back on in Developer options (Wi-Fi must be connected), then tap Connect.",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scope.launch { c.adb.connect() } }) { Text("Connect") }
                            OutlinedButton(onClick = { openWirelessDebugging() }) { Text("Wireless debugging") }
                        }
                    }
                    BackendStatus.Unknown -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    is BackendStatus.Ready -> Text(
                        if (c.adb.canToggleWirelessDebugging) "Paired. After a reboot the app switches Wireless debugging back on and reconnects by itself, as long as you're on Wi-Fi."
                        else "Paired. After a reboot, turn Wireless debugging back on and the app reconnects.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Only worth showing to people who already use Shizuku.
            if (shizuku != BackendStatus.NotInstalled || !AdbExecutor.isSupported) StepCard(
                title = "Shizuku (alternative)",
                done = shizuku is BackendStatus.Ready,
                status = when (val st = shizuku) {
                    is BackendStatus.Ready -> "Connected (${st.detail})"
                    BackendStatus.NeedsPermission -> "Running — grant permission"
                    BackendStatus.NotRunning -> "Installed but not running"
                    BackendStatus.NotInstalled -> "Not installed"
                    BackendStatus.Unknown -> "Checking…"
                },
            ) {
                when (shizuku) {
                    BackendStatus.NotInstalled -> {
                        Text("Install Shizuku, start it from a computer with adb, then grant permission here.")
                        Button(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD))) }) { Text("Get Shizuku") }
                    }
                    BackendStatus.NotRunning, BackendStatus.Unknown -> {
                        Text("Open Shizuku and start it, or from a computer run:")
                        Text(ADB_START, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                context.packageManager.getLaunchIntentForPackage(ShizukuExecutor.MANAGER_PACKAGE)?.let(::open)
                            }) { Text("Open Shizuku") }
                            OutlinedButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", ADB_START))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy command") }
                        }
                    }
                    BackendStatus.NeedsPermission -> Button(onClick = { c.shizuku.requestPermission() }) { Text("Grant permission") }
                    is BackendStatus.Ready -> Text("Shizuku restarts are needed after each reboot unless your phone is rooted.", style = MaterialTheme.typography.bodySmall)
                }
            }

            StepCard(
                title = "Root (only for rooted phones)",
                done = root is BackendStatus.Ready,
                status = when (root) {
                    is BackendStatus.Ready -> "Granted"
                    BackendStatus.NeedsPermission -> "Denied"
                    BackendStatus.NotInstalled -> "Not rooted"
                    else -> if (suPresent) "su found — not requested yet" else "Not rooted"
                },
            ) {
                when {
                    root is BackendStatus.Ready -> Unit
                    !suPresent && !rootAsked -> {
                        Text("This phone isn't rooted (no su binary), so root isn't available. Use Wireless debugging above instead.")
                        TextButton(onClick = {
                            scope.launch {
                                rootAsked = true
                                c.backend.requestRoot()
                            }
                        }) { Text("Try anyway") }
                    }
                    else -> {
                        when (root) {
                            BackendStatus.NotInstalled -> Text("su couldn't be run. This phone isn't rooted, so use Wireless debugging above.", color = MaterialTheme.colorScheme.error)
                            BackendStatus.NeedsPermission -> Text("Root was denied. Allow Uninstall Defaults in your root manager (Magisk, KernelSU, APatch) and try again.", color = MaterialTheme.colorScheme.error)
                            else -> Unit
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(enabled = !checkingRoot, onClick = {
                                scope.launch {
                                    checkingRoot = true
                                    rootAsked = true
                                    c.backend.requestRoot()
                                    checkingRoot = false
                                }
                            }) { Text("Request root") }
                            if (checkingRoot) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            StepCard(
                title = "Notification cleanup (optional)",
                done = listenerOn,
                status = if (listenerOn) "Allowed" else "Not allowed",
            ) {
                Text("Lets the app clear notifications that blocked apps already posted, and dismiss any that slip through.")
                if (!listenerOn) Button(onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Allow notification access") }
            }

            Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (active.isPrivileged) "Continue" else "Continue in limited mode")
            }
        }
    }
}

@Composable
private fun StepCard(title: String, done: Boolean, status: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (done) "Done" else "Not done",
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}
