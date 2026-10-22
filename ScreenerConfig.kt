package com.example.astockscreener

import android.content.Context

/**
 * 选股画像阈值。现在是可在App内"设置"页面调整的运行时参数，
 * 不再是编译期写死的常量：改完代码重新Build只是改"默认值"，
 * 用户在设置页面改的数值会存进SharedPreferences，优先级更高。
 *
 * load() 在App启动时（ScreenerApp.onCreate）调用一次，把上次保存的设置读回来；
 * save() 在设置页面点"保存"时调用，把当前值写回磁盘。
 * 所有用到这些参数的地方都直接写 ScreenerConfig.XXX，不用传参数，改了立刻全局生效。
 */
object ScreenerConfig {
    // ---- 硬性主条件 ----
    var MAX_PRICE = 8.0                  // 现价上限(元)
    var MAX_CIRC_MARKET_CAP_YI = 120.0   // 流通市值上限(亿元)
    var MIN_NET_INFLOW_PCT = 3.0         // 主力净流入占比下限(%)
    var RESTRICT_TO_MAIN_AND_CHINEXT = true  // 只要主板+创业板

    // ---- 资金强度细分（开盘后持续监测，随每次自动刷新更新） ----
    var ENABLE_SUPER_BIG_CHECK = false        // 是否要求超大单净流入占比达标
    var MIN_SUPER_BIG_NET_PCT = 2.0           // 超大单净流入占比下限(%)
    var ENABLE_BIG_CHECK = false              // 是否要求大单净流入占比达标
    var MIN_BIG_NET_PCT = 2.0                 // 大单净流入占比下限(%)

    // ---- 轮询 ----
    // 东方财富接口按源IP限流并可能临时封禁突发请求，建议不要低于10秒
    var POLL_INTERVAL_MS = 15_000L

    // ---- 竞价加分项 ----
    var ENABLE_AUCTION_PRICE_CHECK = true     // 9:15价格<9:25价格
    var ENABLE_AUCTION_VOLUME_RATIO_CHECK = true  // 竞价量比(9:25量/9:20量)
    var MIN_VOLUME_SURGE_RATIO = 1.5
    var ENABLE_AUCTION_VOLUME_CHECK = false   // 竞价成交量(9:25时点)下限
    var MIN_AUCTION_VOLUME = 50_000.0         // 竞价成交量下限(手)
    var ENABLE_AUCTION_AMOUNT_CHECK = false   // 竞价成交额(9:25时点)下限
    var MIN_AUCTION_AMOUNT_WAN = 500.0        // 竞价成交额下限(万元)

    // ---- 热点题材 ----
    var ENABLE_SECTOR_CHECK = true
    var TOP_N_HOT_SECTOR = 10

    // ---- 利好消息 ----
    var ENABLE_NEWS_CHECK = true
    var POSITIVE_KEYWORDS = listOf(
        "中标", "签约", "增持", "回购", "业绩预增", "扭亏", "重组",
        "合作", "战略", "订单", "专利", "获批", "分红", "超预期",
        "龙头", "国产替代", "政策支持", "补贴"
    )

    // ---- 股性活跃 ----
    var ENABLE_ACTIVITY_CHECK = true
    var ACTIVITY_LOOKBACK_DAYS = 250
    var MIN_AVG_TURNOVER_RATE = 3.0
    var MIN_AVG_AMPLITUDE = 4.0
    var MIN_LIMIT_UP_TIMES = 3

    // ---- 加分汇总门槛 ----
    var MIN_CONDITIONS_MET = 2
    var MAX_STOCKS_TO_ENRICH = 30
    var ENRICH_REQUEST_INTERVAL_MS = 300L

    private const val PREFS_NAME = "screener_settings"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        MAX_PRICE = p.getFloat("max_price", MAX_PRICE.toFloat()).toDouble()
        MAX_CIRC_MARKET_CAP_YI = p.getFloat("max_cap", MAX_CIRC_MARKET_CAP_YI.toFloat()).toDouble()
        MIN_NET_INFLOW_PCT = p.getFloat("min_inflow_pct", MIN_NET_INFLOW_PCT.toFloat()).toDouble()
        RESTRICT_TO_MAIN_AND_CHINEXT = p.getBoolean("restrict_board", RESTRICT_TO_MAIN_AND_CHINEXT)

        ENABLE_SUPER_BIG_CHECK = p.getBoolean("en_super_big", ENABLE_SUPER_BIG_CHECK)
        MIN_SUPER_BIG_NET_PCT = p.getFloat("min_super_big_pct", MIN_SUPER_BIG_NET_PCT.toFloat()).toDouble()
        ENABLE_BIG_CHECK = p.getBoolean("en_big", ENABLE_BIG_CHECK)
        MIN_BIG_NET_PCT = p.getFloat("min_big_pct", MIN_BIG_NET_PCT.toFloat()).toDouble()

        POLL_INTERVAL_MS = p.getLong("poll_interval_ms", POLL_INTERVAL_MS)

        ENABLE_AUCTION_PRICE_CHECK = p.getBoolean("en_auction_price", ENABLE_AUCTION_PRICE_CHECK)
        ENABLE_AUCTION_VOLUME_RATIO_CHECK = p.getBoolean("en_auction_ratio", ENABLE_AUCTION_VOLUME_RATIO_CHECK)
        MIN_VOLUME_SURGE_RATIO = p.getFloat("min_surge_ratio", MIN_VOLUME_SURGE_RATIO.toFloat()).toDouble()
        ENABLE_AUCTION_VOLUME_CHECK = p.getBoolean("en_auction_volume", ENABLE_AUCTION_VOLUME_CHECK)
        MIN_AUCTION_VOLUME = p.getFloat("min_auction_volume", MIN_AUCTION_VOLUME.toFloat()).toDouble()
        ENABLE_AUCTION_AMOUNT_CHECK = p.getBoolean("en_auction_amount", ENABLE_AUCTION_AMOUNT_CHECK)
        MIN_AUCTION_AMOUNT_WAN = p.getFloat("min_auction_amount_wan", MIN_AUCTION_AMOUNT_WAN.toFloat()).toDouble()

        ENABLE_SECTOR_CHECK = p.getBoolean("en_sector", ENABLE_SECTOR_CHECK)
        TOP_N_HOT_SECTOR = p.getInt("top_n_sector", TOP_N_HOT_SECTOR)

        ENABLE_NEWS_CHECK = p.getBoolean("en_news", ENABLE_NEWS_CHECK)

        ENABLE_ACTIVITY_CHECK = p.getBoolean("en_activity", ENABLE_ACTIVITY_CHECK)
        MIN_AVG_TURNOVER_RATE = p.getFloat("min_turnover", MIN_AVG_TURNOVER_RATE.toFloat()).toDouble()
        MIN_AVG_AMPLITUDE = p.getFloat("min_amplitude", MIN_AVG_AMPLITUDE.toFloat()).toDouble()
        MIN_LIMIT_UP_TIMES = p.getInt("min_limit_up", MIN_LIMIT_UP_TIMES)

        MIN_CONDITIONS_MET = p.getInt("min_conditions_met", MIN_CONDITIONS_MET)
        MAX_STOCKS_TO_ENRICH = p.getInt("max_enrich", MAX_STOCKS_TO_ENRICH)
    }

    fun save(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        p.edit()
            .putFloat("max_price", MAX_PRICE.toFloat())
            .putFloat("max_cap", MAX_CIRC_MARKET_CAP_YI.toFloat())
            .putFloat("min_inflow_pct", MIN_NET_INFLOW_PCT.toFloat())
            .putBoolean("restrict_board", RESTRICT_TO_MAIN_AND_CHINEXT)
            .putBoolean("en_super_big", ENABLE_SUPER_BIG_CHECK)
            .putFloat("min_super_big_pct", MIN_SUPER_BIG_NET_PCT.toFloat())
            .putBoolean("en_big", ENABLE_BIG_CHECK)
            .putFloat("min_big_pct", MIN_BIG_NET_PCT.toFloat())
            .putLong("poll_interval_ms", POLL_INTERVAL_MS)
            .putBoolean("en_auction_price", ENABLE_AUCTION_PRICE_CHECK)
            .putBoolean("en_auction_ratio", ENABLE_AUCTION_VOLUME_RATIO_CHECK)
            .putFloat("min_surge_ratio", MIN_VOLUME_SURGE_RATIO.toFloat())
            .putBoolean("en_auction_volume", ENABLE_AUCTION_VOLUME_CHECK)
            .putFloat("min_auction_volume", MIN_AUCTION_VOLUME.toFloat())
            .putBoolean("en_auction_amount", ENABLE_AUCTION_AMOUNT_CHECK)
            .putFloat("min_auction_amount_wan", MIN_AUCTION_AMOUNT_WAN.toFloat())
            .putBoolean("en_sector", ENABLE_SECTOR_CHECK)
            .putInt("top_n_sector", TOP_N_HOT_SECTOR)
            .putBoolean("en_news", ENABLE_NEWS_CHECK)
            .putBoolean("en_activity", ENABLE_ACTIVITY_CHECK)
            .putFloat("min_turnover", MIN_AVG_TURNOVER_RATE.toFloat())
            .putFloat("min_amplitude", MIN_AVG_AMPLITUDE.toFloat())
            .putInt("min_limit_up", MIN_LIMIT_UP_TIMES)
            .putInt("min_conditions_met", MIN_CONDITIONS_MET)
            .putInt("max_enrich", MAX_STOCKS_TO_ENRICH)
            .apply()
    }

    // 候选股过多时，逐股查询题材/新闻/历史K线会很慢也容易被限流，
    // 这里限制最多对前N只（按净流入占比排序后）做加分项评估。
    // 请求之间有小延迟，避免短时间内密集请求同一批接口被限流。
}
