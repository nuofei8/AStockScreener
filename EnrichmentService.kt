package com.example.astockscreener

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 对已经通过"资金驱动硬性主条件"筛选出的候选股，逐个补充加分信息：
 *   - 竞价价格上行 / 竞价量比 / 竞价成交量 / 竞价成交额（本地读快照，不联网）
 *   - 超大单/大单净流入占比是否达标（已经在主快照里拿到了，不用额外联网）
 *   - 热点题材（联网）
 *   - 利好消息（联网）
 *   - 股性活跃（联网，历史K线）
 *
 * 每一项都可以在设置页面单独开关（ScreenerConfig.ENABLE_XXX），
 * 关掉的项目不计入加分统计（既不算命中也不算未命中，直接跳过）。
 *
 * 只对排序后的前 MAX_STOCKS_TO_ENRICH 只做联网类加分项：
 *   候选股可能有几十上百只，逐股打三四个接口太慢也容易被限流，
 *   只看资金驱动最强的前几十只足够用了，这个数量在设置页面调。
 *
 * 请求之间有小延迟（ENRICH_REQUEST_INTERVAL_MS），避免短时间内密集请求被限流。
 */
object EnrichmentService {

    suspend fun enrich(context: Context, candidates: List<StockItem>): List<StockItem> {
        val toEnrich = candidates.take(ScreenerConfig.MAX_STOCKS_TO_ENRICH)
        val hotSectors = if (ScreenerConfig.ENABLE_SECTOR_CHECK) {
            withContext(Dispatchers.IO) {
                runCatching { SectorApi.fetchHotSectorNames(ScreenerConfig.TOP_N_HOT_SECTOR) }
                    .getOrDefault(emptyList())
            }
        } else emptyList()

        val enriched = mutableListOf<StockItem>()
        for (stock in toEnrich) {
            enriched.add(withContext(Dispatchers.IO) { enrichOne(context, stock, hotSectors) })
            delay(ScreenerConfig.ENRICH_REQUEST_INTERVAL_MS)
        }

        // 候选股里没进入前N强化的那部分，原样保留在列表末尾（不参与加分排序）
        val remainder = candidates.drop(ScreenerConfig.MAX_STOCKS_TO_ENRICH)

        return (enriched.sortedWith(
            compareByDescending<StockItem> { it.conditionsMet }
                .thenByDescending { it.netInflowPct }
        ) + remainder)
    }

    private fun enrichOne(context: Context, stock: StockItem, hotSectors: List<String>): StockItem {
        val conditions = mutableListOf<Boolean?>()

        // 1. 竞价价格上行（本地读快照，无网络请求）
        val auctionDetail = AuctionSnapshotManager.getAuctionDetail(context, stock.code)
        val auctionPriceUp = if (ScreenerConfig.ENABLE_AUCTION_PRICE_CHECK) auctionDetail.priceUp else null
        conditions.add(auctionPriceUp)

        // 2. 竞价量比
        val surgeRatio = auctionDetail.volumeSurgeRatio
        val surgeHit = if (ScreenerConfig.ENABLE_AUCTION_VOLUME_RATIO_CHECK) {
            surgeRatio?.let { it >= ScreenerConfig.MIN_VOLUME_SURGE_RATIO }
        } else null
        conditions.add(surgeHit)

        // 3. 竞价成交量
        val volumeHit = if (ScreenerConfig.ENABLE_AUCTION_VOLUME_CHECK) {
            auctionDetail.volume925?.let { it >= ScreenerConfig.MIN_AUCTION_VOLUME }
        } else null
        conditions.add(volumeHit)

        // 4. 竞价成交额
        val amountHit = if (ScreenerConfig.ENABLE_AUCTION_AMOUNT_CHECK) {
            auctionDetail.amountWan925?.let { it >= ScreenerConfig.MIN_AUCTION_AMOUNT_WAN }
        } else null
        conditions.add(amountHit)

        // 5. 超大单净流入占比（数据已经在主快照里了，不用再联网）
        val superBigHit = if (ScreenerConfig.ENABLE_SUPER_BIG_CHECK) {
            if (stock.superBigNetPct.isNaN()) null else stock.superBigNetPct >= ScreenerConfig.MIN_SUPER_BIG_NET_PCT
        } else null
        conditions.add(superBigHit)

        // 6. 大单净流入占比
        val bigHit = if (ScreenerConfig.ENABLE_BIG_CHECK) {
            if (stock.bigNetPct.isNaN()) null else stock.bigNetPct >= ScreenerConfig.MIN_BIG_NET_PCT
        } else null
        conditions.add(bigHit)

        // 7. 热点题材
        var sectorHitNames = emptyList<String>()
        var sectorHit: Boolean? = null
        if (ScreenerConfig.ENABLE_SECTOR_CHECK) {
            val sectorName = runCatching { SectorApi.fetchStockSectorName(stock.code) }.getOrNull()
            sectorHitNames = if (sectorName != null) {
                hotSectors.filter { hot -> sectorName.contains(hot) || hot.contains(sectorName) }
            } else emptyList()
            sectorHit = if (hotSectors.isEmpty()) null else sectorHitNames.isNotEmpty()
        }
        conditions.add(sectorHit)

        // 8. 利好消息
        var matchedTitles = emptyList<String>()
        var newsHit: Boolean? = null
        if (ScreenerConfig.ENABLE_NEWS_CHECK) {
            val titles = runCatching { NewsApi.fetchRecentTitles(stock.code) }.getOrDefault(emptyList())
            matchedTitles = NewsApi.matchKeywords(titles, ScreenerConfig.POSITIVE_KEYWORDS)
            newsHit = if (titles.isEmpty()) null else matchedTitles.isNotEmpty()
        }
        conditions.add(newsHit)

        // 9. 股性活跃
        var activityDetail = ""
        var activityHit: Boolean? = null
        if (ScreenerConfig.ENABLE_ACTIVITY_CHECK) {
            val activityStats = runCatching { HistoryApi.fetchActivityStats(stock.code) }.getOrNull()
            activityDetail = activityStats?.summary() ?: ""
            activityHit = activityStats?.isActive()
        }
        conditions.add(activityHit)

        val evaluated = conditions.count { it != null }
        val met = conditions.count { it == true }

        return stock.copy(
            auctionPriceUp = auctionPriceUp,
            auctionVolumeSurgeRatio = surgeRatio,
            auctionVolumeSurgeHit = surgeHit,
            auctionVolume925 = auctionDetail.volume925,
            auctionAmountWan925 = auctionDetail.amountWan925,
            auctionVolumeHit = volumeHit,
            auctionAmountHit = amountHit,
            superBigNetHit = superBigHit,
            bigNetHit = bigHit,
            hotSectorNames = sectorHitNames,
            hotSectorHit = sectorHit,
            newsTitles = matchedTitles,
            newsHit = newsHit,
            activityDetail = activityDetail,
            activityHit = activityHit,
            conditionsMet = met,
            conditionsEvaluated = evaluated
        )
    }
}
