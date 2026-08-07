package com.wbconv.wechatvideosaver.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 视频音频提取 + 离线中文转写（v3 核心）
 *
 * 流程：
 *   1. 把视频 contentUri 拷贝到缓存文件
 *   2. FFmpegKit 提取音频 → 16kHz 单声道 s16le PCM
 *   3. Vosk 离线模型逐块识别 → 拼接成文字
 *
 * 模型：vosk-model-small-cn-0.22（构建时下载进 APK 的 assets，首次运行解压到 filesDir）
 * 全程离线，不上传任何数据。
 */
object AudioTranscriber {

    private const val TAG = "AudioTranscriber"
    private const val MODEL_ZIP = "vosk-model-small-cn-0.22.zip"
    private const val MODEL_DIR = "vosk-model-small-cn-0.22"

    @Volatile private var model: Model? = null
    @Volatile private var modelReady = false

    /** 确保模型就绪（首次从 assets 解压）。返回是否成功 */
    @Synchronized
    fun ensureModel(context: Context): Boolean {
        if (modelReady && model != null) return true
        return try {
            val modelsRoot = File(context.filesDir, "models")
            val modelDir = File(modelsRoot, MODEL_DIR)
            if (!File(modelDir, "conf/model.conf").exists()) {
                Log.i(TAG, "解压离线识别模型...")
                modelsRoot.mkdirs()
                context.assets.open(MODEL_ZIP).use { zip ->
                    unzip(zip, modelsRoot)
                }
            }
            LibVosk.load()
            model = Model(modelDir.absolutePath)
            modelReady = true
            Log.i(TAG, "模型就绪")
            true
        } catch (e: Exception) {
            Log.e(TAG, "模型初始化失败", e)
            false
        }
    }

    /**
     * 转写视频音频为文字
     * @param onProgress 实时回调（部分识别结果），用于界面刷新
     * @return 完整文字，失败返回 null
     */
    fun transcribe(context: Context, videoUri: Uri, onProgress: (String) -> Unit): String? {
        val workDir = File(context.cacheDir, "transcribe_${System.currentTimeMillis()}")
        workDir.mkdirs()
        try {
            val videoFile = File(workDir, "input.mp4")
            copyUriToFile(context, videoUri, videoFile)
            if (videoFile.length() == 0L) {
                Log.e(TAG, "视频文件为空")
                return null
            }

            val pcmFile = File(workDir, "audio.pcm")
            // 提取音频：去视频、单声道、16k、16bit 小端 PCM（Vosk 标准输入）
            val cmd = "-y -i \"${videoFile.absolutePath}\" -vn -ac 1 -ar 16000 -f s16le \"${pcmFile.absolutePath}\""
            val session = FFmpegKit.execute(cmd)
            if (!ReturnCode.isSuccess(session.returnCode) || !pcmFile.exists() || pcmFile.length() == 0L) {
                Log.e(TAG, "FFmpeg 提取音频失败，returnCode=${session.returnCode}")
                return null
            }

            val m = model ?: run {
                Log.e(TAG, "模型未初始化")
                return null
            }

            val recognizer = Recognizer(m, 16000.0f)
            val bytes = pcmFile.readBytes()
            val chunk = 4000
            val sb = StringBuilder()
            var offset = 0
            while (offset < bytes.size) {
                val size = minOf(chunk, bytes.size - offset)
                val buf = bytes.copyOfRange(offset, offset + size)
                if (recognizer.acceptWaveForm(buf, size)) {
                    sb.append(JSONObject(recognizer.result).optString("text", ""))
                }
                val partial = JSONObject(recognizer.partialResult).optString("text", "")
                onProgress(sb.toString() + (if (partial.isNotEmpty()) partial else ""))
                offset += size
            }
            sb.append(JSONObject(recognizer.finalResult).optString("text", ""))
            recognizer.release()

            val text = sb.toString().replace(Regex("\\s+"), " ").trim()
            Log.i(TAG, "转写完成，字数=${text.length}")
            return text
        } catch (e: Exception) {
            Log.e(TAG, "转写失败", e)
            return null
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, out: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: throw IOException("无法打开视频: $uri")
    }

    private fun unzip(zipStream: InputStream, destDir: File) {
        destDir.mkdirs()
        val buffer = ByteArray(8192)
        ZipInputStream(BufferedInputStream(zipStream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(destDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
