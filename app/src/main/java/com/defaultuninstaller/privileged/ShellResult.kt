package com.defaultuninstaller.privileged

import android.os.Bundle

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    /** `pm` often exits 0 while printing "Failure [...]", so the output is checked too. */
    val isSuccess: Boolean
        get() = exitCode == 0 &&
            !stdout.contains("Failure") && !stderr.contains("Failure") &&
            !stderr.contains("Exception")

    val output: String get() = listOf(stdout.trim(), stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")

    fun toBundle() = Bundle().apply {
        putInt(KEY_EXIT, exitCode)
        putString(KEY_OUT, stdout)
        putString(KEY_ERR, stderr)
    }

    companion object {
        private const val KEY_EXIT = "exit"
        private const val KEY_OUT = "out"
        private const val KEY_ERR = "err"

        fun fromBundle(bundle: Bundle) = ShellResult(
            bundle.getInt(KEY_EXIT, -1),
            bundle.getString(KEY_OUT).orEmpty(),
            bundle.getString(KEY_ERR).orEmpty(),
        )

        fun error(message: String) = ShellResult(-1, "", message)
    }
}
