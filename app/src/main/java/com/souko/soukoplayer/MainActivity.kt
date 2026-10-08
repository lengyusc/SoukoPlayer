package com.souko.soukoplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var playerControlWidget: View
    private lateinit var miniPlayerManager: MiniPlayerManager

    // 1. 注册通知权限请求回调（针对 Android 13+ POST_NOTIFICATIONS）
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "用户已授予通知权限")
        } else {
            Log.w(TAG, "用户拒绝了通知权限，前台服务通知可能无法正常显示")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化悬浮播放器
        playerControlWidget = findViewById(R.id.playerControlWidget)
        miniPlayerManager = MiniPlayerManager(this, playerControlWidget)

        // 2. 在这里发起通知权限检查与请求
        checkAndRequestNotificationPermission()

        // 默认显示专辑列表 Fragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, AlbumListFragment())
                .commit()
        }

        handleSearchIntent(intent)
    }

    /**
     * 检查并请求通知权限（Android 13 / API 33 及以上必需）
     */
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "正在请求通知权限...")
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d(TAG, "已拥有通知权限")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 确保单例模式下重新启动 MainActivity 时也能接收并处理跳转
        handleSearchIntent(intent)
    }

    private fun handleSearchIntent(intent: Intent?) {
        val albumId = intent?.getStringExtra("EXTRA_OPEN_ALBUM_ID")
        if (!albumId.isNullOrEmpty()) {
            val albumName = intent.getStringExtra("EXTRA_ALBUM_NAME") ?: ""
            val artistName = intent.getStringExtra("EXTRA_ARTIST_NAME") ?: ""
            val albumArtUrl = intent.getStringExtra("EXTRA_ALBUM_ART_URL") ?: ""
            val trackId = intent.getStringExtra("EXTRA_TRACK_ID") // 读取需要直接播放的歌曲 ID

            // 兼容解析年份：优先读取 String，读不到则读取 Int，均无则默认为 0
            val releaseYearStr = intent.getStringExtra("EXTRA_RELEASE_YEAR")
            val releaseYear = releaseYearStr?.toIntOrNull()
                ?: intent.getIntExtra("EXTRA_RELEASE_YEAR", 0)

            // 实例化 AlbumDetailFragment 并传入 trackId
            val detailFragment = AlbumDetailFragment.newInstance(
                albumId = albumId,
                albumName = albumName,
                artistName = artistName,
                releaseYear = releaseYear,
                albumArtUrl = albumArtUrl,
                trackId = trackId
            )

            // 替换容器中的 Fragment 并入栈
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, detailFragment)
                .addToBackStack(null)
                .commit()

            // 消费掉 Key，避免重复触发
            intent.removeExtra("EXTRA_OPEN_ALBUM_ID")
            intent.removeExtra("EXTRA_TRACK_ID")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            (application as MyApplication).stopAudioServiceAndCleanUp()

            try {
                Glide.get(this).clearMemory()
            } catch (e: Exception) {
                println("DEBUG: Glide clearMemory failed: ${e.message}")
            }

            Thread {
                try {
                    Glide.get(this).clearDiskCache()
                } catch (e: Exception) {
                    println("DEBUG: Glide clearDiskCache failed: ${e.message}")
                }
            }.start()
        }
    }
}