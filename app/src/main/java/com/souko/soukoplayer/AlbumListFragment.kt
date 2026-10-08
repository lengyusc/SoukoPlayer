package com.souko.soukoplayer

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.souko.soukoplayer.models.Album
import com.souko.soukoplayer.network.NavidromeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlbumListFragment : Fragment() {

    private lateinit var rvAlbums: RecyclerView
    private lateinit var tvAlbumCount: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var btnSettings: ImageButton
    private lateinit var topBar: RelativeLayout

    private var lastClickTime: Long = 0

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == AppCompatActivity.RESULT_OK) {
                loadAlbums()
            }
        }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_album_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        topBar = view.findViewById(R.id.topBar)
        rvAlbums = view.findViewById(R.id.rvAlbums)
        tvAlbumCount = view.findViewById(R.id.tvAlbumCount)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        btnSettings = view.findViewById(R.id.btnSettings)

        rvAlbums.layoutManager = GridLayoutManager(requireContext(), 3)

        btnSettings.setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            settingsLauncher.launch(intent)
        }

        val btnSearch: ImageButton = view.findViewById(R.id.btnSearch)
        btnSearch.setOnClickListener {
            val intent = Intent(requireContext(), SearchActivity::class.java)
            startActivity(intent)
        }

        swipeRefresh.setOnRefreshListener { loadAlbums() }

        setupDoubleClickToTop()
        loadAlbums()
    }

    private fun setupDoubleClickToTop() {
        topBar.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime < 500) {
                if (!isAtTop()) {
                    rvAlbums.smoothScrollToPosition(0)
                }
            }
            lastClickTime = currentTime
        }
    }

    private fun isAtTop(): Boolean {
        val layoutManager = rvAlbums.layoutManager as? LinearLayoutManager
        return layoutManager?.findFirstVisibleItemPosition() == 0
    }

    private fun loadAlbums() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val context = context ?: return@launch
                val serverUrl = ConfigManager.getServerUrl(context)
                val username = ConfigManager.getUsername(context)
                val password = ConfigManager.getPassword(context)

                if (serverUrl.isNullOrEmpty() || username.isNullOrEmpty() || password.isNullOrEmpty()) {
                    tvAlbumCount.text = "请先在设置中配置服务器"
                    rvAlbums.adapter = AlbumAdapter(emptyList()) {}
                    swipeRefresh.isRefreshing = false
                    return@launch
                }

                val albums: List<Album> = withContext(Dispatchers.IO) {
                    NavidromeApi.getAlbums(serverUrl, username, password)
                }

                tvAlbumCount.text = resources.getString(R.string.album_count, albums.size)

                val albumAdapter = AlbumAdapter(albums) { album ->
                    // 将 album.year（String 类型）转为 Int 类型传入
                    val detailFragment = AlbumDetailFragment.newInstance(
                        albumId = album.id,
                        albumName = album.title,
                        artistName = album.artist,
                        releaseYear = album.year.toIntOrNull() ?: 0,
                        albumArtUrl = album.coverUrl
                    )

                    parentFragmentManager.beginTransaction()
                        .setCustomAnimations(
                            R.anim.slide_in_right,
                            R.anim.slide_out_left,
                            R.anim.slide_in_left,
                            R.anim.slide_out_right
                        )
                        .replace(R.id.fragment_container, detailFragment)
                        .addToBackStack(null) // 允许按返回键退回专辑列表
                        .commit()
                }

                val screenWidth = resources.displayMetrics.widthPixels
                val orientation = resources.configuration.orientation
                val spanCount = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 6 else 3
                val marginPx = 6.dpToPx()
                val itemSizePx = (screenWidth - marginPx * 2 * spanCount) / spanCount

                albumAdapter.setItemSize(itemSizePx)
                rvAlbums.layoutManager = GridLayoutManager(requireContext(), spanCount)
                rvAlbums.adapter = albumAdapter

            } catch (e: Exception) {
                e.printStackTrace()
                tvAlbumCount.text = e.message ?: "加载专辑失败"
                rvAlbums.adapter = AlbumAdapter(emptyList()) {}
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        val spanCount = if (isLandscape) 6 else 3
        (rvAlbums.layoutManager as? GridLayoutManager)?.spanCount = spanCount

        val albumAdapter = rvAlbums.adapter as? AlbumAdapter
        if (albumAdapter != null) {
            val screenWidth = resources.displayMetrics.widthPixels
            val marginPx = 6.dpToPx()
            val itemSizePx = (screenWidth - marginPx * 2 * spanCount) / spanCount
            albumAdapter.setItemSize(itemSizePx)
            albumAdapter.notifyItemRangeChanged(0, albumAdapter.itemCount)
        }
    }
}