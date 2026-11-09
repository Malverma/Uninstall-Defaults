package com.defaultuninstaller.data.packages

data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val uid: Int,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val isEnabled: Boolean,
    /** False when a preloaded app has been uninstalled for the current user. */
    val isInstalledForUser: Boolean,
    val apkPath: String,
    val installer: String?,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
)
