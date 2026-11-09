package com.defaultuninstaller.privileged

enum class BackendKind { SHIZUKU, ROOT }

sealed interface BackendStatus {
    data class Ready(val detail: String) : BackendStatus
    data object NeedsPermission : BackendStatus
    data object NotRunning : BackendStatus
    data object NotInstalled : BackendStatus
    data object Unknown : BackendStatus
}

/** Something that can run commands with shell (uid 2000) or root (uid 0) privileges. */
interface PrivilegedExecutor {
    val kind: BackendKind
    suspend fun exec(command: String): ShellResult
    suspend fun setNotificationsEnabled(pkg: String, uid: Int, enabled: Boolean): ShellResult
}
