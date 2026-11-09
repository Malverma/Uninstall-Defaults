# Started by class name from outside the normal app process:
#  - PrivilegedCli via `app_process` (root and built-in ADB backends)
#  - PrivilegedService by Shizuku's UserService host
-keep class com.defaultuninstaller.privileged.** { *; }

# Conscrypt is called from native code and looked up reflectively by libadb.
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-keep class io.github.muntashirakon.** { *; }
-dontwarn org.bouncycastle.**
