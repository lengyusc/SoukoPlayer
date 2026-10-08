package com.souko.soukoplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.souko.soukoplayer.models.Track
import java.util.Locale

class TrackAdapter(
    private val tracks: List<Track>,
    private val onClick: (Track) -> Unit,
    private val displayedAlbumId: String // 新增：当前显示的专辑ID
) : RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

    private var currentPlayingPosition = -1
    private var isCurrentlyPlaying = false
    private var playingAlbumId: String? = null // 当前正在播放的专辑ID

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvIndex: TextView = itemView.findViewById(R.id.tvTrackIndex)
        val tvTitle: TextView = itemView.findViewById(R.id.tvTrackTitle)
        val tvDuration: TextView = itemView.findViewById(R.id.tvTrackDuration)
        // 删除 btnPlay 的引用
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return ViewHolder(view)
    }

    // 修改 onBindViewHolder 中的高亮逻辑
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = tracks[position]

        // 修复1：使用字符串资源或直接设置文本
        holder.tvIndex.text = holder.itemView.context.getString(R.string.track_index, position + 1)

        // 修复2：移除多余的 Elvis 操作符
        holder.tvTitle.text = track.title

        holder.tvDuration.text = if ((track.duration ?: 0) > 0) {
            formatTime(track.duration ?: 0)
        } else {
            "--:--"
        }

        // 点击整行
        holder.itemView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onClick(tracks[adapterPos])
            }
        }

        // 高亮逻辑：只有正在播放的专辑匹配显示的专辑才高亮
        if (position == currentPlayingPosition && playingAlbumId == displayedAlbumId) {
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, R.color.colorPrimaryLight)
            )
            holder.tvTitle.setTextColor(
                ContextCompat.getColor(holder.itemView.context, R.color.colorPrimary)
            )
        } else {
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.transparent)
            )
            holder.tvTitle.setTextColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.black)
            )
        }
    }

    override fun getItemCount(): Int = tracks.size

    fun setCurrentPosition(position: Int) {
        val previousPosition = currentPlayingPosition
        currentPlayingPosition = position
        if (previousPosition != -1) notifyItemChanged(previousPosition)
        if (position != -1) notifyItemChanged(position)
    }

    fun updatePlaybackState(position: Int, isPlaying: Boolean, currentAlbumId: String?) {
        // 只有当前播放的专辑ID和Adapter绑定的专辑ID一致时才高亮
        if (currentAlbumId == displayedAlbumId) {
            val oldPosition = currentPlayingPosition
            currentPlayingPosition = position
            isCurrentlyPlaying = isPlaying
            playingAlbumId = currentAlbumId

            if (oldPosition != -1 && oldPosition != position) {
                notifyItemChanged(oldPosition)
            }
            if (position != -1) {
                notifyItemChanged(position)
            }
        } else {
            // 如果播放专辑和Adapter不一致，清除高亮
            val oldPosition = currentPlayingPosition
            currentPlayingPosition = -1
            isCurrentlyPlaying = false
            if (oldPosition != -1) notifyItemChanged(oldPosition)
        }
    }

    private fun formatTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        // 修复3：添加 Locale 参数
        return String.format(Locale.getDefault(), "%d:%02d", min, sec)
    }
}