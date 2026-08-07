package com.wbconv.wechatvideosaver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wbconv.wechatvideosaver.R
import com.wbconv.wechatvideosaver.util.AudioTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频转文字界面
 * 接收 video_uri，执行「提取音频 → 离线识别 → 显示文字」
 */
class TranscribeActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCopy: Button
    private lateinit var btnShare: Button
    private lateinit var btnBack: Button
    private var resultText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcribe)

        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        progressBar = findViewById(R.id.progressBar)
        btnCopy = findViewById(R.id.btnCopy)
        btnShare = findViewById(R.id.btnShare)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { finish() }
        btnCopy.setOnClickListener { copyText() }
        btnShare.setOnClickListener { shareText() }
        btnCopy.isEnabled = false
        btnShare.isEnabled = false

        val uriStr = intent.getStringExtra("video_uri")
        if (uriStr.isNullOrEmpty()) {
            Toast.makeText(this, "未收到视频", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val uri = Uri.parse(uriStr)
        val name = intent.getStringExtra("video_name") ?: "视频"
        startTranscription(uri, name)
    }

    private fun startTranscription(uri: Uri, name: String) {
        lifecycleScope.launch {
            // 1. 准备离线模型
            tvStatus.text = "正在准备中文识别模型（首次需解压，请稍候）..."
            val modelReady = withContext(Dispatchers.IO) {
                AudioTranscriber.ensureModel(this@TranscribeActivity)
            }
            if (!modelReady) {
                progressBar.visibility = View.GONE
                tvStatus.text = "识别模型加载失败。\n可能是安装包不完整或存储空间不足，请重新下载完整安装包后重试。"
                return@launch
            }

            // 2. 提取音频 + 离线识别
            tvStatus.text = "正在提取音频并识别：$name"
            val text = withContext(Dispatchers.IO) {
                AudioTranscriber.transcribe(this@TranscribeActivity, uri) { partial ->
                    runOnUiThread { tvResult.text = partial }
                }
            }

            progressBar.visibility = View.GONE
            if (text.isNullOrBlank()) {
                tvStatus.text = "识别失败：未能从视频中提取到音频，或该视频没有声音。"
            } else {
                resultText = text
                tvStatus.text = "识别完成 ✅ 可复制或分享"
                tvResult.text = text
                btnCopy.isEnabled = true
                btnShare.isEnabled = true
            }
        }
    }

    private fun copyText() {
        if (resultText.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("视频识别文字", resultText))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun shareText() {
        if (resultText.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, resultText)
        }
        startActivity(Intent.createChooser(intent, "分享文字到"))
    }
}
