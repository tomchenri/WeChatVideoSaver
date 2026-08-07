package com.wbconv.wechatvideosaver.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 占位 Activity（预留：通过 SAF 文件选择器手动选择微信目录）
 * 可在后续版本中实现，让用户手动通过系统文件选择器选择微信视频目录
 */
class FolderPickerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 后续实现：通过 ACTION_OPEN_DOCUMENT_TREE 让用户手动选择微信目录
    }
}
