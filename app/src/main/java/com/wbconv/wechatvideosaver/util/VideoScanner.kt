package com.wbconv.wechatvideosaver.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.wbconv.wechatvideosaver.data.VideoItem

/**
 * 视频扫描器 v2
 *
 * 三种来源：
 * 1. MediaStore 全盘扫描 — 相册里所有视频（含微信"保存视频"到相册的）
 * 2. SAF 授权目录扫描 — 用户授权 Android/data/com.tencent.mm 后直读微信缓存
 * 3. 保存 / 分享 / 播放 工具方法
 */
object VideoScanner {

    private const val TAG = "VideoScanner"
    private val videoExtensions = setOf("mp4", "mov", "avi", "mkv", "flv", "3gp", "webm", "m4v")

    // ==================== MediaStore 全盘扫描 ====================

    fun scanAllVideos(context: Context): List<VideoItem> {
        val results = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATA
        )

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null, null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val size = cursor.getLong(sizeCol)
                    if (size < 50 * 1024) continue // 过滤无效小文件
                    val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                    results.add(
                        VideoItem(
                            id = id,
                            contentUri = uri,
                            name = cursor.getString(nameCol) ?: "video_$id",
                            size = size,
                            lastModified = cursor.getLong(dateCol) * 1000,
                            durationMs = cursor.getLong(durCol),
                            bucketName = cursor.getString(bucketCol) ?: "",
                            filePath = cursor.getString(dataCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore 扫描失败", e)
        }
        return results
    }

    // ==================== SAF 授权目录扫描（微信缓存） ====================

    /** 递归扫描 SAF 授权的树目录，收集视频文件 */
    fun scanSafTree(context: Context, treeUri: Uri, maxDepth: Int = 6): List<VideoItem> {
        val results = mutableListOf<VideoItem>()
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return results
        scanDocumentTree(root, results, 0, maxDepth)
        return results.sortedByDescending { it.lastModified }
    }

    private fun scanDocumentTree(dir: DocumentFile, out: MutableList<VideoItem>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        for (doc in dir.listFiles()) {
            try {
                if (doc.isDirectory) {
                    val name = (doc.name ?: "").lowercase()
                    // 跳过明显无关目录，减少遍历量
                    if (name in setOf("cache", "emoji", "image", "image2", "voice", "voice2", "avatar", "wallet", "backup")) continue
                    scanDocumentTree(doc, out, depth + 1, maxDepth)
                } else if (doc.isFile) {
                    val name = doc.name ?: continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in videoExtensions && doc.length() > 50 * 1024) {
                        out.add(
                            VideoItem(
                                contentUri = doc.uri,
                                name = name,
                                size = doc.length(),
                                lastModified = doc.lastModified(),
                                bucketName = "微信缓存",
                                fromSaf = true
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "遍历跳过: ${doc.uri}", e)
            }
        }
    }

    // ==================== 保存到相册 ====================

    /** 把视频（contentUri）保存到相册 Movies/WeChatSaved */
    fun saveToGallery(context: Context, item: VideoItem): Boolean {
        return try {
            val resolver = context.contentResolver
            val fileName = if (item.name.contains('.')) item.name else "${item.name}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "saved_${System.currentTimeMillis()}_$fileName")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/WeChatSaved")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

            val destUri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(destUri)?.use { out ->
                resolver.openInputStream(item.contentUri)?.use { input ->
                    input.copyTo(out)
                } ?: return false
            } ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(destUri, values, null, null)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存失败: ${item.name}", e)
            false
        }
    }

    fun batchSave(context: Context, items: List<VideoItem>): Pair<Int, Int> {
        var ok = 0
        var fail = 0
        items.forEach { if (saveToGallery(context, it)) ok++ else fail++ }
        return ok to fail
    }

    // ==================== 播放 / 分享 ====================

    /** 调系统播放器预览 */
    fun playVideo(context: Context, item: VideoItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.contentUri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "播放视频").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** 分享到其他 App（如 WorkBuddy 提取文字） */
    fun shareVideo(context: Context, item: VideoItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, item.contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享视频到").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun shareVideos(context: Context, items: List<VideoItem>) {
        if (items.size == 1) {
            shareVideo(context, items[0])
            return
        }
        val uris = ArrayList(items.map { it.contentUri })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 ${items.size} 个视频到").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
