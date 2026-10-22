package com.example.astockscreener

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScreenerUiState(
    val stocks: List<StockItem> = emptyList(),
    val isLoading: Boolean = false,
    val loadingStage: String = "",
    val lastUpdateText: String = "尚未刷新",
    val errorText: String? = null,
    val isAutoRefreshing: Boolean = false
)

/**
 * 用 AndroidViewModel 而不是普通 ViewModel，是因为集合竞价加分项
 * (AuctionSnapshotManager) 需要 Context 读写本地快照数据。
 */
class ScreenerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ScreenerUiState())
    val uiState: StateFlow<ScreenerUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    fun refreshOnce() {
        viewModelScope.launch { doRefresh() }
    }

    fun startAutoRefresh() {
        if (pollingJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(isAutoRefreshing = true)
        pollingJob = viewModelScope.launch {
            while (true) {
                doRefresh()
                delay(ScreenerConfig.POLL_INTERVAL_MS)
            }
        }
    }

    fun stopAutoRefresh() {
        pollingJob?.cancel()
        pollingJob = null
        _uiState.value = _uiState.value.copy(isAutoRefreshing = false)
    }

    /** 供"手动记录竞价快照"按钮调用，onDone 的参数 null=成功，非null=失败原因 */
    fun captureAuctionSnapshot(tag: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                AuctionSnapshotManager.captureAndStore(getApplication(), tag)
            }
            onDone(error)
        }
    }

    private suspend fun doRefresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorText = null, loadingStage = "拉取资金流向...")
        try {
            val snapshot = withContext(Dispatchers.IO) { EastmoneyApi.fetchFundFlowSnapshot() }
            val primary = ScreenerRepository.filterAndRank(snapshot)

            _uiState.value = _uiState.value.copy(loadingStage = "补充竞价/题材/消息/活跃度...")
            val context = getApplication<Application>()
            val enrichedAll = EnrichmentService.enrich(context, primary)

            val enrichedCount = minOf(primary.size, ScreenerConfig.MAX_STOCKS_TO_ENRICH)
            val enrichedPart = enrichedAll.take(enrichedCount)
            val remainderPart = enrichedAll.drop(enrichedCount) // 排名靠后、没来得及做加分评估的

            val passed = enrichedPart.filter { stock ->
                val effectiveThreshold = minOf(
                    ScreenerConfig.MIN_CONDITIONS_MET,
                    stock.conditionsEvaluated.coerceAtLeast(1)
                )
                stock.conditionsMet >= effectiveThreshold
            }

            val finalList = passed + remainderPart
            val now = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
            _uiState.value = _uiState.value.copy(
                stocks = finalList,
                isLoading = false,
                loadingStage = "",
                lastUpdateText = "最后更新 $now · 资金驱动候选${primary.size}只 · 加分评估${enrichedCount}只（免费数据源，延迟不保证，仅供参考）"
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                loadingStage = "",
                errorText = "刷新失败：${e.message ?: "未知错误"}"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
