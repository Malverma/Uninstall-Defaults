package com.defaultuninstaller

import android.app.Application
import android.content.Context
import com.defaultuninstaller.data.history.HistoryDatabase
import com.defaultuninstaller.data.packages.IconCache
import com.defaultuninstaller.data.packages.PackageRepository
import com.defaultuninstaller.data.safety.SafetyRepository
import com.defaultuninstaller.data.settings.SettingsRepository
import com.defaultuninstaller.domain.AppActions
import com.defaultuninstaller.domain.ProfileManager
import com.defaultuninstaller.privileged.AdbExecutor
import com.defaultuninstaller.privileged.BackendManager
import com.defaultuninstaller.privileged.RootExecutor
import com.defaultuninstaller.privileged.ShizukuExecutor
import com.defaultuninstaller.service.AdbPairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency graph; small enough that a DI framework would add more than it saves. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(context, scope)
    val packages = PackageRepository(context, scope)
    val icons = IconCache(context)
    val safety = SafetyRepository(context).also { it.refreshRuntimeRoles() }
    val adb = AdbExecutor(context, scope)
    val shizuku = ShizukuExecutor(context)
    val root = RootExecutor(context)
    val backend = BackendManager(adb, shizuku, root, settings, scope)
    val pairing = AdbPairing(context, adb, scope)
    val history = HistoryDatabase.create(context).historyDao()
    val actions = AppActions(context, backend, packages, safety, settings, history)
    val profiles = ProfileManager(context, packages, safety, settings)
}
