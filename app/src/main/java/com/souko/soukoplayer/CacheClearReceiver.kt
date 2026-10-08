package com.souko.soukoplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CacheClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        println("DEBUG: CacheClearReceiver received action: ${intent.action}")

        when (intent.action) {
            "CLEAR_GLIDE_CACHE" -> {
                println("DEBUG: Clearing Glide cache via broadcast")
                CacheCleaner.clearGlideCache(context.applicationContext)
            }
            "CLEAR_ALL_CACHES" -> {
                println("DEBUG: Clearing all caches via broadcast")
                CacheCleaner.clearAllCaches(context.applicationContext)
            }
        }
    }
}