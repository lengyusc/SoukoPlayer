package com.souko.soukoplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.souko.soukoplayer.models.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * 悬浮播放器统一管理类，负责连接 AudioService、UI更新及各种控制响应
 */
class MiniPlayerManager(
    private val activity: AppCompatActivity,
    private val playerControlWidget: View
) : DefaultLifecycleObserver {

    // 控件变量声明（使用原 ID）
    private val btnPrev: ImageButton = playerControlWidget.findViewById(R.id.btnPrev)
    private val btnPlayPause: ImageButton = playerControlWidget.findViewById(R.id.btnPlayPause)
    private val btnNext: ImageButton = playerControlWidget.findViewById(R.id.btnNext)
    private val btnRepeat: ImageButton = playerControlWidget.findViewById(R.id.btnRepeat)
    private val btnMegaBass: ImageButton = playerControlWidget.findViewById(R.id.btnMegaBass)
    private val seekBar: SeekBar = playerControlWidget.findViewById(R.id.seekBar)
    private val tvCurTime: TextView = playerControlWidget.findViewById(R.id.tvCurTime)
    private val tvTotalTime: TextView = playerControlWidget.findViewById(R.id.tvTotalTime)
    private val tvNowPlayingTitle: TextView = playerControlWidget.findViewById(R.id.tvNowPlayingTitle)
    private val tvNowPlayingArtist: TextView = playerControlWidget.findViewById(R.id.tvNowPlayingArtist)

    // Service 状态与标识
    private var audioService: AudioService? = null
    private var isBound = false
    private var isSeeking = false

    // Handler 与 线程控制
    private val handler = Handler(Looper.getMainLooper())

    // 1. ExoPlayer 播放状态回调
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            activity.runOnUiThread {
                updatePlayPauseButton(isPlaying)
                refreshNowPlayingBar()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            activity.runOnUiThread { refreshNowPlayingBar() }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            activity.runOnUiThread { refreshNowPlayingBar() }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            activity.runOnUiThread { refreshNowPlayingBar() }
        }
    }

    // 2. 循环模式改变监听器
    private val repeatListener = {
        activity.runOnUiThread {
            updateRepeatButtonIcon()
        }
    }

    // 3. MEGA BASS 改变监听器
    private val megaBassListener: (Boolean) -> Unit = { isEnabled ->
        activity.runOnUiThread {
            updateMegaBassButton(isEnabled)
        }
    }

    // ServiceConnection 实现
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as AudioService.AudioBinder
            audioService = binder.getService()
            isBound = true

            // 解除可能存在的旧监听，重新注册
            try {
                audioService?.removePlayerListener(playerListener)
                audioService?.removeRepeatModeListener(repeatListener)
                audioService?.removeMegaBassListener(megaBassListener)
            } catch (_: Exception) {}

            audioService?.addPlayerListener(playerListener)
            audioService?.addRepeatModeListener(repeatListener)
            audioService?.addMegaBassListener(megaBassListener)

            // 同步 UI 状态
            updateAllPlayerUI()
            startProgressUpdater()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            audioService?.removePlayerListener(playerListener)
            audioService?.removeRepeatModeListener(repeatListener)
            audioService?.removeMegaBassListener(megaBassListener)
            audioService = null
        }
    }

    init {
        initPlayerControls()

        // 点击悬浮播放栏跳转到当前播放的专辑详情（切换为 AlbumDetailFragment）
        playerControlWidget.setOnClickListener {
            val albumId = audioService?.getCurrentAlbumId()

            // 场景 1：没听歌
            if (albumId.isNullOrEmpty()) {
                Toast.makeText(activity, "没有正在播放的歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentFragment = activity.supportFragmentManager.findFragmentById(R.id.fragment_container)

            // 场景 4：已经在【正在播放的专辑详情页】，点击跳转到【歌词+LED跳动页面】
            if (currentFragment is AlbumDetailFragment && currentFragment.getDisplayedAlbumId() == albumId) {
                val lyricsLedFragment = LyricsLedFragment.newInstance()
                activity.supportFragmentManager.beginTransaction()
                    .setCustomAnimations(
                        android.R.anim.fade_in,
                        android.R.anim.fade_out,
                        android.R.anim.fade_in,
                        android.R.anim.fade_out
                    )
                    .replace(R.id.fragment_container, lyricsLedFragment)
                    .addToBackStack(null)
                    .commit()
                return@setOnClickListener
            }

            // 场景 5（补充防护）：如果已经在【歌词+LED页面】，点击不进行操作
            if (currentFragment is LyricsLedFragment) {
                return@setOnClickListener
            }

            // 场景 3：在浏览【其他专辑详情页】，先将当前详情页弹出，再进入正在播放的专辑页
            if (currentFragment is AlbumDetailFragment) {
                activity.supportFragmentManager.popBackStack()
            }

            // 场景 2 & 3 统一跳转：跳转到正在播放的专辑详情页
            val albumName = audioService?.getCurrentAlbumName() ?: ""
            val artistName = audioService?.getCurrentArtistName() ?: ""
            val albumArtUrl = audioService?.getCurrentAlbumCover() ?: ""
            val releaseYearStr = audioService?.getCurrentReleaseYear() ?: ""
            val releaseYear = releaseYearStr.toIntOrNull() ?: 0

            val detailFragment = AlbumDetailFragment.newInstance(
                albumId = albumId,
                albumName = albumName,
                artistName = artistName,
                releaseYear = releaseYear,
                albumArtUrl = albumArtUrl
            )

            activity.supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    android.R.anim.slide_in_left,
                    android.R.anim.slide_out_right,
                    android.R.anim.slide_in_left,
                    android.R.anim.slide_out_right
                )
                .replace(R.id.fragment_container, detailFragment)
                .addToBackStack(null)
                .commit()

            updateCacheSizeDisplay()
        }

        // 将自己绑定到 Activity 的生命周期观察者中
        activity.lifecycle.addObserver(this)
    }

    private fun initPlayerControls() {
        btnPrev.setOnClickListener { prevTrack() }
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnNext.setOnClickListener { nextTrack() }

        btnRepeat.setOnClickListener {
            audioService?.toggleRepeatMode()
        }

        btnMegaBass.setOnClickListener {
            if (isBound) {
                val isEnabled = audioService?.toggleMegaBass() ?: false
                updateMegaBassButton(isEnabled)
            }
        }

        // 进度条拖动监听
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && isBound) {
                    val duration = audioService?.getDuration() ?: 0
                    if (duration > 0) {
                        val pos = progress * duration / 100
                        tvCurTime.text = formatTime(pos)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                seekBar?.progress?.let { progress ->
                    if (isBound) {
                        val duration = audioService?.getDuration() ?: 0
                        val pos = progress * duration / 100
                        audioService?.seekTo(pos)
                    }
                }
            }
        })
    }

    // 跟随 Activity 生命周期管理 Service 绑定与解绑
    override fun onResume(owner: LifecycleOwner) {
        val serviceIntent = Intent(activity, AudioService::class.java)
        activity.startService(serviceIntent)

        if (!isBound) {
            activity.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        } else {
            audioService?.addRepeatModeListener(repeatListener)
            updateAllPlayerUI()
        }

        handler.postDelayed({
            if (isBound) {
                audioService?.forceRefreshUI()
                updateAllPlayerUI()
                startProgressUpdater()
            }
        }, 300)
    }

    override fun onPause(owner: LifecycleOwner) {
        stopProgressUpdater()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (isBound) {
            audioService?.removePlayerListener(playerListener)
            audioService?.removeRepeatModeListener(repeatListener)
            audioService?.removeMegaBassListener(megaBassListener)
            activity.unbindService(connection)
            isBound = false
        }
        stopProgressUpdater()
    }

    /**
     * 统一同步悬浮控制栏的所有UI组件状态
     */
    private fun updateAllPlayerUI() {
        if (!isBound) return
        refreshNowPlayingBar()
        updateRepeatButtonIcon()
        val isMegaBassEnabled = audioService?.isMegaBassEnabled() ?: false
        updateMegaBassButton(isMegaBassEnabled)
        updateProgress()
    }

    private fun refreshNowPlayingBar() {
        val track: Track? = audioService?.getCurrentTrack()

        if (track == null) {
            tvNowPlayingTitle.text = "暂无播放"
            tvNowPlayingArtist.text = "点击专辑开始播放"
            updatePlayPauseButton(false)
            return
        }

        tvNowPlayingTitle.text = track.title
        tvNowPlayingArtist.text = track.artist
        playerControlWidget.visibility = View.VISIBLE
        updatePlayPauseButton(audioService?.isPlaying() ?: false)
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_souko_pause else R.drawable.ic_souko_play
        )
    }

    private fun updateRepeatButtonIcon() {
        if (!isBound) return
        when (audioService?.repeatMode) {
            AudioService.RepeatMode.LIST_LOOP -> {
                btnRepeat.setImageResource(R.drawable.ic_list_loop)
                btnRepeat.contentDescription = "List Loop"
            }
            AudioService.RepeatMode.LIST_ORDER -> {
                btnRepeat.setImageResource(R.drawable.ic_list_order)
                btnRepeat.contentDescription = "List Order"
            }
            AudioService.RepeatMode.SINGLE_LOOP -> {
                btnRepeat.setImageResource(R.drawable.ic_single_loop)
                btnRepeat.contentDescription = "Single Loop"
            }
            else -> {
                btnRepeat.setImageResource(R.drawable.ic_list_loop)
                btnRepeat.contentDescription = "List Loop"
            }
        }
    }

    private fun updateMegaBassButton(isEnabled: Boolean) {
        btnMegaBass.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(50)
            .withEndAction {
                if (isEnabled) {
                    btnMegaBass.setImageResource(R.drawable.ic_bass_on)
                } else {
                    btnMegaBass.setImageResource(R.drawable.ic_bass_off)
                }
                btnMegaBass.clearColorFilter()

                btnMegaBass.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    private fun togglePlayPause() {
        if (isBound) {
            if (audioService?.isPlaying() == true) {
                audioService?.pause()
                updatePlayPauseButton(false)
            } else {
                audioService?.play()
                updatePlayPauseButton(true)
            }
        }
    }

    private fun prevTrack() {
        if (isBound) audioService?.prevTrack()
    }

    private fun nextTrack() {
        if (isBound) audioService?.nextTrack()
    }

    private fun updateProgress() {
        if (isBound && audioService?.isPlaying() == true && !isSeeking) {
            val pos = audioService?.getCurrentPosition() ?: 0
            val duration = audioService?.getDuration() ?: 0
            if (duration > 0) {
                val progress = (pos * 100 / duration).toInt()
                seekBar.progress = progress
                tvCurTime.text = formatTime(pos)
                tvTotalTime.text = formatTime(duration)
            }
        }
    }

    private fun startProgressUpdater() {
        activity.lifecycleScope.launch {
            while (isBound && isActive) {
                try {
                    if (isBound && audioService?.isPlaying() == true) {
                        val pos = audioService?.getCurrentPosition() ?: 0
                        val duration = audioService?.getDuration() ?: 0
                        if (duration > 0 && !isSeeking) {
                            val progress = (pos * 100 / duration).toInt()
                            activity.runOnUiThread {
                                seekBar.progress = progress
                                tvCurTime.text = formatTime(pos)
                                tvTotalTime.text = formatTime(duration)
                            }
                        }
                    }
                    delay(300.milliseconds)
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun stopProgressUpdater() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun updateCacheSizeDisplay() {
        val cacheSize = CacheCleaner.getCacheSize(activity)
        println("DEBUG: Current cache size: $cacheSize")
    }
}