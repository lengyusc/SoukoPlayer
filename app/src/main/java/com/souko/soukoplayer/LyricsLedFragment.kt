package com.souko.soukoplayer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.souko.soukoplayer.models.LrcRow
import com.souko.soukoplayer.models.Track
import com.souko.soukoplayer.network.NavidromeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LyricsLedFragment : Fragment() {

    private val TAG = "LyricsLedFragment"

    private lateinit var ivBigCover: ImageView
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvArtistName: TextView
    private lateinit var ledSpectrumView: LEDSpectrumView
    private lateinit var rvLyrics: RecyclerView

    private val lyricsAdapter = LyricsAdapter()
    private var lrcRows = listOf<LrcRow>()

    private var audioService: AudioService? = null
    private var isBound = false
    private var visualizer: Visualizer? = null

    // 监听 AudioService 的切歌广播
    private val trackChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioService.ACTION_TRACK_CHANGED) {
                Log.d(TAG, "收到切歌广播，正在刷新歌词与歌曲信息...")
                if (isBound) {
                    refreshAllData()
                }
            }
        }
    }

    // 注册动态权限请求回调
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "用户允许了 RECORD_AUDIO 权限，准备初始化 Visualizer")
            val sessionId = audioService?.getAudioSessionId() ?: 0
            initLedVisualizer(sessionId)
        } else {
            Log.w(TAG, "用户拒绝了 RECORD_AUDIO 权限，LED 频谱无法展示")
        }
    }

    // 歌词高亮滚动 Handler
    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateLyricsPosition()
            handler.postDelayed(this, 300)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as AudioService.AudioBinder
            audioService = binder.getService()
            isBound = true

            // 服务连接成功后立即刷新页面数据
            refreshAllData()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            audioService = null
        }
    }

    companion object {
        fun newInstance(): LyricsLedFragment = LyricsLedFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_lyrics_led, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ivBigCover = view.findViewById(R.id.ivBigCover)
        tvTrackTitle = view.findViewById(R.id.tvTrackTitle)
        tvArtistName = view.findViewById(R.id.tvArtistName)
        ledSpectrumView = view.findViewById(R.id.ledSpectrumView)
        rvLyrics = view.findViewById(R.id.rvLyrics)

        rvLyrics.layoutManager = LinearLayoutManager(requireContext())
        rvLyrics.adapter = lyricsAdapter

        // 点击左上角大封面直接返回上级页面
        ivBigCover.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        val serviceIntent = Intent(requireContext(), AudioService::class.java)
        requireContext().bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()

        // 1. 注册切歌广播监听
        val filter = IntentFilter(AudioService.ACTION_TRACK_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(trackChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                requireContext(),
                trackChangeReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        // 2. 每次 Fragment 可见时刷新
        if (isBound) {
            refreshAllData()
        }
    }

    override fun onPause() {
        super.onPause()
        // 取消广播注册与频谱释放
        try {
            requireContext().unregisterReceiver(trackChangeReceiver)
        } catch (_: Exception) {}
        releaseVisualizer()
    }

    private fun refreshAllData() {
        updateTrackUI()
        loadLyrics()

        val sessionId = audioService?.getAudioSessionId() ?: 0
        Log.d(TAG, "当前 AudioSessionId: $sessionId")
        initLedVisualizer(sessionId)

        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    private fun updateTrackUI() {
        val currentTrack: Track = audioService?.getCurrentTrack() ?: return
        tvTrackTitle.text = currentTrack.title
        tvArtistName.text = currentTrack.artist

        val coverUrl = audioService?.getCurrentAlbumCover() ?: ""
        if (coverUrl.isNotEmpty()) {
            Glide.with(this)
                .load(coverUrl)
                .placeholder(R.drawable.souko_player)
                .into(ivBigCover)
        }
    }

    private fun loadLyrics() {
        val currentTrack = audioService?.getCurrentTrack() ?: run {
            Log.w(TAG, "loadLyrics: currentTrack 为空")
            return
        }

        Log.d(TAG, "正在为歌曲联网拉取歌词: ${currentTrack.title} - ${currentTrack.artist}")

        // 先重置当前歌词界面，避免上一首歌曲的歌词残留在界面上
        lrcRows = emptyList()
        lyricsAdapter.setLyrics(emptyList())

        lifecycleScope.launch(Dispatchers.IO) {
            val context = context ?: return@launch

            val serverUrl = ConfigManager.getServerUrl(context) ?: ""
            val username = ConfigManager.getUsername(context) ?: ""
            val password = ConfigManager.getPassword(context) ?: ""

            val rawLrc = NavidromeApi.getLyrics(
                trackId = currentTrack.id,
                artist = currentTrack.artist,
                title = currentTrack.title,
                serverUrl = serverUrl,
                username = username,
                password = password
            )

            Log.d(TAG, "联网拉取到的最终歌词内容长度: ${rawLrc.length}")

            withContext(Dispatchers.Main) {
                if (isAdded) {
                    if (rawLrc.isNotEmpty()) {
                        lrcRows = LrcRow.parseLrc(rawLrc)
                        lyricsAdapter.setLyrics(lrcRows)
                        Log.d(TAG, "歌词成功加载并渲染，共 ${lrcRows.size} 行")
                    } else {
                        Log.w(TAG, "未能联网获取到该歌曲的歌词")
                        lyricsAdapter.setLyrics(emptyList())
                    }
                }
            }
        }
    }

    private fun updateLyricsPosition() {
        val currentPosition = audioService?.getCurrentPosition() ?: return
        if (lrcRows.isEmpty()) return

        var activeIndex = 0
        for (i in lrcRows.indices) {
            if (currentPosition >= lrcRows[i].time) {
                activeIndex = i
            } else {
                break
            }
        }

        if (lyricsAdapter.updateActiveIndex(activeIndex)) {
            rvLyrics.smoothScrollToPosition(activeIndex)
        }
    }

    private fun initLedVisualizer(audioSessionId: Int) {
        if (audioSessionId <= 0) {
            Log.w(TAG, "AudioSessionId 无效 ($audioSessionId)，无法初始化 Visualizer")
            return
        }

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "缺少 RECORD_AUDIO 录音权限，发起动态申请...")
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        releaseVisualizer()

        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {}

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            if (fft != null && isResumed) {
                                ledSpectrumView.updateFftData(fft)
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true
                )
                enabled = true
            }
            Log.d(TAG, "Visualizer 频谱初始化成功！")
        } catch (e: Exception) {
            Log.e(TAG, "Visualizer 初始化失败", e)
        }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
            ledSpectrumView.clear()
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(progressRunnable)
        releaseVisualizer()
        if (isBound) {
            requireContext().unbindService(connection)
            isBound = false
        }
    }
}