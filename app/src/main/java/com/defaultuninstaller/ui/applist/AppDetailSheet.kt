package com.defaultuninstaller.ui.applist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.defaultuninstaller.data.safety.SafetyRating
import com.defaultuninstaller.ui.components.AppIcon
import com.defaultuninstaller.ui.components.SafetyBadge
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailSheet(
    row: AppRow,
    busy: Boolean,
    onDismiss: () -> Unit,
    onUninstall: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onToggleNotifications: () -> Unit,
    onUninstallUpdates: () -> Unit,
) {
    val app = row.app
    val context = LocalContext.current
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.packageName, size = 64.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(app.label, style = MaterialTheme.typography.headlineSmall)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(
                        "Version ${app.versionName ?: "?"} (${app.versionCode})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))

            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SafetyBadge(row.safety.rating)
                    Spacer(Modifier.width(8.dp))
                    Text(row.safety.description, style = MaterialTheme.typography.bodyMedium)
                }
                Info("Type", when {
                    app.isUpdatedSystem -> "Preloaded (updated)"
                    app.isSystem -> "Preloaded"
                    else -> "User installed"
                })
                Info("Source", sourceLabel(app.installer, app.isSystem))
                Info("APK", app.apkPath)
                Info("Size", Formatter.formatShortFileSize(context, app.sizeBytes))
                Info("Installed", dates.format(Date(app.firstInstallTime)))
                Info("Updated", dates.format(Date(app.lastUpdateTime)))
                Info("Status", buildString {
                    append(if (app.isEnabled) "Enabled" else "Disabled")
                    if (row.notificationsBlocked) append(" · notifications blocked")
                })
            }

            val critical = row.safety.rating == SafetyRating.CRITICAL
            Action(Icons.Default.Delete, "Uninstall", enabled = !busy) { onUninstall() }
            if (app.isEnabled) {
                Action(Icons.Default.Block, "Disable", supporting = if (critical) "Needs Expert mode" else null, enabled = !busy) { onSetEnabled(false) }
            } else {
                Action(Icons.Default.PlayArrow, "Enable", enabled = !busy) { onSetEnabled(true) }
            }
            Action(
                if (row.notificationsBlocked) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                if (row.notificationsBlocked) "Allow notifications" else "Block all notifications",
                supporting = if (row.notificationsBlocked) null else "Turns off every channel and clears what it already posted",
                enabled = !busy,
            ) { onToggleNotifications() }
            if (app.isUpdatedSystem) {
                Action(Icons.Default.SystemUpdateAlt, "Uninstall updates", supporting = "Revert to the factory version", enabled = !busy) { onUninstallUpdates() }
            }
            Action(Icons.AutoMirrored.Filled.OpenInNew, "Open in system settings") {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
            }
            Action(Icons.Default.ContentCopy, "Copy package name") {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("package", app.packageName))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Action(icon: ImageVector, title: String, supporting: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

private fun sourceLabel(installer: String?, system: Boolean) = when (installer) {
    "com.android.vending" -> "Google Play"
    "com.sec.android.app.samsungapps" -> "Galaxy Store"
    "com.xiaomi.mipicks" -> "GetApps"
    "org.fdroid.fdroid" -> "F-Droid"
    "com.google.android.packageinstaller", "com.android.packageinstaller" -> "Sideloaded"
    null -> if (system) "Preinstalled" else "Unknown"
    else -> installer
}
