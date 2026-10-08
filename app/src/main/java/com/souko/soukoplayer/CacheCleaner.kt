package com.souko.soukoplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.Glide
import com.souko.soukoplayer.network.NavidromeApi
import java.io.File
import java.util.Locale

object CacheCleaner {

    /** 清理 Glide 内存和磁盘缓存 */
    fun clearGlideCache(context: Context) {
        try {
            println("DEBUG: Clearing Glide cache")

            // 内存缓存必须在主线程
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Glide.get(context).clearMemory()
                println("DEBUG: Glide memory cache cleared")
            } else {
                Handler(Looper.getMainLooper()).post {
                    Glide.get(context).clearMemory()
                    println("DEBUG: Glide memory cache cleared")
                }
            }

            // 磁盘缓存在后台线程
            Thread {
                try {
                    Glide.get(context).clearDiskCache()
                    println("DEBUG: Glide disk cache cleared successfully")
                } catch (e: Exception) {
                    println("DEBUG: Failed to clear Glide disk cache: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            println("DEBUG: Failed to clear Glide cache: ${e.message}")
        }
    }

    /** 清理应用所有缓存 */
    fun clearAllCaches(context: Context) {
        println("DEBUG: Clearing all application caches")

        val appContext = context.applicationContext

        // 1. Glide 缓存
        clearGlideCache(appContext)

        // 2. 内部缓存
        clearInternalCache(appContext)

        // 3. 外部缓存
        clearExternalCache(appContext)

        // 4. ExoPlayer 缓存
        clearExoPlayerCache(appContext)

        // 5. 临时文件
        clearTempFiles(appContext)
    }

    // ------------------ 私有方法 ------------------

    private fun clearInternalCache(context: Context) {
        try {
            val cacheDir = context.cacheDir
            if (cacheDir.exists() && cacheDir.isDirectory) {
                cacheDir.deleteRecursively()
                println("DEBUG: Internal cache cleared")
            }
        } catch (e: Exception) {
            println("DEBUG: Failed to clear internal cache: ${e.message}")
        }
    }

    private fun clearExternalCache(context: Context) {
        try {
            val externalCacheDir = context.externalCacheDir
            if (externalCacheDir?.exists() == true && externalCacheDir.isDirectory) {
                externalCacheDir.deleteRecursively()
                println("DEBUG: External cache cleared")
            }
        } catch (e: Exception) {
            println("DEBUG: Failed to clear external cache: ${e.message}")
        }
    }

    private fun clearExoPlayerCache(context: Context) {
        try {
            val dirs = listOf(
                File(context.cacheDir, "exoplayer"),
                File(context.externalCacheDir, "exoplayer")
            )
            dirs.forEach { dir ->
                if (dir.exists() && dir.isDirectory) {
                    dir.deleteRecursively()
                    println("DEBUG: ExoPlayer cache cleared at ${dir.absolutePath}")
                }
            }
        } catch (e: Exception) {
            println("DEBUG: Failed to clear ExoPlayer cache: ${e.message}")
        }
    }

    private fun clearTempFiles(context: Context) {
        try {
            val tempDir = File(context.cacheDir, "temp")
            if (tempDir.exists() && tempDir.isDirectory) {
                tempDir.deleteRecursively()
                println("DEBUG: Temp files cleared")
            }
        } catch (e: Exception) {
            println("DEBUG: Failed to clear temp files: ${e.message}")
        }
    }

    /** 获取当前缓存大小 */
    fun getCacheSize(context: Context): String {
        val internalCacheSize = getDirectorySize(context.cacheDir)
        val externalCacheSize = context.externalCacheDir?.let { getDirectorySize(it) } ?: 0L
        val totalSize = internalCacheSize + externalCacheSize

        return when {
            totalSize > 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f MB", totalSize / (1024.0 * 1024.0))
            totalSize > 1024 -> String.format(Locale.getDefault(), "%.2f KB", totalSize / 1024.0)
            else -> "$totalSize B"
        }
    }

    private fun getDirectorySize(directory: File): Long {
        if (!directory.exists() || !directory.isDirectory) return 0L
        var size = 0L
        directory.listFiles()?.forEach { file ->
            size += if (file.isFile) file.length() else getDirectorySize(file)
        }
        return size
    }
}
