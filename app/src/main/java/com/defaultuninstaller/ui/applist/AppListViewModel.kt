package com.defaultuninstaller.ui.applist

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.defaultuninstaller.AppContainer
import com.defaultuninstaller.data.packages.AppInfo
import com.defaultuninstaller.data.safety.Safety
import com.defaultuninstaller.data.safety.SafetyRating
import com.defaultuninstaller.domain.ActionOutcome
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppFilter(val label: String) { ALL("All"), DISABLED("Disabled") }

data class AppRow(val app: AppInfo, val safety: Safety, val notificationsBlocked: Boolean)

data class Query(val text: String = "", val filter: AppFilter = AppFilter.ALL)

data class BulkProgress(val done: Int, val total: Int, val current: String)

data class BulkResult(val succeeded: List<String>, val failed: List<Pair<String, String>>)

sealed interface UiEvent {
    data class Message(val text: String, val undo: (() -> Unit)? = null) : UiEvent
    data class Failure(val title: String, val message: String, val output: String?) : UiEvent
    data class Launch(val intent: Intent) : UiEvent
}

class AppListViewModel(private val c: AppContainer) : ViewModel() {
    private val query = MutableStateFlow(Query())
    val currentQuery: StateFlow<Query> = query.asStateFlow()

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _bulk = MutableStateFlow<BulkProgress?>(null)
    val bulk: StateFlow<BulkProgress?> = _bulk.asStateFlow()

    private val _bulkResult = MutableStateFlow<BulkResult?>(null)
    val bulkResult: StateFlow<BulkResult?> = _bulkResult.asStateFlow()

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()

    val settings = c.settings.settings
    val backend = c.backend.active
    val loading = c.packages.loading

    /** Every app installed for this user, with safety and block state, before filtering. */
    private val allRows: StateFlow<List<AppRow>> =
        combine(c.packages.apps, c.settings.settings) { apps, prefs ->
            c.safety.refreshRuntimeRoles()
            apps.filter { it.isInstalledForUser }
                .map { AppRow(it, c.safety.safetyOf(it), it.packageName in prefs.blockedPackages) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val rows: StateFlow<List<AppRow>> = combine(allRows, query) { all, q ->
        val needle = q.text.trim().lowercase()
        all.asSequence()
            .filter {
                when (q.filter) {
                    AppFilter.ALL -> true
                    AppFilter.DISABLED -> !it.app.isEnabled
                }
            }
            .filter { needle.isEmpty() || it.app.label.lowercase().contains(needle) || it.app.packageName.contains(needle) }
            .sortedByDescending { it.app.sizeBytes }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun rowFor(pkg: String): AppRow? = allRows.value.firstOrNull { it.app.packageName == pkg }

    fun setSearch(text: String) = query.update { it.copy(text = text) }
    fun setFilter(filter: AppFilter) = query.update { it.copy(filter = filter) }

    fun refresh() {
        c.backend.refresh()
        c.packages.refresh()
    }

    fun toggleSelection(pkg: String) = _selection.update { if (pkg in it) it - pkg else it + pkg }
    fun selectAllVisible() = _selection.update { it + rows.value.map { r -> r.app.packageName } }
    fun clearSelection() { _selection.value = emptySet() }
    fun dismissBulkResult() { _bulkResult.value = null }

    fun uninstall(app: AppInfo, keepData: Boolean): Unit = run(app) {
        val outcome = c.actions.uninstall(app, keepData)
        report(outcome, "Couldn't remove ${app.label}", undo = if (app.isSystem) ({ restore(app) }) else null)
    }

    fun restore(app: AppInfo): Unit = run(app) {
        report(c.actions.restore(app), "Couldn't restore ${app.label}")
    }

    fun setEnabled(app: AppInfo, enabled: Boolean): Unit = run(app) {
        report(c.actions.setEnabled(app, enabled), "Couldn't change ${app.label}", undo = { setEnabled(app, !enabled) })
    }

    fun uninstallUpdates(app: AppInfo): Unit = run(app) {
        report(c.actions.uninstallUpdates(app), "Couldn't uninstall updates for ${app.label}")
    }

    fun setNotificationsBlocked(app: AppInfo, blocked: Boolean): Unit = run(app) {
        report(
            c.actions.setNotificationsBlocked(app, blocked),
            "Couldn't change notifications for ${app.label}",
            undo = { setNotificationsBlocked(app, !blocked) },
        )
    }

    /** Selected apps that bulk uninstall will skip because they're critical (unless Expert mode). */
    fun criticalInSelection(): List<AppRow> =
        if (settings.value.expertMode) emptyList()
        else selectedRows().filter { it.safety.rating == SafetyRating.CRITICAL }

    fun selectedRows(): List<AppRow> = _selection.value.mapNotNull { rowFor(it) }

    fun bulkUninstall() = bulk("Removing") { c.actions.uninstall(it, settings.value.keepData) }

    fun bulkBlockNotifications() = bulk("Blocking notifications") { c.actions.setNotificationsBlocked(it, true) }

    private fun bulk(verb: String, action: suspend (AppInfo) -> ActionOutcome) {
        val targets = selectedRows().map { it.app }
        clearSelection()
        viewModelScope.launch {
            val ok = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, String>>()
            targets.forEachIndexed { i, app ->
                _bulk.value = BulkProgress(i, targets.size, "$verb ${app.label}")
                val outcome = action(app)
                // Limited-mode outcomes need a system screen per app, which doesn't work in bulk.
                if (outcome.success && outcome.fallbackIntent == null) ok += app.label
                else failed += app.label to if (outcome.fallbackIntent != null) "Needs Shizuku or root for bulk actions" else outcome.message
            }
            _bulk.value = null
            _bulkResult.value = BulkResult(ok, failed)
        }
    }

    private fun run(app: AppInfo, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.update { it + app.packageName }
            try {
                block()
            } finally {
                _busy.update { it - app.packageName }
            }
        }
    }

    private suspend fun report(outcome: ActionOutcome, failureTitle: String, undo: (() -> Unit)? = null) {
        when {
            outcome.fallbackIntent != null -> {
                events.send(UiEvent.Launch(outcome.fallbackIntent))
                events.send(UiEvent.Message(outcome.message))
            }
            outcome.success -> events.send(UiEvent.Message(outcome.message, undo))
            else -> events.send(UiEvent.Failure(failureTitle, outcome.message, outcome.output))
        }
    }
}
