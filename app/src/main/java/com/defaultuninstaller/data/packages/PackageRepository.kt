package com.defaultuninstaller.data.packages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PackageRepository(private val context: Context, private val scope: CoroutineScope) {
    private val pm = context.packageManager

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    /** Every package known to the device for this user, including uninstalled-for-user system apps. */
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val currentUserId: Int get() = Process.myUid() / PER_USER_RANGE

    private var refreshJob: Job? = null

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = refresh()
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            _loading.value = true
            _apps.value = withContext(Dispatchers.IO) { load() }
            _loading.value = false
        }
    }

    suspend fun refreshNow() {
        refreshJob?.cancel()
        _apps.value = withContext(Dispatchers.IO) { load() }
        _loading.value = false
    }

    fun find(pkg: String): AppInfo? = _apps.value.firstOrNull { it.packageName == pkg }

    private fun load(): List<AppInfo> {
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }
        return packages.mapNotNull { info -> info.applicationInfo?.let { toAppInfo(info, it) } }
            .sortedBy { it.label.lowercase() }
    }

    private fun toAppInfo(info: PackageInfo, ai: ApplicationInfo): AppInfo {
        val enabledSetting = try {
            pm.getApplicationEnabledSetting(info.packageName)
        } catch (e: IllegalArgumentException) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        val enabled = when (enabledSetting) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> ai.enabled
        }
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(info.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(info.packageName)
        } catch (e: Exception) {
            null
        }
        return AppInfo(
            packageName = info.packageName,
            label = ai.loadLabel(pm).toString().ifBlank { info.packageName },
            versionName = info.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong(),
            uid = ai.uid,
            isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            isUpdatedSystem = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            isEnabled = enabled,
            isInstalledForUser = ai.flags and ApplicationInfo.FLAG_INSTALLED != 0,
            apkPath = ai.sourceDir.orEmpty(),
            sizeBytes = (listOfNotNull(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).sumOf { File(it).length() },
            installer = installer,
            firstInstallTime = info.firstInstallTime,
            lastUpdateTime = info.lastUpdateTime,
        )
    }

    companion object {
        private const val PER_USER_RANGE = 100_000
    }
}
