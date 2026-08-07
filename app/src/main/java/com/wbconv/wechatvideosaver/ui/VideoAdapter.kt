package com.wbconv.wechatvideosaver.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wbconv.wechatvideosaver.R
import com.wbconv.wechatvideosaver.data.VideoItem
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 视频列表适配器 v2：支持缩略图、时长、来源标签、多选
 */
class VideoAdapter(
    private val items: MutableList<VideoItem>,
    private val onItemClick: (VideoItem) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    val selectedItems = mutableSetOf<UriKey>()
    private val thumbCache = ConcurrentHashMap<String, Bitmap?>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var multiSelectMode = false
        private set

    data class UriKey(val s: String) {
        companion object { fun of(item: VideoItem) = UriKey(item.contentUri.toString()) }
    }

    fun enterMultiSelect(item: VideoItem) {
        multiSelectMode = true
        selectedItems.add(UriKey.of(item))
        notifyDataSetChanged()
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedItems.clear()
        notifyDataSetChanged()
    }

    fun selectAll() {
        items.forEach { selectedItems.add(UriKey.of(it)) }
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<VideoItem> =
        items.filter { selectedItems.contains(UriKey.of(it)) }

    fun updateItems(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        selectedItems.clear()
        multiSelectMode = false
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.ivThumb)
        val name: TextView = v.findViewById(R.id.tvName)
        val size: TextView = v.findViewById(R.id.tvSize)
        val date: TextView = v.findViewById(R.id.tvDate)
        val duration: TextView = v.findViewById(R.id.tvDuration)
        val source: TextView = v.findViewById(R.id.tvSource)
        val cb: CheckBox = v.findViewById(R.id.cbSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        val key = UriKey.of(item)

        h.name.text = item.name
        h.size.text = item.sizeFormatted
        h.date.text = item.dateFormatted
        h.duration.text = item.durationFormatted
        h.duration.visibility = if (item.durationMs > 0) View.VISIBLE else View.GONE

        // 来源标签
        h.source.text = when {
            item.fromSaf -> "微信缓存"
            item.isWeChat -> "微信"
            item.bucketName.isNotBlank() -> item.bucketName
            else -> "本地"
        }
        h.source.setBackgroundColor(
            if (item.isWeChat) 0xFF07C160.toInt() else 0xFF999999.toInt()
        )

        // 缩略图
        h.thumb.setImageResource(android.R.drawable.ic_media_play)
        h.thumb.tag = key.s
        val cached = thumbCache[key.s]
        if (cached != null) {
            h.thumb.setImageBitmap(cached)
        } else {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { loadThumb(h.itemView.context, item) }
                thumbCache[key.s] = bmp
                if (bmp != null && h.thumb.tag == key.s) h.thumb.setImageBitmap(bmp)
            }
        }

        // 多选
        h.cb.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
        h.cb.setOnCheckedChangeListener(null)
        h.cb.isChecked = selectedItems.contains(key)
        h.cb.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedItems.add(key) else selectedItems.remove(key)
            onSelectionChanged()
        }

        h.itemView.setOnClickListener {
            if (multiSelectMode) h.cb.isChecked = !h.cb.isChecked else onItemClick(item)
        }
        h.itemView.setOnLongClickListener {
            if (!multiSelectMode) {
                enterMultiSelect(item)
                onSelectionChanged()
            }
            true
        }
    }

    private fun loadThumb(context: Context, item: VideoItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(item.contentUri, Size(160, 160), null)
            } else {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, item.contentUri)
                val bmp = retriever.getFrameAtTime(0)
                retriever.release()
                bmp?.let { Bitmap.createScaledBitmap(it, 160, 160 * it.height / it.width, true) }
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun getItemCount() = items.size

    fun destroy() = scope.cancel()
}
