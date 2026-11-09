package com.defaultuninstaller.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.defaultuninstaller.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

class ShizukuExecutor(private val context: Context) : PrivilegedExecutor {
    override val kind = BackendKind.SHIZUKU

    private val _status = MutableStateFlow<BackendStatus>(BackendStatus.Unknown)
    val status: StateFlow<BackendStatus> = _status.asStateFlow()

    private val bindLock = Mutex()
    @Volatile private var service: IPrivilegedService? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("privileged")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    init {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    fun refresh() {
        _status.value = try {
            when {
                !Shizuku.pingBinder() -> if (isManagerInstalled()) BackendStatus.NotRunning else BackendStatus.NotInstalled
                Shizuku.isPreV11() -> BackendStatus.NotRunning
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> BackendStatus.NeedsPermission
                else -> BackendStatus.Ready(if (Shizuku.getUid() == 0) "root" else "adb")
            }
        } catch (t: Throwable) {
            BackendStatus.NotRunning
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
    }

    fun isManagerInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private suspend fun service(): IPrivilegedService = bindLock.withLock {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val deferred = CompletableDeferred<IPrivilegedService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    deferred.complete(IPrivilegedService.Stub.asInterface(binder))
                } else {
                    deferred.completeExceptionally(IllegalStateException("Shizuku returned a dead binder"))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        withContext(Dispatchers.Main) { Shizuku.bindUserService(userServiceArgs, connection) }
        withTimeout(15_000) { deferred.await() }.also { service = it }
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            ShellResult.fromBundle(service().exec(command))
        } catch (t: Throwable) {
            ShellResult.error("Shizuku: ${t.message ?: t}")
        }
    }

    override suspend fun setNotificationsEnabled(pkg: String, uid: Int, enabled: Boolean): ShellResult =
        withContext(Dispatchers.IO) {
            try {
                ShellResult.fromBundle(service().setNotificationsEnabled(pkg, uid, enabled))
            } catch (t: Throwable) {
                ShellResult.error("Shizuku: ${t.message ?: t}")
            }
        }

    companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4242
    }
}
