package com.defaultuninstaller.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.defaultuninstaller.App
import com.defaultuninstaller.data.history.ActionType
import com.defaultuninstaller.data.history.HistoryEntry
import com.defaultuninstaller.ui.components.AppIcon
import com.defaultuninstaller.ui.components.BackScaffold
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val ActionType.label get() = when (this) {
    ActionType.UNINSTALL -> "Uninstalled"
    ActionType.UNINSTALL_SYSTEM -> "Removed (preloaded)"
    ActionType.UNINSTALL_UPDATES -> "Uninstalled updates"
    ActionType.RESTORE -> "Restored"
    ActionType.DISABLE -> "Disabled"
    ActionType.ENABLE -> "Enabled"
    ActionType.BLOCK_NOTIFICATIONS -> "Blocked notifications"
    ActionType.UNBLOCK_NOTIFICATIONS -> "Allowed notifications"
}

private val ActionType.undoable get() = this != ActionType.UNINSTALL && this != ActionType.UNINSTALL_UPDATES

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val c = (LocalContext.current.applicationContext as App).container
    val entries by remember { c.history.observeAll() }.collectAsState(initial = emptyList())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<HistoryEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    BackScaffold(
        title = "History",
        onBack = onBack,
        snackbar = snackbar,
        actions = { if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") } },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (entries.isEmpty()) {
                Text("Nothing yet.", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn {
                items(entries, key = { it.id }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(entry.packageName)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(entry.appLabel, style = MaterialTheme.typography.titleSmall)
                            Text(
                                entry.action.label + if (entry.success) "" else " — failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                            Text(format.format(Date(entry.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (!entry.output.isNullOrBlank()) TextButton(onClick = { detail = entry }) { Text("Log") }
                        if (entry.success && entry.action.undoable) {
                            TextButton(onClick = {
                                scope.launch { snackbar.showSnackbar(c.actions.undo(entry).message) }
                            }) { Text("Undo") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    detail?.let { entry ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(entry.appLabel) },
            text = { Text(entry.output.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("OK") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This only clears the log. Removed apps stay restorable from Removed Apps.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { c.history.clear() } }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
