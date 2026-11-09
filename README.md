# Uninstall Defaults

Removes preloaded Android apps (YouTube, carrier bloat, OEM extras) that the launcher won't let you uninstall, and fully blocks notifications from apps you keep. See [spec.md](spec.md).

## How it gets permission

Normal apps can't touch system packages, so this one runs its commands through an elevated backend:

| Backend | Privilege | Notes |
|---|---|---|
| **Shizuku** | `shell` (uid 2000), or root if Shizuku was started with root | No root needed. Restart Shizuku after each reboot (Wireless debugging or `adb`). |
| **Root** | uid 0 via `su` (Magisk / KernelSU / APatch) | Shell commands go through `su`; binder calls run in-APK code via `app_process` as root. |
| Limited | none | User apps only (system uninstall dialog); notifications open system settings. |

"Automatic" (default) uses Shizuku when it's connected, otherwise root if you granted it.

Preloaded apps are removed with `pm uninstall -k --user 0`. The factory APK stays on the read-only system partition, so every removal can be undone from **Removed apps** (`pm install-existing`), and a factory reset restores everything.

**Block notifications** does three things: turns off the app's system-wide notification switch, revokes `POST_NOTIFICATIONS` and pins it as user-fixed (Android 13+), and sets the `POST_NOTIFICATION` app-op to `ignore`. With notification access granted, it also clears what the app has already posted.

## Build

```sh
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Needs JDK 17+ and the Android SDK (platform 35). `local.properties` points at the SDK.

Release signing reads `signing-key.txt` in the project root (gitignored, never committed):

```properties
storeFile=release.keystore      # relative to app/
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file, `assembleRelease` produces `app-release-unsigned.apk` instead.

## Install and set up

1. `adb install app/build/outputs/apk/release/app-release.apk`
2. Install [Shizuku](https://shizuku.rikka.app/download/) and start it (Wireless debugging on Android 11+, or `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`). On a rooted phone you can skip this and tap **Request root** instead.
3. Open Uninstall Defaults → **Grant permission** → **Continue**.
4. Optional: allow notification access so blocked apps' existing notifications get cleared.

Xiaomi/Oppo/Vivo: also turn on **USB debugging (Security settings)** in Developer options, or `pm` calls through Shizuku get denied.
