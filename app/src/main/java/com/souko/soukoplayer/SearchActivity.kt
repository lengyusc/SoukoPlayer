package com.souko.soukoplayer

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.souko.soukoplayer.models.Album
import com.souko.soukoplayer.models.SearchItem
import com.souko.soukoplayer.models.Track
import com.souko.soukoplayer.network.NavArtist
import com.souko.soukoplayer.network.NavidromeApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class SearchActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var rvResults: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar

    private var searchJob: Job? = null
    private val searchAdapter = SearchAdapter { item ->
        when (item) {
            is SearchItem.AlbumItem -> openAlbum(item.album)
            is SearchItem.TrackItem -> playTrack(item.track)
            is SearchItem.ArtistItem -> showArtist(item.artist)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        initViews()
        setupSearch()
    }

    private fun initViews() {
        etSearch = findViewById(R.id.etSearch)
        btnBack = findViewById(R.id.btnBack)
        btnClear = findViewById(R.id.btnClear)
        rvResults = findViewById(R.id.rvResults)
        tvEmpty = findViewById(R.id.tvEmpty)
        progressBar = findViewById(R.id.progressBar)

        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = searchAdapter

        btnBack.setOnClickListener { finish() }
        btnClear.setOnClickListener {
            etSearch.text.clear()
            searchAdapter.submitList(emptyList())
            tvEmpty.visibility = android.view.View.VISIBLE
        }

        etSearch.requestFocus()
    }

    private fun setupSearch() {
        etSearch.setOnEditorActionListener { _, _, _ ->
            performSearch()
            true
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(500.milliseconds) // 防抖
                    if ((s?.length ?: 0) >= 2) {
                        performSearch()
                    } else {
                        searchAdapter.submitList(emptyList())
                        tvEmpty.visibility = android.view.View.VISIBLE
                    }
                }
            }
        })
    }

    private fun performSearch() {
        val query = etSearch.text.toString().trim()
        if (query.length < 2) return

        progressBar.visibility = android.view.View.VISIBLE
        tvEmpty.visibility = android.view.View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = ConfigManager.getServerUrl(this@SearchActivity) ?: ""
                val username = ConfigManager.getUsername(this@SearchActivity) ?: ""
                val password = ConfigManager.getPassword(this@SearchActivity) ?: ""

                if (serverUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        tvEmpty.text = getString(R.string.configure_server_first)
                        tvEmpty.visibility = android.view.View.VISIBLE
                        progressBar.visibility = android.view.View.GONE
                    }
                    return@launch
                }

                // 调用服务器搜索
                val result = NavidromeApi.search(query, serverUrl, username, password)

                withContext(Dispatchers.Main) {
                    val searchItems = mutableListOf<SearchItem>()

                    // 直接使用服务器返回的结果，不再做二次过滤
                    result.albums.forEach { searchItems.add(SearchItem.AlbumItem(it)) }
                    result.tracks.forEach { searchItems.add(SearchItem.TrackItem(it)) }
                    result.artists.forEach { searchItems.add(SearchItem.ArtistItem(it)) }

                    searchAdapter.submitList(searchItems)
                    tvEmpty.visibility = if (searchItems.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                    progressBar.visibility = android.view.View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    tvEmpty.text = getString(R.string.search_failed, e.message ?: getString(R.string.unknown_error))
                    tvEmpty.visibility = android.view.View.VISIBLE
                    progressBar.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun openAlbum(album: Album) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_OPEN_ALBUM_ID", album.id)
            putExtra("EXTRA_ALBUM_NAME", album.title)
            putExtra("EXTRA_ARTIST_NAME", album.artist)
            putExtra("EXTRA_ALBUM_ART_URL", album.coverUrl)
            putExtra("EXTRA_RELEASE_YEAR", album.year) // 移除多余的 .toString()
        }
        startActivity(intent)
        finish()
    }

    private fun playTrack(track: Track) {
        Toast.makeText(this, getString(R.string.playing_track, track.title), Toast.LENGTH_SHORT).show()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_OPEN_ALBUM_ID", track.albumId)
            putExtra("EXTRA_TRACK_ID", track.id)
            putExtra("EXTRA_ALBUM_NAME", track.album)
            putExtra("EXTRA_ARTIST_NAME", track.artist)
            putExtra("EXTRA_ALBUM_ART_URL", track.coverUrl)
            putExtra("EXTRA_RELEASE_YEAR", track.year) // 移除多余的 .toString()
        }
        startActivity(intent)
        finish()
    }

    private fun showArtist(artist: NavArtist) {
        Toast.makeText(this, getString(R.string.artist_name, artist.name), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
    }
}