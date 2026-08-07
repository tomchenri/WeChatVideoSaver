package com.wbconv.wechatvideosaver.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 视频音频提取 + 离线中文转写（v3 核心）
 *
 * 音频提取：使用 Android 内置 MediaExtractor + MediaCodec 解码音频轨，
 *           再重采样为 16kHz 单声道 16bit PCM（Vosk 标准输入）。无需任何外部库。
 * 语音识别：Vosk 离线中文模型（构建时下载进 APK 的 assets，首次运行解压）。
 * 全程离线，不上传任何数据。
 */
object AudioTranscriber {

    private const val TAG = "AudioTranscriber"
    private const val MODEL_ZIP = "vosk-model-small-cn-0.22.zip"
    private const val MODEL_DIR = "vosk-model-small-cn-0.22"
    private const val TARGET_RATE = 16000

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
                context.assets.open(MODEL_ZIP).use { zip -> unzip(zip, modelsRoot) }
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
            val pcmFile = File(workDir, "audio.pcm")
            if (!extractAudioToPcm(context, videoUri, pcmFile)) {
                Log.e(TAG, "音频提取失败")
                return null
            }

            val m = model ?: run { Log.e(TAG, "模型未初始化"); return null }
            val recognizer = Recognizer(m, TARGET_RATE.toFloat())
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

    /**
     * 用 MediaExtractor + MediaCodec 解码视频中的音频轨，
     * 重采样为 16kHz 单声道 16bit PCM 写入 out 文件。
     */
    private fun extractAudioToPcm(context: Context, uri: Uri, out: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开视频数据源", e)
            return false
        }

        var trackIdx = -1
        var srcRate = 44100
        var channels = 1
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIdx = i
                if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                break
            }
        }
        if (trackIdx < 0) {
            Log.e(TAG, "未找到音频轨")
            extractor.release()
            return false
        }

        val audioFormat = extractor.getTrackFormat(trackIdx)
        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return false
        extractor.selectTrack(trackIdx)

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(audioFormat, null, null, 0)
        decoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val pcmChunks = mutableListOf<ByteArray>()
        var sawInputEOS = false
        var sawOutputEOS = false
        val timeoutUs = 10000L

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inId = decoder.dequeueInputBuffer(timeoutUs)
                if (inId >= 0) {
                    val inBuf = decoder.getInputBuffer(inId) ?: continue
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        decoder.queueInputBuffer(inId, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outId = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outId >= 0) {
                val outBuf = decoder.getOutputBuffer(outId) ?: run {
                    decoder.releaseOutputBuffer(outId, false); continue
                }
                outBuf.position(0)
                val chunk = ByteArray(bufferInfo.size)
                outBuf.get(chunk)
                decoder.releaseOutputBuffer(outId, false)
                pcmChunks.add(chunk)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true
            } else if (outId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFmt = decoder.outputFormat
                if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    srcRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()

        if (pcmChunks.isEmpty()) return false
        val src = ByteArrayOutputStream()
        pcmChunks.forEach { src.write(it) }
        val resampled = resampleToMono16k(src.toByteArray(), srcRate, channels)
        if (resampled.isEmpty()) return false
        FileOutputStream(out).use { it.write(resampled) }
        return true
    }

    /** 线性插值重采样 + 多声道下混为单声道，输出 16kHz 16bit 小端 PCM */
    private fun resampleToMono16k(src: ByteArray, srcRate: Int, channels: Int): ByteArray {
        if (srcRate <= 0) return byteArrayOf()
        val numSrc = src.size / 2
        val numDst = (numSrc.toLong() * TARGET_RATE / srcRate).toInt()
        if (numDst <= 0) return byteArrayOf()
        val dst = ByteArray(numDst * 2)
        var di = 0
        for (i in 0 until numDst) {
            val srcPos = i * srcRate / TARGET_RATE
            var sum = 0
            for (c in 0 until channels) {
                val idx = (srcPos * channels + c) * 2
                if (idx + 1 < src.size) {
                    val s = (src[idx].toInt() and 0xFF) or (src[idx + 1].toInt() shl 8)
                    sum += if (s >= 0x8000) s - 0x10000 else s
                }
            }
            val sample = (sum / channels).coerceIn(-32768, 32767)
            dst[di++] = (sample and 0xFF).toByte()
            dst[di++] = ((sample shr 8) and 0xFF).toByte()
        }
        return dst
    }

    private fun unzip(zipStream: InputStream, destDir: File) {
        destDir.mkdirs()
        val buffer = ByteArray(8192)
        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(destDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) fos.write(buffer, 0, len)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
