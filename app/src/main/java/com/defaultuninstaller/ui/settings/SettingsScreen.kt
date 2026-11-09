package com.defaultuninstaller.ui.settings

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.defaultuninstaller.App
import com.defaultuninstaller.BuildConfig
import com.defaultuninstaller.data.settings.BackendPreference
import com.defaultuninstaller.data.settings.ThemeMode
import com.defaultuninstaller.domain.ProfilePlan
import com.defaultuninstaller.service.BlockedNotificationListener
import com.defaultuninstaller.ui.components.BackScaffold
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = (context.applicationContext as App).container
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val backend by c.backend.active.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var listenerOn by remember { mutableStateOf(BlockedNotificationListener.isEnabled(context)) }
    var confirmExpert by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<ProfilePlan?>(null) }
    var applying by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { listenerOn = BlockedNotificationListener.isEnabled(context) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { c.profiles.export(uri) }
                .onSuccess { snackbar.showSnackbar("Profile exported") }
                .onFailure { snackbar.showSnackbar("Export failed: ${it.message}") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { c.profiles.plan(uri) }
                .onSuccess { plan = it }
                .onFailure { snackbar.showSnackbar("Not a valid profile: ${it.message}") }
        }
    }

    BackScaffold(title = "Settings", onBack = onBack, snackbar = snackbar) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Section("Privileged access")
            Text(
                "Active: ${backend.label}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            BackendPreference.entries.forEach { pref ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = s.backend == pref, onClick = {
                            scope.launch {
                                c.settings.setBackend(pref)
                                if (pref == BackendPreference.ROOT) c.backend.requestRoot()
                            }
                        })
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.backend == pref, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(
                            when (pref) {
                                BackendPreference.AUTO -> "Automatic"
                                BackendPreference.SHIZUKU -> "Shizuku only"
                                BackendPreference.ROOT -> "Root only"
                                BackendPreference.LIMITED -> "Limited (no elevated access)"
                            },
                        )
                        Text(
                            when (pref) {
                                BackendPreference.AUTO -> "Shizuku if running, otherwise root"
                                BackendPreference.SHIZUKU -> "ADB-level, no root needed"
                                BackendPreference.ROOT -> "Uses su (Magisk, KernelSU, APatch)"
                                BackendPreference.LIMITED -> "Only user apps; notifications via system settings"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Section("Removal")
            Toggle("Keep app data on removal", "Makes restoring preloaded apps instant", s.keepData) { scope.launch { c.settings.setKeepData(it) } }
            Toggle("Apply to all users", "Remove/restore for every user and work profile, not just you", s.allUsers) { scope.launch { c.settings.setAllUsers(it) } }
            Toggle("Expert mode", "Allow removing critical system packages", s.expertMode) {
                if (it) confirmExpert = true else scope.launch { c.settings.setExpertMode(false) }
            }

            Section("Notifications")
            ListItem(
                headlineContent = { Text("Notification cleanup") },
                supportingContent = {
                    Text(if (listenerOn) "On — clears notifications from blocked apps" else "Off — tap to allow clearing existing notifications")
                },
                modifier = Modifier.clickable {
                    context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
            )

            Section("Display")
            Toggle("Show package names", null, s.showPackageNames) { scope.launch { c.settings.setShowPackageNames(it) } }
            Toggle("Dynamic color", "Use your wallpaper colors (Android 12+)", s.dynamicColor) { scope.launch { c.settings.setDynamicColor(it) } }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Theme", Modifier.weight(1f))
                ThemeMode.entries.forEach { mode ->
                    TextButton(onClick = { scope.launch { c.settings.setTheme(mode) } }) {
                        Text(
                            mode.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = if (s.theme == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Section("Profiles")
            ListItem(
                headlineContent = { Text("Export profile") },
                supportingContent = { Text("Save the list of removed, disabled and muted apps") },
                modifier = Modifier.clickable { exportLauncher.launch("uninstall-defaults-profile.json") },
            )
            ListItem(
                headlineContent = { Text("Import profile") },
                supportingContent = { Text("Apply a saved profile, e.g. on a new phone") },
                modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )

            Section("About")
            ListItem(
                headlineContent = { Text("Uninstall Defaults ${BuildConfig.VERSION_NAME}") },
                supportingContent = {
                    Text("Preloaded apps are removed for your user only (pm uninstall --user). The factory copy stays on the read-only system partition, so they can always be restored, and a factory reset brings them back.")
                },
            )
        }
    }

    if (confirmExpert) {
        AlertDialog(
            onDismissRequest = { confirmExpert = false },
            title = { Text("Turn on Expert mode?") },
            text = { Text("Removing critical packages like System UI or Settings can bootloop your phone. If that happens you may need a factory reset to recover.") },
            confirmButton = {
                TextButton(onClick = { confirmExpert = false; scope.launch { c.settings.setExpertMode(true) } }) {
                    Text("I understand", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmExpert = false }) { Text("Cancel") } },
        )
    }

    plan?.let { p ->
        AlertDialog(
            onDismissRequest = { if (applying == null) plan = null },
            title = { Text(if (applying != null) "Applying…" else "Apply profile?") },
            text = {
                Column {
                    if (applying != null) {
                        Text(applying!!)
                    } else {
                        Text("Remove ${p.toRemove.size} · Disable ${p.toDisable.size} · Mute ${p.toBlock.size}")
                        if (p.skippedCritical.isNotEmpty()) Text("Skipping ${p.skippedCritical.size} critical package(s)", color = MaterialTheme.colorScheme.error)
                        if (p.notOnDevice.isNotEmpty()) Text("${p.notOnDevice.size} package(s) aren't on this device", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!backend.isPrivileged) Text("Needs Shizuku or root.", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = applying == null && p.total > 0 && backend.isPrivileged, onClick = {
                    scope.launch {
                        var failed = 0
                        val total = p.total
                        var done = 0
                        suspend fun step(pkg: String, action: suspend (com.defaultuninstaller.data.packages.AppInfo) -> Boolean) {
                            applying = "${++done} / $total  $pkg"
                            val app = c.packages.find(pkg)
                            if (app == null || !action(app)) failed++
                        }
                        p.toRemove.forEach { step(it) { app -> c.actions.uninstall(app, s.keepData).success } }
                        p.toDisable.forEach { step(it) { app -> c.actions.setEnabled(app, false).success } }
                        p.toBlock.forEach { step(it) { app -> c.actions.setNotificationsBlocked(app, true).success } }
                        applying = null
                        plan = null
                        snackbar.showSnackbar(if (failed == 0) "Profile applied" else "Profile applied, $failed failed (see History)")
                    }
                }) { Text("Apply") }
            },
            dismissButton = { if (applying == null) TextButton(onClick = { plan = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
