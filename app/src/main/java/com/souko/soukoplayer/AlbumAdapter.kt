package com.souko.soukoplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.souko.soukoplayer.models.Album

class AlbumAdapter(
    private val albums: List<Album>,
    private val onItemClick: (Album) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder>() {

    private var itemSize: Int = 0

    fun setItemSize(size: Int) {
        if (itemSize != size) {
            itemSize = size
            notifyItemRangeChanged(0, itemCount, "item_size_changed")
        }
    }

    class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivCover: ImageView = itemView.findViewById(R.id.ivAlbumCover)
        val tvTitle: TextView = itemView.findViewById(R.id.tvAlbumTitle)
        val tvArtist: TextView = itemView.findViewById(R.id.tvAlbumArtist)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album, parent, false)
        return AlbumViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        val album = albums[position]
        holder.tvTitle.text = album.title
        holder.tvArtist.text = album.artist

        if (itemSize > 0) {
            val params = holder.ivCover.layoutParams
            params.width = itemSize
            params.height = itemSize
            holder.ivCover.layoutParams = params
        }

        holder.ivCover.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        Glide.with(holder.itemView)
            .load(album.coverUrl)
            .apply(RequestOptions().centerCrop())
            .into(holder.ivCover)

        holder.itemView.setOnClickListener {
            onItemClick(album)
        }
    }

    override fun getItemCount(): Int = albums.size
}
// 移除未使用的 dpToPx 函数