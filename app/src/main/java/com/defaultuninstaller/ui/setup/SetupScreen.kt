package com.defaultuninstaller.ui.setup

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.defaultuninstaller.App
import com.defaultuninstaller.privileged.BackendStatus
import com.defaultuninstaller.privileged.ShizukuExecutor
import com.defaultuninstaller.service.BlockedNotificationListener
import com.defaultuninstaller.ui.components.BackScaffold
import kotlinx.coroutines.launch

private const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
private const val ADB_START = "adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh"

@Composable
fun SetupScreen(firstRun: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val c = (context.applicationContext as App).container
    val shizuku by c.shizuku.status.collectAsStateWithLifecycle()
    val root by c.root.status.collectAsStateWithLifecycle()
    val active by c.backend.active.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var listenerOn by remember { mutableStateOf(BlockedNotificationListener.isEnabled(context)) }
    var checkingRoot by remember { mutableStateOf(false) }
    val suPresent = remember { c.root.suBinaryPresent() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        c.shizuku.refresh()
        listenerOn = BlockedNotificationListener.isEnabled(context)
    }

    fun open(intent: Intent) = try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Couldn't open that screen", Toast.LENGTH_SHORT).show()
    }

    fun finish() = scope.launch {
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
                "Android doesn't let normal apps remove preloaded apps like YouTube. Uninstall Defaults gets that power from Shizuku (ADB-level, no root) or from root. Either one works; you only need one.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("Active now: ${active.label}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            StepCard(
                title = "Shizuku (recommended, no root)",
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
                        Text("1. Install Shizuku.\n2. Turn on Developer options and Wireless debugging.\n3. Open Shizuku and tap Start (pairing).\n4. Come back here and grant permission.")
                        Button(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD))) }) { Text("Get Shizuku") }
                    }
                    BackendStatus.NotRunning, BackendStatus.Unknown -> {
                        Text("Open Shizuku and start it with Wireless debugging (Android 11+), or from a computer run:")
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
                        TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text("Developer options") }
                    }
                    BackendStatus.NeedsPermission -> Button(onClick = { c.shizuku.requestPermission() }) { Text("Grant permission") }
                    is BackendStatus.Ready -> Text("Shizuku restarts are needed after each reboot unless your phone is rooted.", style = MaterialTheme.typography.bodySmall)
                }
            }

            StepCard(
                title = "Root (optional)",
                done = root is BackendStatus.Ready,
                status = when (root) {
                    is BackendStatus.Ready -> "Granted"
                    BackendStatus.NeedsPermission -> "Denied"
                    BackendStatus.NotInstalled -> "Not rooted"
                    else -> if (suPresent) "su found — not requested yet" else "No su binary found"
                },
            ) {
                if (root !is BackendStatus.Ready) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = !checkingRoot, onClick = {
                            scope.launch {
                                checkingRoot = true
                                c.backend.requestRoot()
                                checkingRoot = false
                            }
                        }) { Text("Request root") }
                        if (checkingRoot) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
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
