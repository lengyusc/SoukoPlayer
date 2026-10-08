package com.souko.soukoplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.souko.soukoplayer.models.Track
import androidx.core.content.edit
import androidx.media3.session.MediaSession

class AudioService : Service() {

    // ======================== 常量定义 ========================
    companion object {
        const val ACTION_TRACK_CHANGED = "com.souko.soukoplayer.ACTION_TRACK_CHANGED"
    }

    // ======================== 基础组件声明 ========================
    private lateinit var exoPlayer: ExoPlayer
    private val binder = AudioBinder()
    private val listeners = mutableListOf<Player.Listener>()

    // ======================== 播放队列与状态 ========================
    private var trackQueue: List<Track> = emptyList()
    private var currentIndex: Int = 0
    private var currentAlbumArtUrl: String = ""
    private var currentAlbumId: String = ""
    private var currentAlbumName: String = ""
    private var currentArtistName: String = ""
    private var currentReleaseYear: String = ""
    private lateinit var mediaSession: MediaSession

    // ======================== 音频焦点管理 ========================
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // ======================== MEGA BASS 音效管理 ========================
    private lateinit var equalizer: Equalizer
    private var isMegaBassEnabled = false
    private var pendingAudioEffectsInitialization = false
    private val prefs by lazy {
        getSharedPreferences("audio_settings", MODE_PRIVATE)
    }
    // 在 AudioService.kt 内部添加
    @OptIn(UnstableApi::class)
    fun getAudioSessionId(): Int {
        return exoPlayer.audioSessionId
    }

    // ======================== Binder 与 Service 绑定 ========================
    inner class AudioBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    // ======================== 播放模式枚举 ========================
    enum class RepeatMode {
        LIST_LOOP,      // 列表循环
        LIST_ORDER,     // 顺序播放
        SINGLE_LOOP     // 单曲循环
    }
    var repeatMode: RepeatMode = RepeatMode.LIST_LOOP
        private set

    // ======================== Service 生命周期 ========================
    override fun onCreate() {
        super.onCreate()
        println("DEBUG: AudioService.onCreate()")

        // 从 SharedPreferences 加载初始状态
        val savedMegaBassState = prefs.getBoolean("mega_bass_enabled", false)
        isMegaBassEnabled = savedMegaBassState
        println("DEBUG: Initial MEGA BASS state loaded: $isMegaBassEnabled")

        exoPlayer = ExoPlayer.Builder(this).build()

        // ======================== 初始化 MediaSession ========================
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setId("SoukoPlayer")
            .setSessionActivity(getMainActivityPendingIntent())
            .build()
        // 设置初始重复模式
        updateExoPlayerRepeatMode()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        setupAudioFocus()
        initAudioEffects() // 初始化音效
        createNotificationChannel()
        startForegroundNotification()

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                println("DEBUG: Service onIsPlayingChanged: $isPlaying")
                updateNotification()
                safeNotifyAllListeners { listener ->
                    listener.onIsPlayingChanged(isPlaying)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 关键修复：立即同步当前索引
                currentIndex = exoPlayer.currentMediaItemIndex
                println("DEBUG: Service onMediaItemTransition: $reason, index: $currentIndex")

                // 发送切歌广播通知 UI/LyricsLedFragment ===
                sendBroadcast(Intent(ACTION_TRACK_CHANGED).setPackage(packageName))

                // 切换歌曲时重新应用 MEGA BASS ===
                if (isMegaBassEnabled && ::equalizer.isInitialized) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isMegaBassEnabled) {
                            enableMegaBass()
                            println("DEBUG: MEGA BASS reapplied on media transition")
                        }
                    }, 150)
                }

                updateNotification()
                safeNotifyAllListeners { listener ->
                    listener.onMediaItemTransition(mediaItem, reason)
                }
                setupAudioDeviceListener()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                println("DEBUG: Playback state changed to: $playbackState")

                // === 新增：基于播放状态的音效初始化 ===
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (pendingAudioEffectsInitialization) {
                            println("DEBUG: Player ready, initializing pending audio effects...")
                            initAudioEffects()
                            pendingAudioEffectsInitialization = false
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        println("DEBUG: Player buffering, audio effects still pending: $pendingAudioEffectsInitialization")
                    }
                    Player.STATE_ENDED, Player.STATE_IDLE -> {
                        // 播放结束时重置标志，为下一首歌准备
                        pendingAudioEffectsInitialization = false
                        println("DEBUG: Playback ended, reset audio effects pending flag")
                    }
                }

                // 处理播放结束
                if (playbackState == Player.STATE_ENDED) {
                    println("DEBUG: Playback ended, current repeat mode: $repeatMode")

                    // 只有在顺序播放模式下才需要特殊处理
                    if (repeatMode == RepeatMode.LIST_ORDER) {
                        handleTrackEnd()
                    }
                    // 列表循环和单曲循环由ExoPlayer自动处理
                }

                // 通知所有监听器
                listeners.forEach { it.onPlaybackStateChanged(playbackState) }
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                println("DEBUG: ExoPlayer repeat mode changed to: $repeatMode")
                // 这个回调可以用于调试，但不是必须的
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopServiceAndCleanup()
    }

    // 在onStartCommand中处理按钮点击
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                keyEvent?.let {
                    when (it.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> play()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (isPlaying()) pause() else play()
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> nextTrack()
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> prevTrack()
                    }
                }
            }
            "ACTION_TOGGLE_PLAYBACK" -> {
                if (isPlaying()) pause() else play()
            }
            "ACTION_NEXT" -> nextTrack()
            "ACTION_PREV" -> prevTrack()
            "ACTION_TOGGLE_MEGABASS" -> toggleMegaBass()
            "ACTION_STOP_SERVICE" -> {
                stopServiceAndCleanup()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // 释放 MediaSession
        mediaSession.release()

        // 确保通知被清除
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(1)

        // 清理数据
        clearAllData()

        // 释放播放器资源
        abandonAudioFocus()
        if (::exoPlayer.isInitialized) {
            exoPlayer.release()
        }
        releaseEqualizer()
    }

    // ======================== 播放模式控制 ========================
    fun toggleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.LIST_LOOP -> RepeatMode.LIST_ORDER
            RepeatMode.LIST_ORDER -> RepeatMode.SINGLE_LOOP
            RepeatMode.SINGLE_LOOP -> RepeatMode.LIST_LOOP
        }
        println("DEBUG: Repeat mode changed to $repeatMode")

        // 根据新模式设置ExoPlayer的重复模式
        updateExoPlayerRepeatMode()

        // 通知UI更新 - 这里调用你现有的回调机制
        notifyRepeatModeChanged()

        // 可选：强制刷新UI
        forceRefreshUI()
    }

    private fun updateExoPlayerRepeatMode() {
        when (repeatMode) {
            RepeatMode.LIST_LOOP -> {
                exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
                println("DEBUG: ExoPlayer set to REPEAT_MODE_ALL")
            }
            RepeatMode.LIST_ORDER -> {
                exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
                println("DEBUG: ExoPlayer set to REPEAT_MODE_OFF")
            }
            RepeatMode.SINGLE_LOOP -> {
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                println("DEBUG: ExoPlayer set to REPEAT_MODE_ONE")
            }
        }
    }

    private fun handleTrackEnd() {
        println("DEBUG: handleTrackEnd called, repeatMode: $repeatMode, currentIndex: $currentIndex, queueSize: ${trackQueue.size}")

        when (repeatMode) {
            RepeatMode.LIST_ORDER -> {
                // 顺序播放 - 如果是最后一首就暂停，否则播下一首
                if (currentIndex < trackQueue.size - 1) {
                    println("DEBUG: List order mode - playing next track")
                    nextTrack()
                } else {
                    println("DEBUG: List order mode - last track, pausing")
                    pause()
                    // 可选：回到第一首
                    // currentIndex = 0
                }
            }
            RepeatMode.LIST_LOOP, RepeatMode.SINGLE_LOOP -> {
                // 列表循环和单曲循环由ExoPlayer自动处理，不需要额外代码
                println("DEBUG: Loop mode - letting ExoPlayer handle automatically")
            }
        }
    }

    // ======================== MEGA BASS 音效控制 ========================

    /**
     * 设置音频设备变化监听
     */
    private fun setupAudioDeviceListener() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                println("DEBUG: Audio devices added")
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                println("DEBUG: Audio devices removed")
            }
        }, null)
    }

    @OptIn(UnstableApi::class)
    private fun initAudioEffects() {
        try {
            val audioSessionId = exoPlayer.audioSessionId
            println("DEBUG: initAudioEffects - Audio session ID: $audioSessionId")

            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                // 先释放旧的 Equalizer
                releaseEqualizer()

                println("DEBUG: Starting delayed Equalizer initialization...")

                // 分阶段延迟初始化
                Handler(Looper.getMainLooper()).postDelayed({
                    createEqualizer(audioSessionId)
                }, 350)

            } else {
                println("DEBUG: Audio session ID is UNSET")
            }
        } catch (e: Exception) {
            println("DEBUG: initAudioEffects failed: ${e.message}")
        }
    }

    private fun createEqualizer(audioSessionId: Int) {
        try {
            println("DEBUG: Creating Equalizer...")
            equalizer = Equalizer(0, audioSessionId)
            println("DEBUG: Equalizer created, waiting for stabilization...")

            Handler(Looper.getMainLooper()).postDelayed({
                initializeEqualizerEffects()
            }, 150)

        } catch (e: Exception) {
            println("DEBUG: createEqualizer failed: ${e.message}")
        }
    }

    private fun initializeEqualizerEffects() {
        try {
            println("DEBUG: Initializing Equalizer effects...")

            // 从 SharedPreferences 加载状态
            val savedMegaBassState = prefs.getBoolean("mega_bass_enabled", false)
            println("DEBUG: Loaded MEGA BASS state: $savedMegaBassState")

            // 同步状态
            if (savedMegaBassState != isMegaBassEnabled) {
                isMegaBassEnabled = savedMegaBassState
            }

            // 应用效果
            if (isMegaBassEnabled) {
                println("DEBUG: Enabling MEGA BASS...")
                val success = enableMegaBass()
                println("DEBUG: MEGA BASS enabled - Success: $success")
            } else {
                println("DEBUG: MEGA BASS is disabled")
            }
        } catch (e: Exception) {
            println("DEBUG: initializeEqualizerEffects failed: ${e.message}")
        }
    }

    // 添加状态保存方法
    private fun saveMegaBassState(enabled: Boolean) {
        // 使用 KTX 扩展函数
        prefs.edit {
            putBoolean("mega_bass_enabled", enabled)
        }
        println("DEBUG: === SAVED MEGA BASS STATE: $enabled ===")
    }

    // 修改 toggleMegaBass 方法，添加状态保存
    fun toggleMegaBass(): Boolean {
        println("DEBUG: === toggleMegaBass CALLED ===")
        println("DEBUG: Current state before toggle: $isMegaBassEnabled")

        isMegaBassEnabled = !isMegaBassEnabled

        // 立即保存状态
        saveMegaBassState(isMegaBassEnabled)
        println("DEBUG: New state after toggle: $isMegaBassEnabled")

        val success = if (isMegaBassEnabled) {
            enableMegaBass()
        } else {
            disableMegaBass()
        }

        // 强制刷新通知
        updateNotification()

        println("DEBUG: MEGA BASS ${if (isMegaBassEnabled) "Enabled" else "Disabled"} - Success: $success")
        notifyMegaBassStateChanged()
        return isMegaBassEnabled
    }

    private fun enableMegaBass(): Boolean {
        if (!::equalizer.isInitialized) {
            println("DEBUG: enableMegaBass - Equalizer not initialized")
            return false
        }

        try {
            for (attempt in 1..2) {
                try {
                    if (!equalizer.enabled) {
                        equalizer.enabled = true
                    }

                    // 应用统一的MEGA BASS曲线
                    for (band in 0 until equalizer.numberOfBands) {
                        val centerFreq = equalizer.getCenterFreq(band.toShort()) / 1000f
                        val targetGain = calculateMegaBassGain(centerFreq)
                        equalizer.setBandLevel(band.toShort(), targetGain)

                        // 调试输出
                        if (band < 5) {
                            println("DEBUG: Band $band (${centerFreq}Hz) -> ${targetGain / 100}dB")
                        }
                    }

                    // 方法1：通过EQ整体提升中频来补偿音量（更安全）
                    // 在800-2000Hz人声区域稍微提升来补偿感知音量
                    println("DEBUG: MEGA BASS enabled with volume compensation")

                    println("DEBUG: MEGA BASS enabled successfully")
                    return true
                } catch (e: Exception) {
                    if (attempt == 1) {
                        println("DEBUG: MEGA BASS enable failed, retrying...")
                        Thread.sleep(50)
                    } else {
                        throw e
                    }
                }
            }
            return false
        } catch (e: Exception) {
            println("DEBUG: Error enabling MEGA BASS: ${e.message}")
            return false
        }
    }

    private fun disableMegaBass(): Boolean {
        if (!::equalizer.isInitialized) {
            return true
        }

        try {
            // 重置所有频段到 0
            for (band in 0 until equalizer.numberOfBands) {
                equalizer.setBandLevel(band.toShort(), 0)
            }

            // 确保通知更新
            updateNotification()

            println("DEBUG: MEGA BASS disabled successfully")
            return true
        } catch (e: Exception) {
            println("DEBUG: Error disabling MEGA BASS: ${e.message}")
            return false
        }
    }


    // ======================== 统一的 MEGA BASS 曲线 ========================

    /**
     * 统一的 MEGA BASS 曲线 - 所有设备通用，已包含音量补偿
     */
    private fun calculateMegaBassGain(frequency: Float): Short {
        return when {
            // 超低频：适度增强，避免过度
            frequency <= 50f -> (6 * 100).toShort()      // +6dB 超低频
            frequency <= 80f -> (10 * 100).toShort()     // +10dB 核心低音区
            frequency <= 120f -> (6 * 100).toShort()     // +6dB 低音打击感
            frequency <= 200f -> (3 * 100).toShort()     // +3dB 平滑过渡

            // 中低频：轻微削减或保持平坦，减少闷感
            frequency <= 400f -> (-1 * 100).toShort()    // -1dB 减少箱音
            frequency <= 800f -> 0                       // 0dB 中频基准

            // 中高频：适度提升清晰度 + 音量补偿
            frequency <= 2000f -> (2 * 100).toShort()    // +2dB 人声清晰度
            frequency <= 4000f -> (3 * 100).toShort()    // +3dB 乐器细节
            frequency <= 8000f -> (2 * 100).toShort()    // +2dB 空气感

            // 高频：适度提升，避免刺耳
            frequency <= 10000f -> (1 * 100).toShort()   // +1dB 明亮度
            frequency <= 12000f -> (1 * 100).toShort()   // +1dB 细节
            frequency <= 15000f -> 0                     // 0dB 保持自然
            frequency <= 20000f -> 0                     // 0dB 超高频

            else -> 0
        }
    }

    // 添加 EQ 释放
    private fun releaseEqualizer() {
        if (::equalizer.isInitialized) {
            try {
                equalizer.enabled = false
                equalizer.release()
            } catch (e: Exception) {
                println("DEBUG: Error releasing equalizer: ${e.message}")
            }
        }
    }

    // ======================== 音频焦点管理 ========================
    private fun setupAudioFocus() {
        audioFocusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    handleAudioFocusChange(focusChange)
                }
                .build()
        } else null
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                { focusChange -> handleAudioFocusChange(focusChange) },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 恢复音量和播放
                exoPlayer.volume = 1.0f
                if (!exoPlayer.isPlaying) exoPlayer.play()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                exoPlayer.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                exoPlayer.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                exoPlayer.volume = 0.2f
            }
        }
    }

    // ======================== 播放控制 API ========================
    fun setTrackQueue(tracks: List<Track>, startIndex: Int = 0, albumArtUrl: String = "",
                      albumId: String = "", albumName: String = "", artistName: String = "",
                      releaseYear: String = "") {
        if (tracks.isEmpty()) return

        // 请求音频焦点
        if (!requestAudioFocus()) {
            println("DEBUG: Audio focus request denied")
        }

        trackQueue = tracks
        currentIndex = startIndex
        currentAlbumArtUrl = albumArtUrl
        currentAlbumId = albumId
        currentAlbumName = albumName
        currentArtistName = artistName
        currentReleaseYear = releaseYear

        val mediaItems = tracks.map { MediaItem.fromUri(it.streamUrl) }
        exoPlayer.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
        exoPlayer.prepare()
        exoPlayer.play()

        // === 标记需要初始化音效，但不立即执行 ===
        pendingAudioEffectsInitialization = true
        println("DEBUG: Audio effects initialization pending...")

        // === 发送切歌广播 ===
        sendBroadcast(Intent(ACTION_TRACK_CHANGED).setPackage(packageName))

        updateNotification()
        notifyAllListeners()
    }

    fun getCurrentAlbumCover(): String = currentAlbumArtUrl
    fun getCurrentAlbumId(): String = currentAlbumId
    fun getCurrentAlbumName(): String = currentAlbumName
    fun getCurrentArtistName(): String = currentArtistName
    fun getCurrentReleaseYear(): String = currentReleaseYear

    // 关键修复：使用播放器实时索引
    fun getCurrentTrack(): Track? {
        val playerIndex = exoPlayer.currentMediaItemIndex

        // 同步索引确保一致性
        if (playerIndex != currentIndex) {
            currentIndex = playerIndex
        }

        if (trackQueue.isEmpty() || currentIndex !in trackQueue.indices) return null

        val track = trackQueue[currentIndex]

        // 解析年份：如果 track.year <= 0，尝试将 currentReleaseYear (String) 转为 Int
        val fallbackYear = currentReleaseYear.toIntOrNull() ?: 0
        val finalYear = if (track.year > 0) track.year else fallbackYear

        // 返回一个完整的 Track，包括专辑名称、歌手、年份、封面
        return track.copy(
            album = track.album.ifEmpty { currentAlbumName },
            artist = track.artist.ifEmpty { currentArtistName },
            year = finalYear,
            coverUrl = track.coverUrl.ifEmpty { currentAlbumArtUrl }
        )
    }

    // 关键修复：直接返回播放器索引
    fun getCurrentIndex(): Int {
        return exoPlayer.currentMediaItemIndex
    }

    fun play() {
        if (!exoPlayer.isPlaying) {
            exoPlayer.play()
            updateNotification()
        }
    }

    fun pause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
            updateNotification()
        }
    }

    fun prevTrack() {
        if (trackQueue.isEmpty()) return
        val newIndex = if (currentIndex - 1 >= 0) currentIndex - 1 else trackQueue.size - 1
        playTrackAtIndex(newIndex)
    }

    fun nextTrack() {
        if (trackQueue.isEmpty()) return
        val newIndex = (currentIndex + 1) % trackQueue.size
        playTrackAtIndex(newIndex)
    }

    private fun playTrackAtIndex(index: Int) {
        if (index !in trackQueue.indices) return

        currentIndex = index
        println("DEBUG: Playing track at index $index: ${trackQueue.getOrNull(index)?.title}")

        // 直接seek到当前曲目，而不是重新设置整个队列
        if (exoPlayer.mediaItemCount > index && exoPlayer.currentMediaItemIndex != index) {
            exoPlayer.seekToDefaultPosition(index)
            exoPlayer.play()
        } else if (exoPlayer.mediaItemCount <= index || exoPlayer.currentMediaItemIndex != index) {
            // 如果队列不匹配，重新设置队列
            val mediaItems = trackQueue.map { MediaItem.fromUri(it.streamUrl) }
            exoPlayer.setMediaItems(mediaItems, index, C.TIME_UNSET)
            exoPlayer.prepare()
            exoPlayer.play()
        } else {
            // 已经在播放该曲目，确保正在播放
            if (!exoPlayer.isPlaying) {
                exoPlayer.play()
            }
        }

        // === 发送切歌广播 ===
        sendBroadcast(Intent(ACTION_TRACK_CHANGED).setPackage(packageName))

        updateNotification()
        notifyAllListeners()
    }

    fun isPlaying(): Boolean = exoPlayer.isPlaying
    fun getDuration(): Long = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
    fun getCurrentPosition(): Long = exoPlayer.currentPosition

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    // ======================== 监听器管理 ========================
    fun addPlayerListener(listener: Player.Listener) {
        listeners.add(listener)
        listener.onIsPlayingChanged(exoPlayer.isPlaying)
    }

    fun removePlayerListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    // 添加回调机制
    private val repeatModeListeners = mutableListOf<() -> Unit>()
    fun addRepeatModeListener(listener: () -> Unit) { repeatModeListeners.add(listener) }
    fun removeRepeatModeListener(listener: () -> Unit) { repeatModeListeners.remove(listener) }

    private fun notifyRepeatModeChanged() {
        // 先拷贝一份，保证回调里修改列表也不会报错
        val copy = ArrayList(repeatModeListeners)
        copy.forEach { it.invoke() }
    }

    // 添加 MEGA BASS 状态监听器
    private val megaBassListeners = mutableListOf<(Boolean) -> Unit>()

    fun addMegaBassListener(listener: (Boolean) -> Unit) {
        megaBassListeners.add(listener)
    }

    fun removeMegaBassListener(listener: (Boolean) -> Unit) {
        megaBassListeners.remove(listener)
    }

    private fun notifyMegaBassStateChanged() {
        val copy = ArrayList(megaBassListeners)
        copy.forEach { it.invoke(isMegaBassEnabled) }
    }

    fun isMegaBassEnabled(): Boolean = isMegaBassEnabled

    private fun getMainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
    }

    // ======================== 通知栏管理 ========================
    private fun startForegroundNotification() {
        updateNotification() // 直接调用更新通知的方法，显示完整播放器界面
    }

    private fun updateNotification() {
        println("DEBUG: updateNotification called")

        val currentTrack = getCurrentTrack()
        val trackTitle = currentTrack?.title ?: "SoukoPlayer"
        val trackArtist = currentTrack?.artist ?: "音乐播放器"

        // 构建通知内容
        val contentText = if (isMegaBassEnabled) {
            "$trackArtist - [MEGA BASS 开启]"
        } else {
            "$trackArtist - [MEGA BASS 关闭]"
        }

        val bigText = if (isMegaBassEnabled) {
            "$trackArtist\n\n" +
                    "🎵 MEGA BASS 已开启\n" +
                    "💡 使用提示：如果音响设备音量降低，请调低手机音量，调高音响音量"
        } else {
            "$trackArtist\n\n" +
                    "🎵 MEGA BASS 已关闭\n" +
                    "💡 可以点击应用内MEGA BASS按钮开启音效"
        }

        println("DEBUG: Notification content - Title: $trackTitle, Text: $contentText")

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "FROM_NOTIFICATION"
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val notification: Notification = NotificationCompat.Builder(this, "music_channel")
            .setContentTitle(trackTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.souko_player)
            .setContentIntent(pendingIntent) // 点击通知打开App
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(bigText)
                .setBigContentTitle(trackTitle))
            // 不添加任何按钮
            .build()

        // 启动前台服务
        startForeground(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "music_channel",
                "音乐播放",
                NotificationManager.IMPORTANCE_LOW  // 改为 LOW 避免打扰
            ).apply {
                description = "音乐播放通知"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // 明确设置不发出声音
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            println("DEBUG: Notification channel created with LOW importance")
        }
    }

    // ======================== 工具方法 ========================
    private fun safeNotifyAllListeners(notification: (Player.Listener) -> Unit) {
        println("DEBUG: Safe notifying ${listeners.size} listeners")

        val listenersCopy = ArrayList(listeners)
        listenersCopy.forEachIndexed { index, listener ->
            try {
                println("DEBUG: Safe notifying listener $index")
                notification(listener)
            } catch (e: Exception) {
                println("DEBUG: Failed to safe notify listener $index: ${e.message}")
            }
        }
    }

    private fun notifyAllListeners() {
        safeNotifyAllListeners { listener ->
            listener.onIsPlayingChanged(exoPlayer.isPlaying)
        }
    }

    fun forceRefreshUI() {
        println("DEBUG: Force refreshing UI")
        updateNotification()
        safeNotifyAllListeners { listener ->
            listener.onIsPlayingChanged(exoPlayer.isPlaying)
        }
    }

    // ======================== 服务清理与资源释放 ========================
    fun stopServiceAndCleanup() {

        // 1. 停止播放
        if (::exoPlayer.isInitialized && exoPlayer.isPlaying) {
            exoPlayer.stop()
        }

        // 2. 放弃音频焦点
        abandonAudioFocus()

        // 3. 停止前台服务并移除通知
        stopForeground(STOP_FOREGROUND_REMOVE)

        // 4. 清除通知
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(1)
        notificationManager.cancelAll()

        // 5. 清理数据
        clearAllData()

        // 6. 释放播放器
        if (::exoPlayer.isInitialized) {
            exoPlayer.release()
        }

        // 7. 停止服务
        stopSelf()
    }

    // ======================== 缓存清理功能 ========================
    fun clearAllData() {

        // 清除所有播放数据
        trackQueue = emptyList()
        currentIndex = 0
        currentAlbumArtUrl = ""
        currentAlbumId = ""
        currentAlbumName = ""
        currentArtistName = ""
        currentReleaseYear = ""

        // 清除ExoPlayer缓存
        if (::exoPlayer.isInitialized) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }
}