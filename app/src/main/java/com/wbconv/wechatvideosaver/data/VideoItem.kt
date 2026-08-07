package com.wbconv.wechatvideosaver.data

import java.io.File

/**
 * 视频文件数据模型
 */
data class VideoItem(
    val file: File,
    val name: String,
    val size: Long,        // 字节
    val lastModified: Long, // 时间戳
    val durationMs: Long = 0L // 视频时长（毫秒），0 表示未获取
) {
    val sizeFormatted: String
        get() = when {
            size < 1024 -> "${size}B"
            size < 1024 * 1024 -> String.format("%.1fKB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.1fMB", size / (1024.0 * 1024))
            else -> String.format("%.2fGB", size / (1024.0 * 1024 * 1024))
        }

    val dateFormatted: String
        get() {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            return sdf.format(java.util.Date(lastModified))
        }
}
