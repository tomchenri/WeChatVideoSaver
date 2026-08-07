package com.wbconv.wechatvideosaver.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wbconv.wechatvideosaver.R
import com.wbconv.wechatvideosaver.data.VideoItem

/**
 * 视频列表适配器
 */
class VideoAdapter(
    private val items: MutableList<VideoItem>,
    private val onItemClick: (VideoItem) -> Unit,
    private val onItemLongClick: (VideoItem) -> Unit,
    private val onCheckChanged: () -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    // 选中状态
    val selectedItems = mutableSetOf<String>() // 存文件路径

    var multiSelectMode = false
        private set

    fun toggleMultiSelect() {
        multiSelectMode = !multiSelectMode
        if (!multiSelectMode) selectedItems.clear()
        notifyDataSetChanged()
    }

    fun exitMultiSelect() {
        if (multiSelectMode) {
            multiSelectMode = false
            selectedItems.clear()
            notifyDataSetChanged()
        }
    }

    fun selectAll() {
        selectedItems.clear()
        items.forEach { selectedItems.add(it.file.absolutePath) }
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<VideoItem> {
        return items.filter { selectedItems.contains(it.file.absolutePath) }
    }

    fun updateItems(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        selectedItems.clear()
        notifyDataSetChanged()
    }

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvName)
        val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvSize.text = item.sizeFormatted
        holder.tvDate.text = item.dateFormatted

        // 多选模式显示 Checkbox
        holder.cbSelect.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = selectedItems.contains(item.file.absolutePath)
        holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
            val path = item.file.absolutePath
            if (isChecked) selectedItems.add(path) else selectedItems.remove(path)
            onCheckChanged()
        }

        // 点击事件
        holder.itemView.setOnClickListener {
            if (multiSelectMode) {
                holder.cbSelect.isChecked = !holder.cbSelect.isChecked
            } else {
                onItemClick(item)
            }
        }

        // 长按进入多选
        holder.itemView.setOnLongClickListener {
            if (!multiSelectMode) {
                multiSelectMode = true
                selectedItems.add(item.file.absolutePath)
                notifyDataSetChanged()
            }
            onItemLongClick(item)
            true
        }
    }

    override fun getItemCount() = items.size
}
