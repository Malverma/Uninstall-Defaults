package com.defaultuninstaller.privileged

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs commands through `su` (Magisk, KernelSU, APatch). */
class RootExecutor(private val context: Context) : PrivilegedExecutor {
    override val kind = BackendKind.ROOT

    private val _status = MutableStateFlow<BackendStatus>(BackendStatus.Unknown)
    val status: StateFlow<BackendStatus> = _status.asStateFlow()

    fun suBinaryPresent(): Boolean =
        System.getenv("PATH").orEmpty().split(':').plus(EXTRA_SU_DIRS).any { File(it, "su").exists() }

    /** Asks `su` for access; may show the root manager's grant prompt. */
    suspend fun checkAccess(): BackendStatus = withContext(Dispatchers.IO) {
        val status = try {
            val result = runSu("id", timeoutSeconds = 30)
            if (result.exitCode == 0 && result.stdout.contains("uid=0")) BackendStatus.Ready("root")
            else BackendStatus.NeedsPermission
        } catch (e: IOException) {
            BackendStatus.NotInstalled
        }
        _status.value = status
        status
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            runSu(command)
        } catch (e: IOException) {
            ShellResult.error("su not available: ${e.message}")
        }
    }

    override suspend fun setNotificationsEnabled(pkg: String, uid: Int, enabled: Boolean): ShellResult =
        exec(PrivilegedCli.notifCommand(context.applicationInfo.sourceDir, pkg, uid, enabled))

    private fun runSu(command: String, timeoutSeconds: Long = 120): ShellResult {
        val process = ProcessBuilder("su").start()
        process.outputStream.bufferedWriter().use {
            it.write(command)
            it.write("\nexit\n")
        }
        var stdout = ""
        var stderr = ""
        val outReader = thread { stdout = process.inputStream.bufferedReader().readText() }
        val errReader = thread { stderr = process.errorStream.bufferedReader().readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            return ShellResult.error("su timed out")
        }
        outReader.join()
        errReader.join()
        return ShellResult(process.exitValue(), stdout, stderr)
    }

    companion object {
        private val EXTRA_SU_DIRS = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/debug_ramdisk", "/data/adb/ksu/bin")
    }
}
