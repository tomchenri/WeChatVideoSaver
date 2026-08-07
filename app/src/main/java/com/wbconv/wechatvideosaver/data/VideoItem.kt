package com.wbconv.wechatvideosaver.data

import android.net.Uri

/**
 * 视频数据模型（v2：以 contentUri 为主，兼容 MediaStore 与 SAF 来源）
 */
data class VideoItem(
    val id: Long = 0,
    val contentUri: Uri,          // 视频内容 URI（播放/分享/读取都用它）
    val name: String,
    val size: Long,               // 字节
    val lastModified: Long,       // 毫秒时间戳
    val durationMs: Long = 0L,    // 时长（毫秒）
    val bucketName: String = "",  // 来源目录名（WeiXin / Camera / Download...）
    val filePath: String? = null, // 真实路径（可能为 null）
    val fromSaf: Boolean = false  // 是否来自 SAF 授权目录（微信缓存）
) {
    val sizeFormatted: String
        get() = when {
            size < 1024 -> "${size}B"
            size < 1024 * 1024 -> String.format("%.1fKB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.1fMB", size / (1024.0 * 1024))
            else -> String.format("%.2fGB", size / (1024.0 * 1024 * 1024))
        }

    val durationFormatted: String
        get() {
            if (durationMs <= 0) return ""
            val totalSec = durationMs / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            return String.format("%d:%02d", m, s)
        }

    val dateFormatted: String
        get() {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            return sdf.format(java.util.Date(lastModified))
        }

    /** 是否疑似微信来源 */
    val isWeChat: Boolean
        get() {
            val b = bucketName.lowercase()
            val p = (filePath ?: "").lowercase()
            return fromSaf || b.contains("weixin") || b.contains("wechat") ||
                    p.contains("tencent") || p.contains("micromsg") || p.contains("weixin")
        }
}
