package com.wbconv.wechatvideosaver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.wbconv.wechatvideosaver.R
import com.wbconv.wechatvideosaver.data.VideoItem
import com.wbconv.wechatvideosaver.util.WeChatScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面
 *
 * 功能：
 * 1. 扫描微信视频
 * 2. 列表展示（支持单选保存和多选批量保存）
 * 3. 保存到相册 Movies/WeChatSaved
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: VideoAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private lateinit var fabScan: FloatingActionButton
    private lateinit var btnSaveSelected: MaterialButton
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnCancelSelect: MaterialButton
    private lateinit var multiSelectBar: View

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startScan()
        } else {
            // 检查是否点击了「不再询问」
            val shouldShowRationale = permissions.keys.any { perm ->
                shouldShowRequestPermissionRationale(perm)
            }
            if (!shouldShowRationale) {
                // 用户点了「不再询问」，引导到设置页
                showPermissionSettingsDialog()
            } else {
                Toast.makeText(this, "需要存储权限才能扫描微信视频", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupRecyclerView()

        // 自动检查权限并扫描
        fabScan.setOnClickListener {
            checkPermissionAndScan()
        }

        btnSaveSelected.setOnClickListener {
            saveSelectedVideos()
        }

        btnSelectAll.setOnClickListener {
            adapter.selectAll()
            updateMultiSelectBar()
        }

        btnCancelSelect.setOnClickListener {
            adapter.exitMultiSelect()
            updateMultiSelectBar()
        }
    }

    override fun onResume() {
        super.onResume()
        // 首次打开自动扫描
        if (adapter.itemCount == 0) {
            checkPermissionAndScan()
        }
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvStatus = findViewById(R.id.tvStatus)
        fabScan = findViewById(R.id.fabScan)
        btnSaveSelected = findViewById(R.id.btnSaveSelected)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnCancelSelect = findViewById(R.id.btnCancelSelect)
        multiSelectBar = findViewById(R.id.multiSelectBar)
    }

    private fun setupRecyclerView() {
        adapter = VideoAdapter(
            items = mutableListOf(),
            onItemClick = { item -> showSaveDialog(item) },
            onItemLongClick = { _ -> updateMultiSelectBar() },
            onCheckChanged = { updateMultiSelectBar() }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    /**
     * 检查权限并开始扫描
     */
    private fun checkPermissionAndScan() {
        val permissions = getRequiredPermissions()
        val needRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needRequest) {
            permissionLauncher.launch(permissions)
        } else {
            startScan()
        }
    }

    /**
     * 根据 Android 版本返回所需权限
     */
    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            // Android 9 及以下
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    /**
     * 开始扫描
     */
    private fun startScan() {
        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        tvStatus.text = "正在扫描微信视频..."
        fabScan.visibility = View.GONE

        lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) {
                WeChatScanner.scan(this@MainActivity)
            }

            progressBar.visibility = View.GONE
            fabScan.visibility = View.VISIBLE

            if (videos.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "未找到微信视频\n\n可能原因：\n1. 微信视频目录无访问权限\n2. 微信未下载过视频\n3. Android 11+ 限制了 /Android/data/ 目录访问\n\n点击下方按钮用「文件选择器」手动选择微信视频目录"
                tvStatus.text = "未找到视频"
            } else {
                tvEmpty.visibility = View.GONE
                adapter.updateItems(videos)
                tvStatus.text = "找到 ${videos.size} 个视频"
            }
        }
    }

    /**
     * 单个视频保存确认
     */
    private fun showSaveDialog(item: VideoItem) {
        AlertDialog.Builder(this)
            .setTitle("保存视频")
            .setMessage("文件：${item.name}\n大小：${item.sizeFormatted}\n日期：${item.dateFormatted}\n\n保存到相册 Movies/WeChatSaved 目录？")
            .setPositiveButton("保存") { _, _ ->
                saveSingleVideo(item)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 保存单个视频
     */
    private fun saveSingleVideo(item: VideoItem) {
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "正在保存 ${item.name}..."

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                WeChatScanner.saveToGallery(this@MainActivity, item.file)
            }
            progressBar.visibility = View.GONE
            tvStatus.text = if (success) {
                "已保存到相册: Movies/WeChatSaved/${item.name}"
            } else {
                "保存失败"
            }
            Toast.makeText(
                this@MainActivity,
                if (success) "保存成功！" else "保存失败",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 批量保存选中的视频
     */
    private fun saveSelectedVideos() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择视频", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("批量保存")
            .setMessage("确定保存 ${selected.size} 个视频到相册？")
            .setPositiveButton("保存") { _, _ ->
                progressBar.visibility = View.VISIBLE
                tvStatus.text = "正在批量保存 ${selected.size} 个视频..."

                lifecycleScope.launch {
                    val (success, failed) = withContext(Dispatchers.IO) {
                        WeChatScanner.batchSaveToGallery(
                            this@MainActivity,
                            selected.map { it.file }
                        )
                    }
                    progressBar.visibility = View.GONE
                    tvStatus.text = "保存完成: 成功 $success 个, 失败 $failed 个"
                    Toast.makeText(
                        this@MainActivity,
                        "成功 $success 个，失败 $failed 个",
                        Toast.LENGTH_LONG
                    ).show()

                    adapter.exitMultiSelect()
                    updateMultiSelectBar()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 更新多选操作栏
     */
    private fun updateMultiSelectBar() {
        val count = adapter.selectedItems.size
        if (adapter.multiSelectMode) {
            multiSelectBar.visibility = View.VISIBLE
            btnSaveSelected.text = "保存选中($count)"
            btnSaveSelected.isEnabled = count > 0
        } else {
            multiSelectBar.visibility = View.GONE
        }
    }

    /**
     * 引导用户到设置页开启权限
     */
    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要存储权限")
            .setMessage("您拒绝了存储权限并勾选了「不再询问」，需要手动到设置中开启权限才能使用本功能。\n\n点击「去设置」跳转到应用权限管理页面。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 处理返回键：多选模式下先退出多选
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (adapter.multiSelectMode) {
            adapter.exitMultiSelect()
            updateMultiSelectBar()
        } else {
            super.onBackPressed()
        }
    }
}
