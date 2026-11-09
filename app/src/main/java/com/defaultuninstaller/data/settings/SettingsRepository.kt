package com.defaultuninstaller.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class BackendPreference { AUTO, ADB, SHIZUKU, ROOT, LIMITED }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val backend: BackendPreference = BackendPreference.AUTO,
    val rootGranted: Boolean = false,
    val keepData: Boolean = true,
    val allUsers: Boolean = false,
    val expertMode: Boolean = false,
    val showPackageNames: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val onboardingDone: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object Keys {
        val backend = stringPreferencesKey("backend")
        val rootGranted = booleanPreferencesKey("root_granted")
        val keepData = booleanPreferencesKey("keep_data")
        val allUsers = booleanPreferencesKey("all_users")
        val expertMode = booleanPreferencesKey("expert_mode")
        val showPackageNames = booleanPreferencesKey("show_package_names")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val blocked = stringSetPreferencesKey("blocked_packages")
    }

    /** Null until DataStore has loaded once, so the UI can avoid flashing onboarding. */
    val loaded: StateFlow<Settings?> = context.dataStore.data.map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val settings: StateFlow<Settings> = context.dataStore.data.map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, Settings())

    private fun Preferences.toSettings() = Settings(
        backend = this[Keys.backend]?.let { runCatching { BackendPreference.valueOf(it) }.getOrNull() } ?: BackendPreference.AUTO,
        rootGranted = this[Keys.rootGranted] ?: false,
        keepData = this[Keys.keepData] ?: true,
        allUsers = this[Keys.allUsers] ?: false,
        expertMode = this[Keys.expertMode] ?: false,
        showPackageNames = this[Keys.showPackageNames] ?: true,
        theme = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        dynamicColor = this[Keys.dynamicColor] ?: true,
        onboardingDone = this[Keys.onboardingDone] ?: false,
        blockedPackages = this[Keys.blocked] ?: emptySet(),
    )

    suspend fun setBackend(value: BackendPreference) = context.dataStore.edit { it[Keys.backend] = value.name }
    suspend fun setRootGranted(value: Boolean) = context.dataStore.edit { it[Keys.rootGranted] = value }
    suspend fun setKeepData(value: Boolean) = context.dataStore.edit { it[Keys.keepData] = value }
    suspend fun setAllUsers(value: Boolean) = context.dataStore.edit { it[Keys.allUsers] = value }
    suspend fun setExpertMode(value: Boolean) = context.dataStore.edit { it[Keys.expertMode] = value }
    suspend fun setShowPackageNames(value: Boolean) = context.dataStore.edit { it[Keys.showPackageNames] = value }
    suspend fun setTheme(value: ThemeMode) = context.dataStore.edit { it[Keys.theme] = value.name }
    suspend fun setDynamicColor(value: Boolean) = context.dataStore.edit { it[Keys.dynamicColor] = value }
    suspend fun setOnboardingDone(value: Boolean) = context.dataStore.edit { it[Keys.onboardingDone] = value }

    suspend fun setBlocked(pkg: String, blocked: Boolean) = context.dataStore.edit {
        val current = it[Keys.blocked] ?: emptySet()
        it[Keys.blocked] = if (blocked) current + pkg else current - pkg
    }
}
