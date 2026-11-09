package com.defaultuninstaller.data.safety

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import com.defaultuninstaller.data.packages.AppInfo
import com.defaultuninstaller.privileged.ShizukuExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class SafetyRating { SAFE, CAUTION, CRITICAL, UNKNOWN }

data class Safety(val rating: SafetyRating, val description: String)

@Serializable
private data class PackageEntry(val `package`: String, val rating: String, val description: String)

class SafetyRepository(private val context: Context) {
    private val database: Map<String, Safety> by lazy {
        val text = context.assets.open("packages.json").bufferedReader().use { it.readText() }
        Json.decodeFromString<List<PackageEntry>>(text).associate {
            it.`package` to Safety(SafetyRating.valueOf(it.rating.uppercase()), it.description)
        }
    }

    @Volatile private var roleHolders: Map<String, String> = emptyMap()

    /** Packages that are critical right now because of a role they hold on this device. */
    fun refreshRuntimeRoles() {
        val holders = mutableMapOf<String, String>()
        holders[context.packageName] = "Uninstall Defaults itself."
        holders[ShizukuExecutor.MANAGER_PACKAGE] = "Shizuku, which gives this app its uninstall permission."
        val pm = context.packageManager

        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != "android" }
            ?.let { holders[it] = "Your current home screen (launcher). Switch launchers before removing it." }

        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.let { holders[it] = "Your current keyboard. Switch keyboards before removing it." }

        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
            ?.let { holders[it] = "Your default SMS app. Choose another SMS app first." }

        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
            ?.let { holders[it] = "Your default phone app. Choose another dialer first." }

        context.getSystemService(DevicePolicyManager::class.java)?.activeAdmins.orEmpty().forEach {
            holders[it.packageName] = "An active device administrator. Deactivate it in Settings first."
        }

        roleHolders = holders
    }

    fun safetyOf(app: AppInfo): Safety {
        roleHolders[app.packageName]?.let { return Safety(SafetyRating.CRITICAL, it) }
        database[app.packageName]?.let { return it }
        return when {
            !app.isSystem -> Safety(SafetyRating.SAFE, "An app you installed.")
            app.packageName.startsWith("com.android.providers.") ->
                Safety(SafetyRating.CRITICAL, "A system data provider other apps depend on.")
            app.apkPath.contains("/overlay/") || app.packageName.contains(".overlay") ->
                Safety(SafetyRating.CAUTION, "A resource overlay; removing it can change or break system UI.")
            else -> Safety(SafetyRating.UNKNOWN, "A system app we have no information about.")
        }
    }
}
