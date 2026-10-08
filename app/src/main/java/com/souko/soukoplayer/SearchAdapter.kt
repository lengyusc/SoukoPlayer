package com.souko.soukoplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.souko.soukoplayer.models.Album
import com.souko.soukoplayer.models.SearchItem
import com.souko.soukoplayer.models.Track
import com.souko.soukoplayer.network.NavArtist

class SearchAdapter(
    private val onItemClick: (SearchItem) -> Unit
) : ListAdapter<SearchItem, RecyclerView.ViewHolder>(SearchItemDiffCallback) {

    companion object {
        private const val TYPE_ALBUM = 0
        private const val TYPE_TRACK = 1
        private const val TYPE_ARTIST = 2

        private object SearchItemDiffCallback : DiffUtil.ItemCallback<SearchItem>() {
            override fun areItemsTheSame(oldItem: SearchItem, newItem: SearchItem): Boolean {
                return when (oldItem) {
                    is SearchItem.AlbumItem if newItem is SearchItem.AlbumItem ->
                        oldItem.album.id == newItem.album.id

                    is SearchItem.TrackItem if newItem is SearchItem.TrackItem ->
                        oldItem.track.id == newItem.track.id

                    is SearchItem.ArtistItem if newItem is SearchItem.ArtistItem ->
                        oldItem.artist.id == newItem.artist.id

                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: SearchItem, newItem: SearchItem): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SearchItem.AlbumItem -> TYPE_ALBUM
            is SearchItem.TrackItem -> TYPE_TRACK
            is SearchItem.ArtistItem -> TYPE_ARTIST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_ALBUM -> AlbumViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_search_album, parent, false)
            )
            TYPE_TRACK -> TrackViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_search_track, parent, false)
            )
            TYPE_ARTIST -> ArtistViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_search_artist, parent, false)
            )
            else -> throw IllegalArgumentException("Unknown view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (item) {
            is SearchItem.AlbumItem -> (holder as AlbumViewHolder).bind(item.album)
            is SearchItem.TrackItem -> (holder as TrackViewHolder).bind(item.track)
            is SearchItem.ArtistItem -> (holder as ArtistViewHolder).bind(item.artist)
        }

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    // 原有的 ViewHolder 类保持不变
    class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)

        fun bind(album: Album) {
            tvTitle.text = album.title
            tvArtist.text = album.artist

            if (album.coverUrl.isNotEmpty()) {
                Glide.with(itemView.context)
                    .load(album.coverUrl)
                    .placeholder(R.drawable.souko_player)
                    .into(ivCover)
            } else {
                ivCover.setImageResource(R.drawable.souko_player)
            }
        }
    }

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val tvAlbum: TextView = itemView.findViewById(R.id.tvAlbum)

        fun bind(track: Track) {
            tvTitle.text = track.title
            tvArtist.text = track.artist
            tvAlbum.text = track.album
        }
    }

    class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)

        fun bind(artist: NavArtist) {
            tvName.text = artist.name

            if (!artist.coverUrl.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(artist.coverUrl)
                    .placeholder(R.drawable.souko_player)
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(R.drawable.souko_player)
            }
        }
    }
}