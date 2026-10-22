package com.example.astockscreener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 由 AuctionSnapshotManager 安排的 AlarmManager 闹钟触发，
 * 在后台拉取一次价格+成交量快照并保存。
 * 用 goAsync() 延长 BroadcastReceiver 的生命周期，确保网络请求能跑完
 * （BroadcastReceiver 默认处理函数返回后系统可能随时回收进程）。
 */
class AuctionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra("tag") ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AuctionSnapshotManager.captureAndStore(context, tag)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
