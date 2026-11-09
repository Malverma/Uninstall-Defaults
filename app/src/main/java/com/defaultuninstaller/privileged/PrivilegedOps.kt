package com.defaultuninstaller.privileged

import android.os.Build
import android.os.IBinder
import kotlin.concurrent.thread

/**
 * Code that runs in an elevated process: the Shizuku user service (shell or root uid)
 * or `app_process` launched through `su` (root uid). Never called from the app process.
 */
object PrivilegedOps {

    fun exec(command: String): ShellResult = try {
        val process = ProcessBuilder("sh", "-c", command).start()
        var stderr = ""
        val errReader = thread { stderr = process.errorStream.bufferedReader().readText() }
        val stdout = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        errReader.join()
        ShellResult(exit, stdout, stderr)
    } catch (t: Throwable) {
        ShellResult.error(t.toString())
    }

    /**
     * Fully enables/disables an app's notifications using every mechanism the caller's uid
     * is allowed to use, and reports what worked.
     */
    fun setNotificationsEnabled(pkg: String, uid: Int, enabled: Boolean): ShellResult {
        val log = StringBuilder()
        var anySucceeded = false

        // 1. The same switch as Settings > Apps > Notifications > "All notifications".
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "notification") as IBinder
            val manager = Class.forName("android.app.INotificationManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
            manager.javaClass
                .getMethod(
                    "setNotificationsEnabledForPackage",
                    String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                )
                .invoke(manager, pkg, uid, enabled)
            anySucceeded = true
            log.appendLine("notification service: ok")
        } catch (t: Throwable) {
            log.appendLine("notification service: ${(t.cause ?: t).javaClass.simpleName}: ${(t.cause ?: t).message}")
        }

        // 2. Android 13+: the runtime permission, pinned so the app can't ask again.
        if (Build.VERSION.SDK_INT >= 33) {
            val perm = "android.permission.POST_NOTIFICATIONS"
            val result = if (enabled) {
                exec("pm clear-permission-flags $pkg $perm user-set user-fixed; pm grant $pkg $perm")
            } else {
                exec("pm revoke $pkg $perm && pm set-permission-flags $pkg $perm user-set user-fixed")
            }
            if (result.isSuccess) anySucceeded = true
            log.appendLine("POST_NOTIFICATIONS: " + (if (result.isSuccess) "ok" else result.output.ifEmpty { "failed" }))
        }

        // 3. The app-op that gates notification posting.
        val op = exec("appops set $pkg POST_NOTIFICATION ${if (enabled) "allow" else "ignore"}")
        if (op.isSuccess) anySucceeded = true
        log.appendLine("appops: " + (if (op.isSuccess) "ok" else op.output.ifEmpty { "failed" }))

        return ShellResult(if (anySucceeded) 0 else 1, log.toString().trim(), "")
    }
}
