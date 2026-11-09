# Uninstall Defaults — Specification

## 1. Overview

**Uninstall Defaults** is an Android application that lists every app installed on the device and lets the user remove the ones they don't want. Its main purpose is to remove **preloaded / system apps** (e.g. YouTube, Google TV, Facebook stubs, carrier bloatware) that the launcher does not allow you to uninstall.

For apps the user wants to keep (or that can't be safely removed), the app also offers **complete notification removal**: the app is fully blocked from posting notifications, and any notifications it has already posted are cleared.

### 1.1 Goals

- Show every installed package (user and system) in one searchable list.
- One-tap **Uninstall** for any app, including preloaded system apps.
- One-tap **Block notifications** that silences an app entirely.
- Make every removal **reversible**. Preloaded apps can be restored.
- Protect the user from bricking the device by removing critical system components.

### 1.2 Non-Goals

- Permanently deleting APKs from the read-only `/system` partition. The app removes a package *for the current user*, which is what the platform allows without modifying the system image.
- Ad blocking, firewalling, or permission management beyond notifications.
- Cloud accounts, analytics, or any network access.

---

## 2. Platform Constraints (Why Elevated Access Is Required)

A normal Android app **cannot** uninstall system apps or change another app's notification settings. The standard `ACTION_DELETE` intent only works for user-installed apps. To do what launchers won't, the app needs shell-level (ADB) privileges, which it gets through one of these backends:

| Backend | How it works | Root needed | Priority |
|---|---|---|---|
| **Built-in Wireless debugging** (primary) | The app pairs once with the phone's own `adbd` over Wireless Debugging (Android 11+, pairing code typed into a notification) using an embedded ADB client ([libadb-android](https://github.com/MuntashirAkon/libadb-android)), then runs commands as `shell`. After the first connection it grants itself `WRITE_SECURE_SETTINGS` so it can switch Wireless Debugging back on after reboots. | No | **Required** |
| **Shizuku** (alternative) | The [Shizuku](https://shizuku.rikka.app/) service runs with ADB (`shell`) privileges. Uninstall Defaults binds to it and runs privileged commands / binder calls. Useful on Android 8–10, where it can be started from a computer. | No | Optional |
| **Root** (optional) | Runs the same commands through `su` (Magisk / KernelSU). | Yes | Nice-to-have |
| **Standard intent** (fallback) | `Intent.ACTION_DELETE` for user-installed apps only. Used when no privileged backend is available. | No | Required |

All privileged operations go through a single `PrivilegedExecutor` interface so backends are interchangeable.

### 2.1 Underlying operations

| Action | Command (shell-equivalent) |
|---|---|
| Uninstall preloaded app (current user) | `pm uninstall -k --user 0 <package>` |
| Uninstall user app | `pm uninstall <package>` (or `ACTION_DELETE` fallback) |
| Restore preloaded app | `cmd package install-existing --user 0 <package>` |
| Disable instead of uninstall | `pm disable-user --user 0 <package>` |
| Re-enable | `pm enable --user 0 <package>` |
| Block notifications | `INotificationManager.setNotificationsEnabledForPackage(pkg, uid, false)` via Shizuku binder; additionally on Android 13+: `pm revoke <package> android.permission.POST_NOTIFICATIONS` + set `user-fixed` flag |
| Unblock notifications | Reverse of the above |
| Clear existing notifications | `NotificationListenerService.cancelNotification(key)` for all active notifications from the package |

`--user 0` targets the primary user. On multi-user / work-profile devices the app uses the current user's ID.

---

## 3. Target Platform & Tech Stack

| Item | Value |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 (dynamic color on Android 12+) |
| Architecture | MVVM + unidirectional data flow (ViewModel + `StateFlow`) |
| Min SDK | 26 (Android 8.0) |
| Target / Compile SDK | 36 |
| DI | Hilt |
| Persistence | Room (action history), DataStore (settings) |
| Async | Kotlin Coroutines + Flow |
| Privileged access | `dev.rikka.shizuku:api` + `dev.rikka.shizuku:provider`; `libsu` for root |
| Hidden API access | `org.lsposed.hiddenapibypass` (for binder calls through Shizuku) |
| Package ID | `com.defaultuninstaller` |

### 3.1 Permissions

| Permission | Reason |
|---|---|
| `QUERY_ALL_PACKAGES` | Enumerate all installed apps (Android 11+ package visibility). |
| `REQUEST_DELETE_PACKAGES` | Fallback uninstall via standard intent. |
| `moe.shizuku.manager.permission.API_V23` | Talk to the Shizuku service. |
| `BIND_NOTIFICATION_LISTENER_SERVICE` (service) | Clear notifications already posted by a blocked app. User grants this in system settings; optional. |
| `POST_NOTIFICATIONS` | **Not requested.** The app itself posts no notifications. |
| `INTERNET` | **Not requested.** The app works fully offline. |

### 3.2 Distribution

Because of `QUERY_ALL_PACKAGES` and its use of Shizuku/root, the app is intended for **GitHub Releases / F-Droid / sideloading**, not the Google Play Store.

---

## 4. Features

### 4.1 App List (Home Screen)

A scrollable list of every installed package for the current user.

**Each row shows:**
- App icon (loaded lazily and cached)
- App label (e.g. "YouTube")
- Package name (e.g. `com.google.android.youtube`), in smaller text
- Badges: `System`, `Updated system app`, `Disabled`, `Notifications blocked`, and a **safety badge** (see 4.5)
- **Uninstall** button
- **Notifications** toggle/button (bell icon; crossed-out bell when blocked)

**Tapping a row** opens the App Detail sheet (4.4).

**Top bar:**
- Search field that filters by label or package name, live as you type
- Filter chips: `All` · `System` · `User` · `Disabled` · `Notifications blocked`
- Sort menu: Name (A–Z), Package name, Install date, Size
- Overflow menu: Removed Apps, History, Settings, Refresh

**Default view:** `System` filter on, sorted by name, so preloaded apps (the main use case) are what the user sees first.

**Multi-select:** long-press enters selection mode; a bottom action bar offers **Uninstall selected** and **Block notifications for selected**.

**Data source:** `PackageManager.getInstalledPackages(MATCH_UNINSTALLED_PACKAGES or MATCH_DISABLED_COMPONENTS)`, refreshed on `ACTION_PACKAGE_ADDED/REMOVED/CHANGED` broadcasts and on pull-to-refresh.

### 4.2 Uninstall

**Flow:**
1. User taps **Uninstall**.
2. A confirmation dialog shows the app icon, name, package, and safety rating, plus text explaining what will happen:
   - *System app:* "This removes YouTube for your user. The app can be restored at any time from **Removed Apps**. A factory reset also restores it."
   - *User app:* "This permanently uninstalls the app and deletes its data."
3. For system apps, a checkbox **"Keep app data"** (default **on**, maps to `-k`).
4. On confirm, the app runs the command via the active backend and shows a progress indicator on the row.
5. On success: the row is removed from the list, and a snackbar appears: "YouTube removed · **Undo**". The action is saved to History.
6. On failure: an error dialog with the command output and a suggestion (e.g. "Shizuku is not running", "This package is protected by the device manufacturer").

**Disable instead:** the confirmation dialog has a secondary button, **Disable instead**, for users who'd rather freeze an app than remove it.

**Without a privileged backend:** user apps uninstall through the system dialog (`ACTION_DELETE`). For system apps, the Uninstall button shows a prompt to set up Shizuku (4.6).

### 4.3 Complete Notification Removal

Gives the user total silence from an app without uninstalling it. Also useful for apps the safety system marks as unsafe to remove.

**"Block notifications" does all of the following:**
1. Turns off the app's notifications at the system level (equivalent to toggling "All notifications" off in Settings → Apps → *App* → Notifications). This covers every notification channel, including ones created later.
2. On Android 13+, revokes `POST_NOTIFICATIONS` and marks it *user-fixed* so the app cannot ask for it again.
3. If the Notification Listener is enabled, immediately clears every notification the app has already posted.
4. Records the action in History.

**Unblock** reverses steps 1–2.

**Fallback (no privileged backend):** open the system notification settings page for the app (`Settings.ACTION_APP_NOTIFICATION_SETTINGS`) and tell the user which toggle to turn off.

**Ongoing enforcement:** the Notification Listener service (if enabled) watches for notifications from any blocked package and cancels them. This catches anything that gets past the system block (e.g. some foreground-service notifications, OEM quirks). Note: Android does not let any app cancel a foreground-service notification. The spec accepts this limitation and documents it in the UI.

### 4.4 App Detail Sheet

A bottom sheet shown when a row is tapped:
- Large icon, label, package name, version name/code
- Install source (preinstalled / Play Store / sideloaded / other)
- APK path (e.g. `/system/app/...`, `/product/app/...`), install and update dates, size
- Safety rating with explanation (4.5)
- Actions: **Uninstall**, **Disable / Enable**, **Block / Unblock notifications**, **Uninstall updates** (system apps with updates), **Open in system settings**, **Copy package name**

### 4.5 Safety System

Removing the wrong system package can cause boot loops or a broken phone. Every package gets a safety rating:

| Rating | Meaning | UI behavior |
|---|---|---|
| 🟢 **Safe** | Known bloatware, safe to remove (e.g. YouTube, YouTube Music, Google TV, Facebook stubs) | Normal confirmation |
| 🟡 **Caution** | Removing it may break related features (e.g. Google Play Services, Gboard, default SMS app) | Warning in confirmation describing what may break |
| 🔴 **Critical** | Required for the device to work (e.g. `android`, `com.android.systemui`, `com.android.settings`, `com.android.phone`, the current default launcher, Package Installer, Shizuku itself, Uninstall Defaults itself) | Uninstall **blocked** by default. Can be unlocked only by turning on "Expert mode" in Settings, followed by a type-to-confirm dialog. |
| ⚪ **Unknown** | Not in the database | Generic warning: "This is a system app we have no information about." |

- Ratings come from a **bundled JSON database** (`assets/packages.json`) that maps package names to rating, description, and known side effects. It can be seeded from community lists such as the Universal Android Debloater list (check license compatibility).
- Some packages are always rated **Critical** whatever the database says, based on runtime checks: the current default launcher, keyboard, and SMS/dialer roles, the device admin, and the app itself.

### 4.6 Privileged Access Setup (Onboarding)

On first launch:
1. **Welcome screen:** explains what the app does and why it needs elevated access.
2. **Choose a backend:**
   - **Wireless debugging (recommended):** one-time pairing, no other app.
     - Developer options off → explain the Build-number taps and open About phone.
     - Not paired → Start pairing: watches for the pairing service over mDNS, opens Developer options at Wireless debugging, and posts a notification with an inline reply for the 6-digit code (the pairing dialog closes if the user switches apps). The code can also be typed in-app in split screen.
     - Paired, not connected → reconnect, switching Wireless debugging on automatically when `WRITE_SECURE_SETTINGS` was granted.
   - **Shizuku (alternative):** only shown if Shizuku is installed, or on Android 10 and older.
   - **Root:** request `su` access. If no `su` binary exists the card says the phone isn't rooted instead of offering a button that silently fails.
   - **Skip:** limited mode (user apps only, notification settings opened manually).
3. **Optional: Notification access:** explains why it's needed and opens `ACTION_NOTIFICATION_LISTENER_SETTINGS`.

A persistent status chip in the top bar shows backend status (`Shizuku ✓`, `Root ✓`, `Limited`). Tapping it reopens setup. If Shizuku stops (e.g. after a reboot), the chip turns red and privileged buttons prompt the user to reconnect.

### 4.7 Removed Apps (Restore)

A screen listing system packages that are uninstalled for the current user but still on the device (`MATCH_UNINSTALLED_PACKAGES` and not installed for this user).
- Each row has a **Restore** button (`cmd package install-existing`).
- **Restore all** in the overflow menu.

### 4.8 History

A log of every action (Room table):

| Field | Type |
|---|---|
| id | Long |
| packageName | String |
| appLabel | String |
| action | `UNINSTALL`, `UNINSTALL_SYSTEM`, `RESTORE`, `DISABLE`, `ENABLE`, `BLOCK_NOTIFICATIONS`, `UNBLOCK_NOTIFICATIONS` |
| timestamp | Instant |
| success | Boolean |
| output | String? (command output / error) |

Each entry has an **Undo** action where one exists.

### 4.9 Export / Import Profiles

- **Export** the list of removed and notification-blocked packages to a JSON file (Storage Access Framework).
- **Import** a profile and apply it in one go, e.g. on a new phone or after a factory reset. Before applying, show a preview diff, and mark Critical packages as skipped.

### 4.10 Settings

- Backend: Auto (Wireless debugging → Shizuku → root) / Wireless debugging / Shizuku / Root / Limited
- Default "Keep app data" on system uninstall (on)
- Expert mode (allows Critical packages, off by default)
- Show package names in list (on)
- Theme: System / Light / Dark; dynamic color toggle
- Notification listener status + shortcut
- About: version, licenses, source code link

---

## 5. Architecture

```
ui/
  applist/         AppListScreen, AppListViewModel, AppRow
  detail/          AppDetailSheet
  removed/         RemovedAppsScreen
  history/         HistoryScreen
  onboarding/      Onboarding flow
  settings/        SettingsScreen
domain/
  model/           AppInfo, SafetyRating, ActionResult, HistoryEntry
  usecase/         UninstallApp, RestoreApp, SetAppEnabled,
                   SetNotificationsBlocked, ExportProfile, ImportProfile
data/
  packages/        PackageRepository (PackageManager + broadcast listener)
  safety/          SafetyRepository (assets/packages.json + runtime role checks)
  history/         HistoryDao, HistoryDatabase
  settings/        SettingsDataStore
privileged/
  PrivilegedExecutor (interface)
  ShizukuExecutor
  RootExecutor
  NoopExecutor     (limited mode; returns Unsupported)
  BackendManager   (selects and monitors the active executor)
service/
  BlockedNotificationListener (NotificationListenerService)
```

### 5.1 Key interfaces

```kotlin
interface PrivilegedExecutor {
    val status: StateFlow<BackendStatus>   // Available, NeedsPermission, NotRunning, Unsupported
    suspend fun uninstall(pkg: String, userId: Int, keepData: Boolean): ActionResult
    suspend fun restore(pkg: String, userId: Int): ActionResult
    suspend fun setEnabled(pkg: String, userId: Int, enabled: Boolean): ActionResult
    suspend fun setNotificationsBlocked(pkg: String, uid: Int, blocked: Boolean): ActionResult
}

data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val uid: Int,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val isEnabled: Boolean,
    val isInstalledForUser: Boolean,
    val notificationsBlocked: Boolean,
    val apkPath: String,
    val installer: String?,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val safety: SafetyRating,
)

sealed interface ActionResult {
    data object Success : ActionResult
    data class Failure(val message: String, val output: String?) : ActionResult
    data object Unsupported : ActionResult
}
```

### 5.2 Notification-blocked state detection

- With a backend: `INotificationManager.areNotificationsEnabledForPackage(pkg, uid)` through Shizuku.
- Without one: the app's own record in History (best effort, marked "unverified").

---

## 6. UX Requirements

- List of 300+ apps scrolls at 60 fps; icons load off the main thread with an in-memory LRU cache.
- Cold start to interactive list in under 1.5 s on a mid-range device.
- Every destructive action needs a confirmation. Bulk actions show a summary count first ("Uninstall 12 apps?").
- Bulk actions run one after another with a progress dialog (`7 / 12`) and a final results summary (successes / failures).
- All strings in `strings.xml`, ready for translation.
- Accessibility: content descriptions on all icon buttons, minimum 48dp touch targets, TalkBack-readable safety ratings (not color-only).

---

## 7. Error Handling

| Situation | Behavior |
|---|---|
| Shizuku not installed / not running / permission denied | Status chip turns red; privileged actions open a setup guide instead of failing silently |
| `pm` returns `Failure [DELETE_FAILED_INTERNAL_ERROR]` or similar | Show raw output plus a plain-language explanation; log it to History |
| Package protected by OEM / device policy | "This app is protected by your device manufacturer or administrator and can't be removed. You can still block its notifications or disable it." |
| Package changed while dialog open | Re-check that it's installed before running; abort with a message if it's gone |
| Notification Listener not granted | Block still applies at the system level; a dismissible tip suggests enabling the listener to clear existing notifications |

---

## 8. Testing

- **Unit tests:** ViewModels, use cases, safety rating resolution, profile import/export parsing (JUnit + Turbine + MockK).
- **Executor tests:** fake `PrivilegedExecutor` that records commands, checking the exact `pm`/`cmd` arguments for each action.
- **UI tests:** Compose UI tests for search, filters, confirmation dialogs, Critical-package blocking.
- **Manual device matrix:** Pixel (stock, Android 14–16), Samsung One UI, Xiaomi HyperOS, plus an emulator with Google APIs. Check that uninstall → restore round-trips work for YouTube, and that notification blocking survives a reboot.

---

## 9. Milestones

| Milestone | Scope |
|---|---|
| **M1 — List** | Compose app list with search, filters, sorting, detail sheet; `ACTION_DELETE` fallback for user apps |
| **M2 — Shizuku uninstall** | Shizuku backend, onboarding, system app uninstall, Removed Apps restore, History |
| **M3 — Notifications** | Block/unblock notifications, Notification Listener cleanup and enforcement |
| **M4 — Safety** | Bundled package database, ratings, Critical protection, Expert mode |
| **M5 — Polish** | Multi-select bulk actions, export/import profiles, root backend, settings, translations, release build |

---

## 10. Open Questions

1. Which community debloat list should seed `packages.json`, and is its license compatible?
2. Should the app offer an "OEM presets" screen (e.g. "Samsung bloat", "Google extras") for one-tap bulk removal?
3. Should work-profile / secondary users be supported in v1, or only the primary user?
4. App license (GPL-3.0 suggested if bundling community debloat data under GPL).
