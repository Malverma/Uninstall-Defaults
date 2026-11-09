package com.defaultuninstaller.ui.applist

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.defaultuninstaller.data.safety.SafetyRating
import com.defaultuninstaller.ui.components.AppIcon
import com.defaultuninstaller.ui.components.Badge
import com.defaultuninstaller.ui.components.SafetyBadge
import com.defaultuninstaller.ui.theme.safetyColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppListScreen(
    vm: AppListViewModel,
    onOpenRemoved: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val context = LocalContext.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val query by vm.currentQuery.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val backend by vm.backend.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val bulk by vm.bulk.collectAsStateWithLifecycle()
    val bulkResult by vm.bulkResult.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<UiEvent.Failure?>(null) }
    var detailPkg by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmUninstallPkg by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmBulk by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = searching || selection.isNotEmpty()) {
        if (selection.isNotEmpty()) {
            vm.clearSelection()
        } else {
            vm.setSearch("")
            searching = false
        }
    }

    LaunchedEffect(Unit) {
        vm.uiEvents.collect { event ->
            when (event) {
                is UiEvent.Launch -> try {
                    context.startActivity(event.intent)
                } catch (e: ActivityNotFoundException) {
                    snackbar.showSnackbar("No app can handle this on your device")
                }
                is UiEvent.Failure -> failure = event
                is UiEvent.Message -> scope.launch {
                    val result = snackbar.showSnackbar(
                        message = event.text,
                        actionLabel = if (event.undo != null) "Undo" else null,
                        duration = if (event.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) event.undo?.invoke()
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selection.isNotEmpty()) {
                SelectionBar(
                    count = selection.size,
                    onClear = vm::clearSelection,
                    onSelectAll = vm::selectAllVisible,
                    onUninstall = { confirmBulk = true },
                    onBlock = vm::bulkBlockNotifications,
                )
            } else {
                MainTopBar(
                    searching = searching,
                    search = query.text,
                    onSearch = vm::setSearch,
                    onToggleSearch = {
                        if (searching) vm.setSearch("")
                        searching = !searching
                    },
                    backendLabel = backend.label,
                    privileged = backend.isPrivileged,
                    onBackendClick = onOpenSetup,
                    sort = query.sort,
                    onSort = vm::setSort,
                    onOpenRemoved = onOpenRemoved,
                    onOpenHistory = onOpenHistory,
                    onOpenSettings = onOpenSettings,
                    onRefresh = vm::refresh,
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppFilter.entries.forEach { f ->
                    FilterChip(selected = query.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) })
                }
            }
            if (!backend.isPrivileged) LimitedBanner(onOpenSetup)

            PullToRefreshBox(isRefreshing = loading, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                if (rows.isEmpty() && !loading) {
                    Text(
                        "No apps match",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(rows, key = { it.app.packageName }) { row ->
                        AppRowItem(
                            row = row,
                            showPackageName = settings.showPackageNames,
                            selected = row.app.packageName in selection,
                            selectionMode = selection.isNotEmpty(),
                            busy = row.app.packageName in busy,
                            onClick = {
                                if (selection.isNotEmpty()) vm.toggleSelection(row.app.packageName)
                                else detailPkg = row.app.packageName
                            },
                            onLongClick = { vm.toggleSelection(row.app.packageName) },
                            onUninstall = { confirmUninstallPkg = row.app.packageName },
                            onToggleNotifications = { vm.setNotificationsBlocked(row.app, !row.notificationsBlocked) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    detailPkg?.let { pkg ->
        val row = vm.rowFor(pkg)
        if (row == null) {
            detailPkg = null
        } else {
            AppDetailSheet(
                row = row,
                busy = pkg in busy,
                onDismiss = { detailPkg = null },
                onUninstall = { confirmUninstallPkg = pkg },
                onSetEnabled = { vm.setEnabled(row.app, it) },
                onToggleNotifications = { vm.setNotificationsBlocked(row.app, !row.notificationsBlocked) },
                onUninstallUpdates = { vm.uninstallUpdates(row.app) },
            )
        }
    }

    confirmUninstallPkg?.let { pkg ->
        val row = vm.rowFor(pkg)
        if (row == null) {
            confirmUninstallPkg = null
        } else {
            UninstallDialog(
                row = row,
                defaultKeepData = settings.keepData,
                expertMode = settings.expertMode,
                privileged = backend.isPrivileged,
                onDismiss = { confirmUninstallPkg = null },
                onUninstall = { keep ->
                    confirmUninstallPkg = null
                    detailPkg = null
                    vm.uninstall(row.app, keep)
                },
                onDisable = {
                    confirmUninstallPkg = null
                    vm.setEnabled(row.app, false)
                },
                onBlockNotifications = {
                    confirmUninstallPkg = null
                    vm.setNotificationsBlocked(row.app, true)
                },
            )
        }
    }

    if (confirmBulk) {
        val critical = vm.criticalInSelection()
        val count = selection.size - critical.size
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = { Text("Uninstall $count apps?") },
            text = {
                Column {
                    Text("Preloaded apps are removed for your user and can be restored from Removed Apps.")
                    if (critical.isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "Skipping ${critical.size} critical: " + critical.joinToString { it.app.label },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = count > 0, onClick = {
                    confirmBulk = false
                    critical.forEach { vm.toggleSelection(it.app.packageName) }
                    vm.bulkUninstall()
                }) { Text("Uninstall") }
            },
            dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("Cancel") } },
        )
    }

    bulk?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${progress.done} / ${progress.total}") },
            text = {
                Column {
                    Text(progress.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.size(12.dp))
                    LinearProgressIndicator(
                        progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {},
        )
    }

    bulkResult?.let { result ->
        AlertDialog(
            onDismissRequest = vm::dismissBulkResult,
            title = { Text("${result.succeeded.size} done, ${result.failed.size} failed") },
            text = {
                LazyColumn {
                    items(result.failed) { (label, why) ->
                        Text("$label — $why", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissBulkResult) { Text("OK") } },
        )
    }

    failure?.let { f ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(f.title) },
            text = {
                Column {
                    Text(f.message)
                    if (!f.output.isNullOrBlank()) {
                        Spacer(Modifier.size(12.dp))
                        Text(f.output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    searching: Boolean,
    search: String,
    onSearch: (String) -> Unit,
    onToggleSearch: () -> Unit,
    backendLabel: String,
    privileged: Boolean,
    onBackendClick: () -> Unit,
    sort: AppSort,
    onSort: (AppSort) -> Unit,
    onOpenRemoved: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            if (searching) {
                TextField(
                    value = search,
                    onValueChange = onSearch,
                    placeholder = { Text("Search name or package") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("Uninstall Defaults", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        actions = {
            if (!searching) {
                AssistChip(
                    onClick = onBackendClick,
                    label = { Text(backendLabel, maxLines = 1) },
                    leadingIcon = {
                        Icon(
                            if (privileged) Icons.Default.CheckCircle else Icons.Default.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = if (privileged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        leadingIconContentColor = if (privileged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    ),
                )
            }
            IconButton(onClick = onToggleSearch) {
                Icon(if (searching) Icons.Default.Close else Icons.Default.Search, contentDescription = if (searching) "Close search" else "Search")
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    AppSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            onClick = { onSort(s); sortMenu = false },
                            trailingIcon = { if (s == sort) Icon(Icons.Default.CheckCircle, contentDescription = "Selected") },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(text = { Text("Removed apps") }, leadingIcon = { Icon(Icons.Default.Restore, null) }, onClick = { overflow = false; onOpenRemoved() })
                    DropdownMenuItem(text = { Text("History") }, leadingIcon = { Icon(Icons.Default.History, null) }, onClick = { overflow = false; onOpenHistory() })
                    DropdownMenuItem(text = { Text("Setup access") }, leadingIcon = { Icon(Icons.Default.Shield, null) }, onClick = { overflow = false; onBackendClick() })
                    DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { overflow = false; onOpenSettings() })
                    DropdownMenuItem(text = { Text("Refresh") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = { overflow = false; onRefresh() })
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, onSelectAll: () -> Unit, onUninstall: () -> Unit, onBlock: () -> Unit) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Clear selection") } },
        title = { Text("$count selected") },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, contentDescription = "Select all visible") }
            IconButton(onClick = onBlock) { Icon(Icons.Outlined.NotificationsOff, contentDescription = "Block notifications for selected") }
            IconButton(onClick = onUninstall) { Icon(Icons.Default.Delete, contentDescription = "Uninstall selected") }
        },
    )
}

@Composable
private fun LimitedBanner(onSetup: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Limited mode: only apps you installed can be removed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSetup) { Text("Set up") }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AppRowItem(
    row: AppRow,
    showPackageName: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUninstall: () -> Unit,
    onToggleNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = row.app
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onLongClick() })
            }
            AppIcon(app.packageName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showPackageName) {
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    if (app.isSystem) SafetyBadge(row.safety.rating)
                    if (app.isUpdatedSystem) Badge("Updated", MaterialTheme.colorScheme.secondary)
                    else if (app.isSystem) Badge("System", MaterialTheme.colorScheme.secondary)
                    if (!app.isEnabled) Badge("Disabled", MaterialTheme.colorScheme.tertiary)
                    if (row.notificationsBlocked) Badge("Muted", MaterialTheme.colorScheme.tertiary)
                }
            }
            if (busy) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            } else if (!selectionMode) {
                IconButton(onClick = onToggleNotifications) {
                    Icon(
                        if (row.notificationsBlocked) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                        contentDescription = if (row.notificationsBlocked) "Allow notifications for ${app.label}" else "Block notifications for ${app.label}",
                        tint = if (row.notificationsBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = onUninstall,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.semantics { contentDescription = "Uninstall ${app.label}" },
                ) { Text("Uninstall") }
            }
        }
        HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun UninstallDialog(
    row: AppRow,
    defaultKeepData: Boolean,
    expertMode: Boolean,
    privileged: Boolean,
    onDismiss: () -> Unit,
    onUninstall: (keepData: Boolean) -> Unit,
    onDisable: () -> Unit,
    onBlockNotifications: () -> Unit,
) {
    val app = row.app
    val rating = row.safety.rating
    var keepData by rememberSaveable { mutableStateOf(defaultKeepData) }
    var typed by rememberSaveable { mutableStateOf("") }
    val critical = rating == SafetyRating.CRITICAL
    val locked = critical && !expertMode
    val confirmWord = "REMOVE"

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppIcon(app.packageName, size = 48.dp) },
        title = { Text(if (locked) "${app.label} is protected" else "Uninstall ${app.label}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (app.isSystem) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SafetyBadge(rating)
                        Spacer(Modifier.width(8.dp))
                        Text(row.safety.description, style = MaterialTheme.typography.bodySmall, color = safetyColor(rating))
                    }
                }
                when {
                    locked -> Text("Removing this can stop your phone from working. Uninstall and disable are blocked unless you turn on Expert mode in Settings. You can still block its notifications.")
                    !app.isSystem -> Text("This permanently uninstalls the app and deletes its data.")
                    !privileged -> Text("Removing preloaded apps needs Shizuku or root. Set it up from the status chip at the top.")
                    else -> {
                        Text("This removes ${app.label} for your user. You can restore it any time from Removed Apps; a factory reset also brings it back.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = keepData, onCheckedChange = { keepData = it })
                            Text("Keep app data (faster restore)")
                        }
                    }
                }
                if (critical && expertMode) {
                    Text("Type $confirmWord to confirm.", color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true)
                }
                if (app.isSystem && app.isEnabled && !locked) {
                    TextButton(onClick = onDisable, contentPadding = PaddingValues(0.dp)) { Text("Disable instead") }
                }
            }
        },
        confirmButton = {
            val canUninstall = !locked && (app.isSystem.not() || privileged) && (!critical || typed.trim() == confirmWord)
            if (!locked) {
                TextButton(enabled = canUninstall, onClick = { onUninstall(keepData) }) {
                    Text("Uninstall", color = if (canUninstall) MaterialTheme.colorScheme.error else Color.Unspecified)
                }
            } else {
                TextButton(onClick = onBlockNotifications) { Text("Block notifications") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
