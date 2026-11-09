package com.defaultuninstaller.domain

import android.content.Context
import android.net.Uri
import com.defaultuninstaller.data.packages.PackageRepository
import com.defaultuninstaller.data.safety.SafetyRating
import com.defaultuninstaller.data.safety.SafetyRepository
import com.defaultuninstaller.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Profile(
    val version: Int = 1,
    val removed: List<String> = emptyList(),
    val disabled: List<String> = emptyList(),
    val notificationsBlocked: List<String> = emptyList(),
)

data class ProfilePlan(
    val profile: Profile,
    val toRemove: List<String>,
    val toDisable: List<String>,
    val toBlock: List<String>,
    val skippedCritical: List<String>,
    val notOnDevice: List<String>,
) {
    val total get() = toRemove.size + toDisable.size + toBlock.size
}

class ProfileManager(
    private val context: Context,
    private val packages: PackageRepository,
    private val safety: SafetyRepository,
    private val settings: SettingsRepository,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun current(): Profile {
        val apps = packages.apps.value
        return Profile(
            removed = apps.filter { it.isSystem && !it.isInstalledForUser }.map { it.packageName }.sorted(),
            disabled = apps.filter { it.isInstalledForUser && !it.isEnabled }.map { it.packageName }.sorted(),
            notificationsBlocked = settings.settings.value.blockedPackages.sorted(),
        )
    }

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use {
            it.write(json.encodeToString(Profile.serializer(), current()))
        }
    }

    suspend fun plan(uri: Uri): ProfilePlan = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        val profile = json.decodeFromString(Profile.serializer(), text)
        val apps = packages.apps.value.associateBy { it.packageName }
        val critical = mutableListOf<String>()
        val missing = mutableListOf<String>()

        fun eligible(list: List<String>, needsAction: (com.defaultuninstaller.data.packages.AppInfo) -> Boolean, checkSafety: Boolean) =
            list.filter { AppActions.PACKAGE_REGEX.matches(it) }.mapNotNull { pkg ->
                val app = apps[pkg]
                when {
                    app == null -> { missing += pkg; null }
                    checkSafety && safety.safetyOf(app).rating == SafetyRating.CRITICAL -> { critical += pkg; null }
                    needsAction(app) -> pkg
                    else -> null
                }
            }

        ProfilePlan(
            profile = profile,
            toRemove = eligible(profile.removed, { it.isInstalledForUser }, checkSafety = true),
            toDisable = eligible(profile.disabled, { it.isInstalledForUser && it.isEnabled }, checkSafety = true),
            toBlock = eligible(profile.notificationsBlocked, { it.packageName !in settings.settings.value.blockedPackages }, checkSafety = false),
            skippedCritical = critical.distinct(),
            notOnDevice = missing.distinct(),
        )
    }
}
