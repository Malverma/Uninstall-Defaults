package com.defaultuninstaller.ui.removed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.defaultuninstaller.App
import com.defaultuninstaller.ui.components.AppIcon
import com.defaultuninstaller.ui.components.BackScaffold
import kotlinx.coroutines.launch

@Composable
fun RemovedAppsScreen(onBack: () -> Unit) {
    val c = (LocalContext.current.applicationContext as App).container
    val apps by c.packages.apps.collectAsStateWithLifecycle()
    val backend by c.backend.active.collectAsStateWithLifecycle()
    val removed = remember(apps) { apps.filter { it.isSystem && !it.isInstalledForUser }.sortedBy { it.label.lowercase() } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(setOf<String>()) }
    var confirmAll by remember { mutableStateOf(false) }

    fun restore(pkgs: List<String>) = scope.launch {
        busy = busy + pkgs
        var failed = 0
        for (pkg in pkgs) {
            val app = c.packages.find(pkg) ?: continue
            if (!c.actions.restore(app).success) failed++
            busy = busy - pkg
        }
        snackbar.showSnackbar(if (failed == 0) "Restored ${pkgs.size} app(s)" else "$failed of ${pkgs.size} failed to restore")
    }

    BackScaffold(
        title = "Removed apps",
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            if (removed.isNotEmpty() && backend.isPrivileged) TextButton(onClick = { confirmAll = true }) { Text("Restore all") }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (removed.isEmpty()) {
                Text(
                    "No preloaded apps have been removed.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn {
                if (!backend.isPrivileged && removed.isNotEmpty()) {
                    item {
                        Text(
                            "Restoring needs Shizuku or root.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(removed, key = { it.packageName }) { app ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app.packageName)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (app.packageName in busy) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            FilledTonalButton(enabled = backend.isPrivileged, onClick = { restore(listOf(app.packageName)) }) { Text("Restore") }
                        }
                    }
                }
            }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Restore ${removed.size} apps?") },
            text = { Text("Every preloaded app you removed will be reinstalled for your user.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    restore(removed.map { it.packageName })
                }) { Text("Restore all") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}
