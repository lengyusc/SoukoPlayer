package com.souko.soukoplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView
import com.souko.soukoplayer.models.LrcRow

class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private var lyrics = listOf<LrcRow>()
    private var activeIndex = -1

    fun setLyrics(list: List<LrcRow>) {
        val oldSize = lyrics.size
        this.lyrics = list
        this.activeIndex = -1
        if (oldSize > 0) {
            notifyItemRangeRemoved(0, oldSize)
        }
        if (list.isNotEmpty()) {
            notifyItemRangeInserted(0, list.size)
        }
    }

    fun updateActiveIndex(newIndex: Int): Boolean {
        if (newIndex == activeIndex || newIndex !in lyrics.indices) return false
        val oldIndex = activeIndex
        activeIndex = newIndex
        if (oldIndex != -1) notifyItemChanged(oldIndex)
        notifyItemChanged(activeIndex)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = lyrics[position]
        holder.tvContent.text = row.content
        holder.tvContent.textSize = if (position == activeIndex) 18f else 15f
        holder.tvContent.setTextColor(
            if (position == activeIndex) "#FFD700".toColorInt() else "#888888".toColorInt()
        )
        holder.tvContent.textAlignment = View.TEXT_ALIGNMENT_CENTER
    }

    override fun getItemCount(): Int = lyrics.size

    // 补全缺失的 ViewHolder 内部类
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvContent: TextView = itemView.findViewById(android.R.id.text1)
    }
}