package com.defaultuninstaller.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.defaultuninstaller.data.history.ActionType
import com.defaultuninstaller.data.history.HistoryDao
import com.defaultuninstaller.data.history.HistoryEntry
import com.defaultuninstaller.data.packages.AppInfo
import com.defaultuninstaller.data.packages.PackageRepository
import com.defaultuninstaller.data.safety.SafetyRating
import com.defaultuninstaller.data.safety.SafetyRepository
import com.defaultuninstaller.data.settings.SettingsRepository
import com.defaultuninstaller.privileged.BackendManager
import com.defaultuninstaller.privileged.PrivilegedExecutor
import com.defaultuninstaller.privileged.ShellResult
import com.defaultuninstaller.service.BlockedNotificationListener

data class ActionOutcome(
    val success: Boolean,
    val message: String,
    val output: String? = null,
    /** Set when the action must be finished by the user in a system screen (limited mode). */
    val fallbackIntent: Intent? = null,
)

class AppActions(
    private val context: Context,
    private val backend: BackendManager,
    private val packages: PackageRepository,
    private val safety: SafetyRepository,
    private val settings: SettingsRepository,
    private val history: HistoryDao,
) {
    private val executor: PrivilegedExecutor? get() = backend.active.value.executor

    suspend fun uninstall(app: AppInfo, keepData: Boolean): ActionOutcome {
        guard(app)?.let { return it }
        val exec = executor
            ?: return if (!app.isSystem) {
                ActionOutcome(
                    success = true,
                    message = "Confirm in the system dialog",
                    fallbackIntent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")),
                )
            } else {
                ActionOutcome(false, "Removing preloaded apps needs Shizuku or root. Open setup from the status chip.")
            }

        val result: ShellResult
        val type: ActionType
        if (app.isSystem) {
            type = ActionType.UNINSTALL_SYSTEM
            val flag = if (keepData) "-k " else ""
            result = forEachUser(exec) { user -> "pm uninstall $flag--user $user ${app.packageName}" }
        } else {
            type = ActionType.UNINSTALL
            result = exec.exec("pm uninstall ${app.packageName}")
        }
        return finish(app, type, result, "${app.label} removed")
    }

    suspend fun restore(app: AppInfo): ActionOutcome {
        validate(app.packageName)?.let { return it }
        val exec = executor ?: return needsBackend()
        val result = forEachUser(exec) { user -> "pm install-existing --user $user ${app.packageName}" }
        return finish(app, ActionType.RESTORE, result, "${app.label} restored")
    }

    suspend fun setEnabled(app: AppInfo, enabled: Boolean): ActionOutcome {
        if (!enabled) guard(app)?.let { return it } else validate(app.packageName)?.let { return it }
        val exec = executor ?: return ActionOutcome(
            success = true,
            message = "Disable it from the app's system settings page",
            fallbackIntent = appDetailsIntent(app.packageName),
        )
        val result = forEachUser(exec) { user ->
            if (enabled) "pm enable --user $user ${app.packageName}" else "pm disable-user --user $user ${app.packageName}"
        }
        return finish(
            app,
            if (enabled) ActionType.ENABLE else ActionType.DISABLE,
            result,
            if (enabled) "${app.label} enabled" else "${app.label} disabled",
        )
    }

    suspend fun uninstallUpdates(app: AppInfo): ActionOutcome {
        validate(app.packageName)?.let { return it }
        val exec = executor ?: return ActionOutcome(
            success = true,
            message = "Use \"Uninstall updates\" in the app's system settings page",
            fallbackIntent = appDetailsIntent(app.packageName),
        )
        // On an updated system app, a full `pm uninstall` removes the update and keeps the factory copy.
        val result = exec.exec("pm uninstall ${app.packageName}")
        return finish(app, ActionType.UNINSTALL_UPDATES, result, "${app.label} reverted to factory version")
    }

    suspend fun setNotificationsBlocked(app: AppInfo, blocked: Boolean): ActionOutcome {
        validate(app.packageName)?.let { return it }
        val exec = executor
        if (exec == null) {
            // The listener still enforces the block by dismissing anything the app posts.
            settings.setBlocked(app.packageName, blocked)
            if (blocked) BlockedNotificationListener.clearFor(app.packageName)
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            return ActionOutcome(
                success = true,
                message = if (blocked) "Turn off \"All notifications\" to finish blocking ${app.label}"
                else "Turn notifications back on for ${app.label}",
                fallbackIntent = intent,
            )
        }
        val result = exec.setNotificationsEnabled(app.packageName, app.uid, !blocked)
        if (result.isSuccess) {
            settings.setBlocked(app.packageName, blocked)
            if (blocked) BlockedNotificationListener.clearFor(app.packageName)
        }
        val type = if (blocked) ActionType.BLOCK_NOTIFICATIONS else ActionType.UNBLOCK_NOTIFICATIONS
        return finish(app, type, result, if (blocked) "Notifications blocked for ${app.label}" else "Notifications allowed for ${app.label}")
    }

    /** Reverses a history entry, if that action has an inverse. */
    suspend fun undo(entry: HistoryEntry): ActionOutcome {
        packages.refreshNow()
        val app = packages.find(entry.packageName)
            ?: return ActionOutcome(false, "${entry.appLabel} is no longer on this device")
        return when (entry.action) {
            ActionType.UNINSTALL_SYSTEM -> restore(app)
            ActionType.RESTORE -> uninstall(app, settings.settings.value.keepData)
            ActionType.DISABLE -> setEnabled(app, true)
            ActionType.ENABLE -> setEnabled(app, false)
            ActionType.BLOCK_NOTIFICATIONS -> setNotificationsBlocked(app, false)
            ActionType.UNBLOCK_NOTIFICATIONS -> setNotificationsBlocked(app, true)
            ActionType.UNINSTALL, ActionType.UNINSTALL_UPDATES -> ActionOutcome(false, "This action can't be undone")
        }
    }

    private fun guard(app: AppInfo): ActionOutcome? {
        validate(app.packageName)?.let { return it }
        if (app.packageName == context.packageName) return ActionOutcome(false, "Uninstall Defaults can't remove itself")
        if (safety.safetyOf(app).rating == SafetyRating.CRITICAL && !settings.settings.value.expertMode) {
            return ActionOutcome(false, "${app.label} is critical to the system. Turn on Expert mode in Settings to override.")
        }
        return null
    }

    /** Package names go into shell commands, so anything outside the legal charset is rejected. */
    private fun validate(pkg: String): ActionOutcome? =
        if (PACKAGE_REGEX.matches(pkg)) null else ActionOutcome(false, "Invalid package name: $pkg")

    private suspend fun forEachUser(exec: PrivilegedExecutor, command: (Int) -> String): ShellResult {
        val users = if (settings.settings.value.allUsers) listUsers(exec) else listOf(packages.currentUserId)
        val results = users.map { exec.exec(command(it)) }
        // Succeeds if the current user's operation worked; other users are best effort.
        val primary = results[users.indexOf(packages.currentUserId).coerceAtLeast(0)]
        return ShellResult(
            exitCode = if (primary.isSuccess) 0 else primary.exitCode.takeIf { it != 0 } ?: 1,
            stdout = results.zip(users).joinToString("\n") { (r, u) -> if (users.size > 1) "user $u: ${r.output}" else r.output },
            stderr = "",
        )
    }

    private suspend fun listUsers(exec: PrivilegedExecutor): List<Int> {
        val ids = USER_REGEX.findAll(exec.exec("pm list users").stdout).map { it.groupValues[1].toInt() }.toList()
        return ids.ifEmpty { listOf(packages.currentUserId) }
    }

    private suspend fun finish(app: AppInfo, type: ActionType, result: ShellResult, successMessage: String): ActionOutcome {
        history.insert(
            HistoryEntry(
                packageName = app.packageName,
                appLabel = app.label,
                action = type,
                success = result.isSuccess,
                output = result.output.ifEmpty { null },
            ),
        )
        packages.refreshNow()
        return if (result.isSuccess) {
            ActionOutcome(true, successMessage, result.output)
        } else {
            ActionOutcome(false, explainFailure(result.output), result.output)
        }
    }

    private fun needsBackend() = ActionOutcome(false, "This needs Shizuku or root. Open setup from the status chip.")

    private fun appDetailsIntent(pkg: String) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

    companion object {
        val PACKAGE_REGEX = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$")
        private val USER_REGEX = Regex("UserInfo\\{(\\d+):")

        fun explainFailure(output: String): String = when {
            output.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER") ->
                "This app is protected by a device administrator and can't be removed. You can still block its notifications or disable it."
            output.contains("DELETE_FAILED_USER_RESTRICTED") ->
                "Your device manufacturer or administrator restricts removing this app. Try disabling it or blocking its notifications."
            output.contains("DELETE_FAILED_INTERNAL_ERROR") ->
                "Android refused to remove this app. It may already be removed, or be required by the system."
            output.contains("not installed for") || output.contains("Unknown package") ->
                "This app isn't installed for this user any more."
            output.contains("SecurityException") || output.contains("Permission Denial") || output.contains("not allowed") ->
                "Permission denied. On Xiaomi/Oppo/Vivo phones turn on \"USB debugging (Security settings)\" in Developer options and restart Shizuku, or use root."
            output.contains("Shizuku") -> "Shizuku stopped responding. Reopen Shizuku and start it again."
            output.contains("su ") -> "Root access was denied or is unavailable."
            output.isBlank() -> "The command failed with no output."
            else -> "The command failed."
        }
    }
}
