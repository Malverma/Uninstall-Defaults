package com.defaultuninstaller.privileged;

// Runs inside the Shizuku user-service process (uid 2000 "shell" or uid 0 "root").
// Results are Bundles with keys "exit" (int), "out" (String), "err" (String).
interface IPrivilegedService {
    // Reserved by Shizuku for UserService teardown.
    void destroy() = 16777114;

    Bundle exec(String command) = 1;

    Bundle setNotificationsEnabled(String packageName, int uid, boolean enabled) = 2;
}
