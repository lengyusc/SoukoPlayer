package com.souko.soukoplayer

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.souko.soukoplayer.models.Track
import com.souko.soukoplayer.network.NavidromeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlbumDetailFragment : Fragment() {

    private lateinit var rvTracks: RecyclerView
    private lateinit var ivAlbumArt: ImageView
    private lateinit var tvAlbumTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvReleaseYear: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var ivFullScreenAlbumArt: ImageView
    private var isFullScreenAlbumArtVisible = false
    private lateinit var fullScreenAlbumContainer: FrameLayout
    private lateinit var backCallback: OnBackPressedCallback

    private var trackList: List<Track> = emptyList()
    private var currentIndex = -1
    private var audioService: AudioService? = null
    private var isBound = false
    private var albumArtUrl: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var highlightTrackId: String? = null

    // 参数变量
    private var albumId: String = ""
    private var albumName: String = ""
    private var artistName: String = ""
    private var releaseYear: Int = 0

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            activity?.runOnUiThread { updateTrackHighlight() }
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun onPlaybackStateChanged(playbackState: Int) {
            activity?.runOnUiThread {
                updateTrackHighlight()
                (rvTracks.adapter as? TrackAdapter)?.notifyDataSetChanged()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            activity?.runOnUiThread { updateTrackHighlight() }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            audioService = (service as AudioService.AudioBinder).getService()
            isBound = true

            audioService?.removePlayerListener(playerListener)
            audioService?.addPlayerListener(playerListener)

            updateTrackHighlight()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            audioService?.removePlayerListener(playerListener)
            audioService = null
        }
    }

    fun getDisplayedAlbumId(): String {
        return albumId
    }

    companion object {
        fun newInstance(
            albumId: String,
            albumName: String,
            artistName: String,
            releaseYear: Int,
            albumArtUrl: String,
            trackId: String? = null
        ): AlbumDetailFragment {
            val fragment = AlbumDetailFragment()
            val args = Bundle().apply {
                putString("ALBUM_ID", albumId)
                putString("ALBUM_NAME", albumName)
                putString("ARTIST_NAME", artistName)
                putInt("RELEASE_YEAR", releaseYear)
                putString("ALBUM_ART_URL", albumArtUrl)
                putString("TRACK_ID", trackId)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            albumId = it.getString("ALBUM_ID", "")
            albumName = it.getString("ALBUM_NAME", "")
            artistName = it.getString("ARTIST_NAME", "")
            releaseYear = it.getInt("RELEASE_YEAR", 0)
            albumArtUrl = it.getString("ALBUM_ART_URL", "")
            highlightTrackId = it.getString("TRACK_ID")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_album_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        initAlbumArtClick(view)
        initBackPressedHandler()

        displayAlbumInfo()

        val serviceIntent = Intent(requireContext(), AudioService::class.java)
        requireContext().bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        handler.postDelayed({ loadAlbumData() }, 300)
    }

    private fun initViews(view: View) {
        rvTracks = view.findViewById(R.id.rvTracks)
        ivAlbumArt = view.findViewById(R.id.ivAlbumArt)
        tvAlbumTitle = view.findViewById(R.id.tvAlbumTitle)
        tvArtist = view.findViewById(R.id.tvArtist)
        tvReleaseYear = view.findViewById(R.id.tvReleaseYear)
        btnBack = view.findViewById(R.id.btnBack)

        rvTracks.layoutManager = LinearLayoutManager(requireContext())
        btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
    }

    private fun displayAlbumInfo() {
        tvAlbumTitle.text = albumName
        tvArtist.text = artistName
        tvReleaseYear.text = if (releaseYear > 0) releaseYear.toString() else ""

        if (albumArtUrl.isNotEmpty()) {
            Glide.with(this)
                .load(albumArtUrl)
                .placeholder(R.drawable.souko_player)
                .into(ivAlbumArt)
        }
    }

    private fun md5(input: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun loadAlbumData() {
        val finalAlbumId = albumId.ifEmpty { audioService?.getCurrentAlbumId() ?: return }

        lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                val serverUrl = ConfigManager.getServerUrl(ctx) ?: ""
                val username = ConfigManager.getUsername(ctx) ?: ""
                val password = ConfigManager.getPassword(ctx) ?: ""

                // 1. 获取歌曲列表
                val tracks = withContext(Dispatchers.IO) {
                    NavidromeApi.getTracks(finalAlbumId, serverUrl, username, password)
                }
                trackList = tracks
                setupTrackAdapter(tracks, finalAlbumId)

                // 2. 如果封面 URL 为空，且列表里有歌曲，优先取第一首歌曲的封面，否则手动拼 getCoverArt
                if (albumArtUrl.isEmpty()) {
                    val firstTrackCover = tracks.firstOrNull { it.coverUrl.isNotEmpty() }?.coverUrl
                    albumArtUrl = firstTrackCover ?: if (serverUrl.isNotEmpty() && finalAlbumId.isNotEmpty()) {
                        val salt = System.currentTimeMillis().toString()
                        val token = md5(password + salt)
                        "$serverUrl/rest/getCoverArt?id=$finalAlbumId&size=300&u=$username&t=$token&s=$salt&v=1.16.1&c=SoukoPlayer"
                    } else ""

                    if (albumArtUrl.isNotEmpty()) {
                        Glide.with(this@AlbumDetailFragment)
                            .load(albumArtUrl)
                            .placeholder(R.drawable.souko_player)
                            .into(ivAlbumArt)
                    }
                }

                // 3. 【核心修复】：如果 releaseYear 为 0，直接从返回的 trackList 中取第一首歌的 year 字段补全
                if (releaseYear <= 0 && tracks.isNotEmpty()) {
                    val trackWithYear = tracks.firstOrNull { it.year > 0 }
                    if (trackWithYear != null) {
                        releaseYear = trackWithYear.year
                        tvReleaseYear.text = releaseYear.toString()
                    }
                }

                // 4. 更新播放高亮与滚动状态
                if (isBound && audioService?.getCurrentAlbumId() == finalAlbumId) {
                    val serviceIndex = audioService?.getCurrentIndex() ?: -1
                    val isPlaying = audioService?.isPlaying() ?: false
                    (rvTracks.adapter as? TrackAdapter)?.updatePlaybackState(serviceIndex, isPlaying, finalAlbumId)
                    if (serviceIndex in trackList.indices) {
                        rvTracks.scrollToPosition(serviceIndex)
                        currentIndex = serviceIndex
                    }
                }

                highlightTrackId?.let { id ->
                    val index = trackList.indexOfFirst { it.id == id }
                    if (index >= 0) {
                        currentIndex = index
                        playCurrent(finalAlbumId)
                        highlightTrackId = null
                    }
                }
            } catch (_: Exception) {
                Toast.makeText(context, "获取歌曲失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playCurrent(albumId: String) {
        if (trackList.isEmpty() || currentIndex !in trackList.indices) return
        if (isBound) {
            audioService?.setTrackQueue(
                trackList,
                currentIndex,
                albumArtUrl,
                albumId,
                albumName,
                artistName,
                releaseYear.toString()
            )
        }
    }

    private fun setupTrackAdapter(tracks: List<Track>, albumId: String): TrackAdapter {
        val adapter = TrackAdapter(
            tracks = tracks,
            onClick = { track ->
                val newIndex = trackList.indexOf(track)
                if (newIndex in trackList.indices) {
                    currentIndex = newIndex
                    playCurrent(albumId)
                }
            },
            displayedAlbumId = albumId
        )
        rvTracks.adapter = adapter
        return adapter
    }

    private fun updateTrackHighlight() {
        if (isBound && audioService != null) {
            val idx = audioService!!.getCurrentIndex()
            val playing = audioService!!.isPlaying()
            val curAlbumId = audioService!!.getCurrentAlbumId()
            (rvTracks.adapter as? TrackAdapter)?.updatePlaybackState(idx, playing, curAlbumId)
        }
    }

    private fun initAlbumArtClick(view: View) {
        fullScreenAlbumContainer = view.findViewById(R.id.fullScreenAlbumContainer)
        ivFullScreenAlbumArt = view.findViewById(R.id.ivFullScreenAlbumArt)

        ivAlbumArt.setOnClickListener { showFullScreenAlbumArt() }
        fullScreenAlbumContainer.setOnClickListener { hideFullScreenAlbumArt() }
    }

    private fun showFullScreenAlbumArt() {
        if (isFullScreenAlbumArtVisible) return
        isFullScreenAlbumArtVisible = true

        Glide.with(this).load(albumArtUrl).placeholder(R.drawable.souko_player).into(ivFullScreenAlbumArt)
        fullScreenAlbumContainer.visibility = View.VISIBLE
        fullScreenAlbumContainer.alpha = 0f
        fullScreenAlbumContainer.animate().alpha(1f).setDuration(300).start()
    }

    private fun hideFullScreenAlbumArt() {
        if (!isFullScreenAlbumArtVisible) return
        isFullScreenAlbumArtVisible = false
        fullScreenAlbumContainer.animate().alpha(0f).setDuration(300).withEndAction {
            fullScreenAlbumContainer.visibility = View.GONE
        }.start()
    }

    private fun initBackPressedHandler() {
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullScreenAlbumArtVisible) {
                    hideFullScreenAlbumArt()
                } else {
                    isEnabled = false
                    parentFragmentManager.popBackStack()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isBound) {
            audioService?.removePlayerListener(playerListener)
            requireContext().unbindService(connection)
            isBound = false
        }
        handler.removeCallbacksAndMessages(null)
    }
}