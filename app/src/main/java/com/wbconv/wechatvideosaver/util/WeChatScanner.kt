package com.wbconv.wechatvideosaver.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.wbconv.wechatvideosaver.data.VideoItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 微信视频扫描器
 *
 * 微信视频存储路径（OPPO/vivo 等 Android 设备）：
 * 1. /sdcard/tencent/MicroMsg/<user_hash>/video/        — 老版本微信
 * 2. /sdcard/Android/data/com.tencent.mm/MicroMsg/<user_hash>/video/  — 新版本微信
 * 3. /sdcard/Tencent/MicroMsg/<user_hash>/video/         — 部分设备大小写不同
 *
 * user_hash 是用户的 32 位 MD5 标识，每个微信号不同。
 */
object WeChatScanner {

    private const val TAG = "WeChatScanner"

    // 微信可能存在的视频目录（按优先级排列）
    private val weChatVideoPaths = listOf(
        // 新版微信（Android 11+ Scoped Storage 可访问部分）
        "/sdcard/Android/data/com.tencent.mm/MicroMsg",
        "/sdcard/tencent/MicroMsg",
        "/sdcard/Tencent/MicroMsg",
        // OPPO 特有路径
        "/sdcard/Android/data/com.tencent.mm",
        // 内部存储变体
        "/storage/emulated/0/tencent/MicroMsg",
        "/storage/emulated/0/Tencent/MicroMsg",
        "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg",
    )

    // 视频文件扩展名
    private val videoExtensions = setOf("mp4", "mov", "avi", "mkv", "flv", "3gp")

    /**
     * 扫描所有微信视频目录
     */
    fun scanAllWeChatVideos(): List<VideoItem> {
        val results = mutableListOf<VideoItem>()

        for (basePath in weChatVideoPaths) {
            val dir = File(basePath)
            if (!dir.exists() || !dir.isDirectory) {
                Log.d(TAG, "路径不存在: $basePath")
                continue
            }
            Log.d(TAG, "扫描目录: $basePath")
            scanDirectory(dir, results)
        }

        // 去重（按文件路径）
        return results.distinctBy { it.file.absolutePath }
            .sortedByDescending { it.lastModified }
    }

    /**
     * 递归扫描目录
     */
    private fun scanDirectory(dir: File, results: MutableList<VideoItem>) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                // 跳过过深的目录和明显无关的目录
                val name = file.name.lowercase()
                if (name in setOf("cache", "emoji", "voice2", "image2", "image", "voice")) {
                    continue
                }
                scanDirectory(file, results)
            } else {
                val ext = file.extension.lowercase()
                if (ext in videoExtensions && file.length() > 100 * 1024) {
                    // 过滤掉小于 100KB 的文件（通常不是有效视频）
                    results.add(
                        VideoItem(
                            file = file,
                            name = file.name,
                            size = file.length(),
                            lastModified = file.lastModified()
                        )
                    )
                }
            }
        }
    }

    /**
     * 通过 MediaStore 查询微信相关视频
     * 某些设备上微信视频也会出现在 MediaStore 中
     */
    fun scanViaMediaStore(context: Context): List<VideoItem> {
        val results = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION
        )

        // 查询包含 tencent/MicroMsg 路径的视频
        val selection = "${MediaStore.Video.Media.DATA} LIKE ?"
        val selectionArgs = arrayOf("%tencent/MicroMsg%")

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataColumn)
                    val name = cursor.getString(nameColumn)
                    val size = cursor.getLong(sizeColumn)
                    val date = cursor.getLong(dateColumn) * 1000
                    val duration = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L

                    val file = File(path)
                    if (file.exists() && size > 100 * 1024) {
                        results.add(VideoItem(file, name, size, date, duration))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore 查询失败", e)
        }

        return results
    }

    /**
     * 合并文件系统扫描和 MediaStore 查询结果
     */
    fun scan(context: Context): List<VideoItem> {
        val fsResults = scanAllWeChatVideos()
        val msResults = scanViaMediaStore(context)

        // 合并去重
        val merged = (fsResults + msResults).distinctBy { it.file.absolutePath }
        return merged.sortedByDescending { it.lastModified }
    }

    /**
     * 保存视频到相册（Movies 目录）
     *
     * Android 10+ 使用 MediaStore API，无需 WRITE_EXTERNAL_STORAGE 权限
     * Android 9 及以下直接复制文件到公共目录
     */
    fun saveToGallery(context: Context, videoFile: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, videoFile)
            } else {
                saveViaFileCopy(videoFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存失败: ${videoFile.name}", e)
            false
        }
    }

    /**
     * Android 10+ 通过 MediaStore 保存
     */
    private fun saveViaMediaStore(context: Context, videoFile: File): Boolean {
        val resolver = context.contentResolver
        val fileName = "WeChat_${System.currentTimeMillis()}_${videoFile.name}"

        val values = android.content.ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/WeChatSaved")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return false

        resolver.openOutputStream(uri)?.use { outputStream ->
            FileInputStream(videoFile).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: return false

        // 标记为已完成
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        Log.i(TAG, "视频已保存到相册: $fileName")
        return true
    }

    /**
     * Android 9 及以下直接复制文件
     */
    private fun saveViaFileCopy(videoFile: File): Boolean {
        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "WeChatSaved"
        )
        if (!moviesDir.exists()) moviesDir.mkdirs()

        val destFile = File(moviesDir, "WeChat_${System.currentTimeMillis()}_${videoFile.name}")
        FileInputStream(videoFile).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        return destFile.exists()
    }

    /**
     * 批量保存
     */
    fun batchSaveToGallery(context: Context, videoFiles: List<File>): Pair<Int, Int> {
        var success = 0
        var failed = 0
        for (file in videoFiles) {
            if (saveToGallery(context, file)) success++ else failed++
        }
        return success to failed
    }
}
