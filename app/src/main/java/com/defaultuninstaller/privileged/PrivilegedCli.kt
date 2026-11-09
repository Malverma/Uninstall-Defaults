package com.defaultuninstaller.privileged

import kotlin.system.exitProcess

/**
 * Entry point for the root backend:
 * `CLASSPATH=<apk> app_process /system/bin com.defaultuninstaller.privileged.PrivilegedCli notif <pkg> <uid> <true|false>`
 * Lets root run binder calls (not just shell commands) as uid 0.
 */
object PrivilegedCli {
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
