package com.defaultuninstaller.privileged

import com.defaultuninstaller.data.settings.BackendPreference
import com.defaultuninstaller.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ActiveBackend(val executor: PrivilegedExecutor?, val label: String) {
    val isPrivileged get() = executor != null
}

/** Picks the privileged executor to use from the user's preference and what's available. */
class BackendManager(
    val shizuku: ShizukuExecutor,
    val root: RootExecutor,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val active: StateFlow<ActiveBackend> =
        combine(shizuku.status, root.status, settings.settings) { shizukuStatus, rootStatus, prefs ->
            val shizukuReady = shizukuStatus as? BackendStatus.Ready
            val rootReady = rootStatus as? BackendStatus.Ready
            when (prefs.backend) {
                BackendPreference.AUTO -> when {
                    shizukuReady != null -> ActiveBackend(shizuku, "Shizuku (${shizukuReady.detail})")
                    rootReady != null -> ActiveBackend(root, "Root")
                    else -> LIMITED
                }
                BackendPreference.SHIZUKU -> shizukuReady?.let { ActiveBackend(shizuku, "Shizuku (${it.detail})") } ?: LIMITED
                BackendPreference.ROOT -> rootReady?.let { ActiveBackend(root, "Root") } ?: LIMITED
                BackendPreference.LIMITED -> LIMITED
            }
        }.stateIn(scope, SharingStarted.Eagerly, LIMITED)

    init {
        // Re-check root silently only if the user granted it before, so first launch never
        // pops a surprise root prompt.
        scope.launch {
            val prefs = settings.settings.first()
            if (prefs.rootGranted && prefs.backend != BackendPreference.LIMITED && prefs.backend != BackendPreference.SHIZUKU) {
                root.checkAccess()
            }
        }
    }

    suspend fun requestRoot(): BackendStatus {
        val status = root.checkAccess()
        settings.setRootGranted(status is BackendStatus.Ready)
        return status
    }

    fun refresh() {
        shizuku.refresh()
        scope.launch { if (settings.settings.first().rootGranted) root.checkAccess() }
    }

    companion object {
        val LIMITED = ActiveBackend(null, "Limited")
    }
}
