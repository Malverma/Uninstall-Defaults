package com.defaultuninstaller.privileged

import kotlin.system.exitProcess

/**
 * Entry point for the root and built-in ADB backends:
 * `CLASSPATH=<apk> app_process /system/bin com.defaultuninstaller.privileged.PrivilegedCli notif <pkg> <uid> <true|false>`
 * Lets backends that only have a shell run binder calls with that shell's uid (0 or 2000).
 */
object PrivilegedCli {
    fun notifCommand(apk: String, pkg: String, uid: Int, enabled: Boolean) =
        "CLASSPATH='$apk' app_process /system/bin ${PrivilegedCli::class.java.name} notif $pkg $uid $enabled"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 4 && args[0] == "notif") {
            val result = PrivilegedOps.setNotificationsEnabled(args[1], args[2].toInt(), args[3].toBoolean())
            println(result.stdout)
            exitProcess(result.exitCode)
        }
        System.err.println("usage: notif <package> <uid> <true|false>")
        exitProcess(2)
    }
}
