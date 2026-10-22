package com.example.astockscreener

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.astockscreener.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ScreenerViewModel by viewModels()
    private val adapter = StockAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshOnce()
        }

        binding.switchAutoRefresh.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) viewModel.startAutoRefresh() else viewModel.stopAutoRefresh()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnSnap920.setOnClickListener { captureSnapshot("920", "9:20") }
        binding.btnSnap925.setOnClickListener { captureSnapshot("925", "9:25", autoRefreshAfter = true) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.stocks)
                    binding.tvLastUpdate.text =
                        if (state.loadingStage.isNotEmpty()) state.loadingStage else state.lastUpdateText
                    binding.swipeRefresh.isRefreshing = state.isLoading
                    binding.tvError.visibility = if (state.errorText != null) View.VISIBLE else View.GONE
                    binding.tvError.text = state.errorText ?: ""
                    binding.tvCount.text = "命中 ${state.stocks.size} 只"
                }
            }
        }

        ensureExactAlarmPermission()
        AuctionSnapshotManager.scheduleTodayAlarms(this)

        viewModel.refreshOnce()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页面返回时，参数可能改了，重新刷新一次让新参数立刻生效
        viewModel.refreshOnce()
    }

    private fun captureSnapshot(tag: String, label: String, autoRefreshAfter: Boolean = false) {
        Toast.makeText(this, "正在记录 $label 快照...", Toast.LENGTH_SHORT).show()
        viewModel.captureAuctionSnapshot(tag) { error ->
            val msg = if (error == null) "$label 快照记录成功" else "$label 失败：$error"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            // 9:25快照记录成功后自动刷新一次选股结果，方便在9:25-9:30这个窗口直接拿到分析结果
            if (error == null && autoRefreshAfter) {
                viewModel.refreshOnce()
            }
        }
    }

    /**
     * Android 12+ 需要用户手动去系统设置里为本App开启"闹钟和提醒"权限，
     * 否则后台定时抓竞价快照会失效（只能靠手动按钮兜底）。
     */
    private fun ensureExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(
                    this,
                    "如需后台自动记录竞价快照，请在系统设置里给本App开启精确闹钟权限",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // 部分设备没有这个系统页面，忽略，用户可以自己去设置里找
                }
            }
        }
    }
}
