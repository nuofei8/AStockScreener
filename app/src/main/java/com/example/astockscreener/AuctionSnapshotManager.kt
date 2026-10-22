package com.example.astockscreener

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 集合竞价快照的存取 + 定时调度。
 *
 * 工作方式：
 *   每天 9:20 / 9:25，用 AlarmManager 触发 AuctionAlarmReceiver，
 *   拉取一次全市场"价格+成交量"快照，存进 SharedPreferences。
 *   有了两个时间点的快照后，就能算出"9:20价格<9:25价格"（竞价是否走高）、
 *   "9:25量/9:20量"（竞价量比）、以及9:25时点的原始成交量/成交额。
 *
 * ⚠️ Android 后台限制提醒（这个比接口字段更容易踩坑）：
 *   - 很多国产手机（小米/华为/OPPO/vivo等）默认会把后台应用的闹钟/广播直接杀掉，
 *     哪怕用了 setExactAndAllowWhileIdle 也可能不触发。
 *     必须手动去手机的"电池优化"设置里，把这个App加入"无限制"/"允许自启动"名单，
 *     不同手机品牌设置路径不一样，自己在设置里搜"电池优化"或"自启动管理"。
 *   - Android 12+ 需要用户手动在系统设置里授权"闹钟和提醒"权限
 *     （代码里会跳转过去，但需要用户自己点一下同意）。
 *   - 如果定时不可靠，MainActivity 里留了"手动记录快照"的按钮做兜底，
 *     建议在 9:20/9:25 这两个时间点自己打开App点一下，比完全依赖后台闹钟稳。
 */
object AuctionSnapshotManager {

    private const val PREFS_NAME = "auction_snapshots"
    private const val REQUEST_CODE_920 = 9200
    private const val REQUEST_CODE_925 = 9250

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    /**
     * 供 AuctionAlarmReceiver 和手动按钮调用，拉取一次快照并存储。
     * 返回 null 表示成功；返回非空字符串表示失败原因（用于在界面上显示具体错误，
     * 而不是笼统的"失败"二字，方便排查到底是超时、HTTP错误还是别的问题）。
     */
    fun captureAndStore(context: Context, tag: String): String? {
        return try {
            val snapshot = EastmoneyApi.fetchPriceVolumeSnapshot()
            if (snapshot.isEmpty()) return "接口返回了空数据（没有抓到任何股票）"

            val json = JSONObject()
            for ((code, pv) in snapshot) {
                val entry = JSONObject()
                entry.put("p", pv.first)
                entry.put("v", pv.second)
                json.put(code, entry)
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("snapshot_$tag", json.toString())
                .putString("snapshot_${tag}_date", todayStr())
                .apply()
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** 读取某个时间点"今天"的快照，日期对不上（比如读到昨天的）直接当作没有 */
    private fun readSnapshot(context: Context, tag: String): Map<String, Pair<Double, Double>>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDate = prefs.getString("snapshot_${tag}_date", null)
        if (savedDate != todayStr()) return null
        val jsonStr = prefs.getString("snapshot_$tag", null) ?: return null

        return try {
            val json = JSONObject(jsonStr)
            val result = mutableMapOf<String, Pair<Double, Double>>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                val entry = json.optJSONObject(code) ?: continue
                result[code] = entry.optDouble("p") to entry.optDouble("v")
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    fun getAuctionResult(context: Context, code: String): Pair<Boolean?, Double?> {
        val result = getAuctionDetail(context, code)
        return result.priceUp to result.volumeSurgeRatio
    }

    /** 竞价全部细分数据：价格上行(9:20→9:25)、量比、原始成交量、估算成交额(万元) */
    data class AuctionDetail(
        val priceUp: Boolean?,
        val volumeSurgeRatio: Double?,
        val volume925: Double?,
        val amountWan925: Double?
    )

    fun getAuctionDetail(context: Context, code: String): AuctionDetail {
        val s920 = readSnapshot(context, "920")?.get(code)
        val s925 = readSnapshot(context, "925")?.get(code)

        val priceUp = if (s920 != null && s925 != null) s920.first < s925.first else null
        val surgeRatio = if (s920 != null && s925 != null && s920.second > 0) {
            s925.second / s920.second
        } else null
        val volume925 = s925?.second
        // 成交额(万元) ≈ 价格(元) × 成交量(手，1手=100股) × 100 / 10000 = 价格×成交量/100
        // ⚠️ f5字段到底是"股"还是"手"没有联网验证过，如果算出来的成交额数量级明显不对
        // （比如几千万变几十万，或反过来），去掉或加上这个 /100，按实际数据校准一下
        val amountWan925 = if (s925 != null) (s925.first * s925.second) / 100.0 else null

        return AuctionDetail(priceUp, surgeRatio, volume925, amountWan925)
    }

    /** 每次打开App时调用，安排今天剩余的时间点闹钟（已过去的时间点不再安排） */
    @SuppressLint("ScheduleExactAlarm")
    fun scheduleTodayAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // 没有精确闹钟权限就不排了，MainActivity 会引导用户去系统设置里开启
            return
        }

        scheduleOne(context, alarmManager, 9, 20, "920", REQUEST_CODE_920)
        scheduleOne(context, alarmManager, 9, 25, "925", REQUEST_CODE_925)
    }

    private fun scheduleOne(
        context: Context,
        alarmManager: AlarmManager,
        hour: Int,
        minute: Int,
        tag: String,
        requestCode: Int
    ) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 5) // 延后5秒，等交易所那边数据先更新
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) return // 今天这个时间点已经过去，不排了

        val intent = Intent(context, AuctionAlarmReceiver::class.java).apply {
            putExtra("tag", tag)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent
            )
        } catch (e: SecurityException) {
            // 没有精确闹钟权限，忽略，靠手动按钮兜底
        }
    }

    private fun todayStr(): String = dateFormat.format(java.util.Date())
}
