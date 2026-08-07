package com.wbconv.wechatvideosaver.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.wbconv.wechatvideosaver.R
import com.wbconv.wechatvideosaver.data.VideoItem
import com.wbconv.wechatvideosaver.util.VideoScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面 v2
 *
 * 数据来源：
 * - 相册扫描：MediaStore 全盘视频（含微信"保存视频"到相册的）
 * - 微信缓存：SAF 授权 Android/data/com.tencent.mm 后直读
 *
 * 操作：单击弹出菜单（播放/保存/分享），长按多选批量操作
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: VideoAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private lateinit var fabScan: FloatingActionButton
    private lateinit var chipGroup: ChipGroup
    private lateinit var chipAll: Chip
    private lateinit var chipWeChat: Chip
    private lateinit var multiSelectBar: View
    private lateinit var btnSaveSelected: MaterialButton
    private lateinit var btnShareSelected: MaterialButton
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnCancelSelect: MaterialButton
    private lateinit var prefs: SharedPreferences

    private var allVideos: List<VideoItem> = emptyList()
    private var safVideos: List<VideoItem> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            startScan()
        } else {
            showPermissionSettingsDialog()
        }
    }

    // SAF 目录授权
    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            prefs.edit().putString("saf_tree_uri", uri.toString()).apply()
            scanSafDirectory(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("app", MODE_PRIVATE)
        initViews()
        setupRecyclerView()
        setupChips()
        fabScan.setOnClickListener { showScanOptions() }
        btnSaveSelected.setOnClickListener { batchSave() }
        btnShareSelected.setOnClickListener {
            val sel = adapter.getSelectedItems()
            if (sel.isNotEmpty()) VideoScanner.shareVideos(this, sel)
        }
        btnSelectAll.setOnClickListener { adapter.selectAll(); updateMultiSelectBar() }
        btnCancelSelect.setOnClickListener { adapter.exitMultiSelect(); updateMultiSelectBar() }
    }

    override fun onResume() {
        super.onResume()
        if (allVideos.isEmpty() && safVideos.isEmpty()) checkPermissionAndScan()
    }

    override fun onDestroy() {
        adapter.destroy()
        super.onDestroy()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvStatus = findViewById(R.id.tvStatus)
        fabScan = findViewById(R.id.fabScan)
        chipGroup = findViewById(R.id.chipGroup)
        chipAll = findViewById(R.id.chipAll)
        chipWeChat = findViewById(R.id.chipWeChat)
        multiSelectBar = findViewById(R.id.multiSelectBar)
        btnSaveSelected = findViewById(R.id.btnSaveSelected)
        btnShareSelected = findViewById(R.id.btnShareSelected)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnCancelSelect = findViewById(R.id.btnCancelSelect)
    }

    private fun setupRecyclerView() {
        adapter = VideoAdapter(
            items = mutableListOf(),
            onItemClick = { showActionDialog(it) },
            onSelectionChanged = { updateMultiSelectBar() }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupChips() {
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipWeChat -> applyFilter(weChatOnly = true)
                else -> applyFilter(weChatOnly = false)
            }
        }
    }

    private fun applyFilter(weChatOnly: Boolean) {
        val merged = (allVideos + safVideos).distinctBy { it.contentUri.toString() }
        val filtered = if (weChatOnly) merged.filter { it.isWeChat } else merged
        adapter.updateItems(filtered)
        updateStatus(filtered.size, merged.size)
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateStatus(showing: Int, total: Int) {
        tvStatus.text = "显示 $showing 个视频（共扫描到 $total 个）"
    }

    // ==================== 扫描 ====================

    private fun showScanOptions() {
        val options = arrayOf("扫描相册视频", "授权微信目录（直读缓存）", "使用帮助")
        AlertDialog.Builder(this)
            .setTitle("选择操作")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkPermissionAndScan()
                    1 -> launchSafPicker()
                    2 -> showHelp()
                }
            }
            .show()
    }

    private fun checkPermissionAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startScan()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun startScan() {
        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        tvStatus.text = "正在扫描相册视频..."

        lifecycleScope.launch {
            allVideos = withContext(Dispatchers.IO) { VideoScanner.scanAllVideos(this@MainActivity) }
            // 有 SAF 授权则一并扫
            prefs.getString("saf_tree_uri", null)?.let { uriStr ->
                try {
                    safVideos = withContext(Dispatchers.IO) {
                        VideoScanner.scanSafTree(this@MainActivity, Uri.parse(uriStr))
                    }
                } catch (e: Exception) {
                    prefs.edit().remove("saf_tree_uri").apply()
                }
            }
            progressBar.visibility = View.GONE
            applyFilter(chipWeChat.isChecked)
            if (allVideos.isEmpty() && safVideos.isEmpty()) showEmptyGuide()
        }
    }

    private fun launchSafPicker() {
        AlertDialog.Builder(this)
            .setTitle("授权微信目录")
            .setMessage("接下来会打开系统文件选择器，请依次进入：\n\n内部存储 → Android → data → com.tencent.mm\n\n然后点「使用此文件夹」完成授权。\n\n授权后 App 就能直接读取微信视频缓存。\n\n（部分 Android 13+ 系统可能禁止选择该目录，属系统限制）")
            .setPositiveButton("去授权") { _, _ -> safLauncher.launch(null) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun scanSafDirectory(treeUri: Uri) {
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "正在扫描微信缓存目录..."
        lifecycleScope.launch {
            safVideos = withContext(Dispatchers.IO) {
                VideoScanner.scanSafTree(this@MainActivity, treeUri)
            }
            progressBar.visibility = View.GONE
            applyFilter(chipWeChat.isChecked)
            Toast.makeText(this@MainActivity, "微信缓存找到 ${safVideos.size} 个视频", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 操作 ====================

    private fun showActionDialog(item: VideoItem) {
        val options = arrayOf("播放", "保存到相册", "分享到其他 App")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage("大小：${item.sizeFormatted}\n时长：${item.durationFormatted}\n日期：${item.dateFormatted}\n来源：${item.bucketName}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> VideoScanner.playVideo(this, item)
                    1 -> saveSingle(item)
                    2 -> VideoScanner.shareVideo(this, item)
                }
            }
            .show()
    }

    private fun saveSingle(item: VideoItem) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { VideoScanner.saveToGallery(this@MainActivity, item) }
            progressBar.visibility = View.GONE
            Toast.makeText(this@MainActivity,
                if (ok) "已保存到相册 Movies/WeChatSaved" else "保存失败",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun batchSave() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择视频", Toast.LENGTH_SHORT).show()
            return
        }
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "正在保存 ${selected.size} 个视频..."
        lifecycleScope.launch {
            val (ok, fail) = withContext(Dispatchers.IO) {
                VideoScanner.batchSave(this@MainActivity, selected)
            }
            progressBar.visibility = View.GONE
            tvStatus.text = "保存完成：成功 $ok，失败 $fail"
            Toast.makeText(this@MainActivity, "成功 $ok 个，失败 $fail 个", Toast.LENGTH_LONG).show()
            adapter.exitMultiSelect()
            updateMultiSelectBar()
        }
    }

    private fun updateMultiSelectBar() {
        val count = adapter.selectedItems.size
        if (adapter.multiSelectMode) {
            multiSelectBar.visibility = View.VISIBLE
            btnSaveSelected.text = "保存($count)"
            btnShareSelected.text = "分享($count)"
            btnSaveSelected.isEnabled = count > 0
            btnShareSelected.isEnabled = count > 0
        } else {
            multiSelectBar.visibility = View.GONE
        }
    }

    private fun showEmptyGuide() {
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = "没有找到视频\n\n方式一（推荐）：\n在微信里点开视频全屏播放 → 点右下角「保存视频」→ 回到本 App 点右下角按钮重新扫描\n\n方式二：\n点右下角按钮 →「授权微信目录」→ 直接读取微信缓存（部分系统不支持）"
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("使用帮助")
            .setMessage("【保存微信视频】\n1. 微信里点开视频 → 全屏播放 → 右下角「保存视频」\n2. 回到本 App 重新扫描，视频就会出现\n\n【直读微信缓存】\n点右下角按钮 →「授权微信目录」→ 按提示选择 Android/data/com.tencent.mm\n\n【提取视频文字】\n选中视频 → 分享 → 发送给 WorkBuddy，AI 自动提取音频文字\n\n【多选操作】\n长按任意视频进入多选，可批量保存或分享")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要视频读取权限")
            .setMessage("请在系统设置中允许「视频和音乐」访问权限。")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (adapter.multiSelectMode) {
            adapter.exitMultiSelect()
            updateMultiSelectBar()
        } else {
            super.onBackPressed()
        }
    }
}
