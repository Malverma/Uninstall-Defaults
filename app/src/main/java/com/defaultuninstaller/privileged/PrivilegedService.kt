package com.defaultuninstaller.privileged

import android.os.Bundle
import kotlin.system.exitProcess

/** Shizuku user service. Instantiated by Shizuku in its own process with shell or root uid. */
class PrivilegedService : IPrivilegedService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): Bundle = PrivilegedOps.exec(command).toBundle()

    override fun setNotificationsEnabled(packageName: String, uid: Int, enabled: Boolean): Bundle =
        PrivilegedOps.setNotificationsEnabled(packageName, uid, enabled).toBundle()
}
