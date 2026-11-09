package com.defaultuninstaller.data.packages

import android.content.Context
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IconCache(private val context: Context) {
    private val cache = LruCache<String, ImageBitmap>(400)

    fun cached(pkg: String): ImageBitmap? = cache.get(pkg)

    suspend fun load(pkg: String): ImageBitmap? = cache.get(pkg) ?: withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            ai.loadIcon(pm).toBitmap(SIZE_PX, SIZE_PX).asImageBitmap().also { cache.put(pkg, it) }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val SIZE_PX = 128
    }
}
