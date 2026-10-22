package com.example.astockscreener

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 设置页面：所有选股参数和开关都在这里调，不用改代码重新编译。
 * 用程序化生成UI（不是写死的XML表单），因为参数项太多，动态生成更好维护：
 * 要加新参数只需要在 buildFields() 里加一行，不用再去改布局文件。
 *
 * 保存时直接写回 ScreenerConfig 的全局变量 + 持久化到 SharedPreferences，
 * 回到主界面（onResume）会自动用新参数重新刷新一次。
 */
class SettingsActivity : AppCompatActivity() {

    // 数值类参数描述：标签、单位说明、当前值的getter/setter
    private data class NumberField(
        val label: String,
        val hint: String,
        val get: () -> Double,
        val set: (Double) -> Unit
    )

    // 开关类参数描述
    private data class ToggleField(
        val label: String,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit
    )

    private val numberInputs = mutableListOf<Pair<NumberField, EditText>>()
    private val toggleInputs = mutableListOf<Pair<ToggleField, SwitchMaterial>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbarSettings)
        toolbar.title = "参数设置"
        toolbar.setNavigationOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.containerSettings)
        buildSections(container)

        findViewById<View>(R.id.btnSaveSettings).setOnClickListener {
            saveAll()
            Toast.makeText(this, "已保存，回到主界面会自动按新参数刷新", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun buildSections(container: LinearLayout) {
        addSectionTitle(container, "① 资金驱动主条件（硬性，不满足直接排除）")
        addNumberField(container, NumberField(
            "现价上限(元)", "例如 8",
            { ScreenerConfig.MAX_PRICE }, { ScreenerConfig.MAX_PRICE = it }
        ))
        addNumberField(container, NumberField(
            "流通市值上限(亿元)", "例如 120",
            { ScreenerConfig.MAX_CIRC_MARKET_CAP_YI }, { ScreenerConfig.MAX_CIRC_MARKET_CAP_YI = it }
        ))
        addNumberField(container, NumberField(
            "主力净流入占比下限(%)", "例如 3",
            { ScreenerConfig.MIN_NET_INFLOW_PCT }, { ScreenerConfig.MIN_NET_INFLOW_PCT = it }
        ))
        addToggleField(container, ToggleField(
            "只看主板+创业板（排除科创板/北交所）",
            { ScreenerConfig.RESTRICT_TO_MAIN_AND_CHINEXT }, { ScreenerConfig.RESTRICT_TO_MAIN_AND_CHINEXT = it }
        ))

        addSectionTitle(container, "② 资金强度细分（开盘后持续监测，每次刷新都更新）")
        addToggleField(container, ToggleField(
            "要求超大单净流入占比达标",
            { ScreenerConfig.ENABLE_SUPER_BIG_CHECK }, { ScreenerConfig.ENABLE_SUPER_BIG_CHECK = it }
        ))
        addNumberField(container, NumberField(
            "超大单净流入占比下限(%)", "例如 2",
            { ScreenerConfig.MIN_SUPER_BIG_NET_PCT }, { ScreenerConfig.MIN_SUPER_BIG_NET_PCT = it }
        ))
        addToggleField(container, ToggleField(
            "要求大单净流入占比达标",
            { ScreenerConfig.ENABLE_BIG_CHECK }, { ScreenerConfig.ENABLE_BIG_CHECK = it }
        ))
        addNumberField(container, NumberField(
            "大单净流入占比下限(%)", "例如 2",
            { ScreenerConfig.MIN_BIG_NET_PCT }, { ScreenerConfig.MIN_BIG_NET_PCT = it }
        ))

        addSectionTitle(container, "③ 竞价加分项（窗口固定9:20-9:25）")
        addToggleField(container, ToggleField(
            "竞价价格上行(9:20<9:25)",
            { ScreenerConfig.ENABLE_AUCTION_PRICE_CHECK }, { ScreenerConfig.ENABLE_AUCTION_PRICE_CHECK = it }
        ))
        addToggleField(container, ToggleField(
            "竞价量比(9:25量/9:20量)达标",
            { ScreenerConfig.ENABLE_AUCTION_VOLUME_RATIO_CHECK }, { ScreenerConfig.ENABLE_AUCTION_VOLUME_RATIO_CHECK = it }
        ))
        addNumberField(container, NumberField(
            "竞价量比下限", "例如 1.5",
            { ScreenerConfig.MIN_VOLUME_SURGE_RATIO }, { ScreenerConfig.MIN_VOLUME_SURGE_RATIO = it }
        ))
        addToggleField(container, ToggleField(
            "竞价成交量(9:25)达标",
            { ScreenerConfig.ENABLE_AUCTION_VOLUME_CHECK }, { ScreenerConfig.ENABLE_AUCTION_VOLUME_CHECK = it }
        ))
        addNumberField(container, NumberField(
            "竞价成交量下限(手)", "例如 50000",
            { ScreenerConfig.MIN_AUCTION_VOLUME }, { ScreenerConfig.MIN_AUCTION_VOLUME = it }
        ))
        addToggleField(container, ToggleField(
            "竞价成交额(9:25)达标",
            { ScreenerConfig.ENABLE_AUCTION_AMOUNT_CHECK }, { ScreenerConfig.ENABLE_AUCTION_AMOUNT_CHECK = it }
        ))
        addNumberField(container, NumberField(
            "竞价成交额下限(万元)", "例如 500",
            { ScreenerConfig.MIN_AUCTION_AMOUNT_WAN }, { ScreenerConfig.MIN_AUCTION_AMOUNT_WAN = it }
        ))

        addSectionTitle(container, "④ 热点题材 / 利好消息")
        addToggleField(container, ToggleField(
            "启用热点题材匹配",
            { ScreenerConfig.ENABLE_SECTOR_CHECK }, { ScreenerConfig.ENABLE_SECTOR_CHECK = it }
        ))
        addNumberField(container, NumberField(
            "热点板块取前N名", "例如 10",
            { ScreenerConfig.TOP_N_HOT_SECTOR.toDouble() }, { ScreenerConfig.TOP_N_HOT_SECTOR = it.toInt() }
        ))
        addToggleField(container, ToggleField(
            "启用利好消息关键词匹配",
            { ScreenerConfig.ENABLE_NEWS_CHECK }, { ScreenerConfig.ENABLE_NEWS_CHECK = it }
        ))

        addSectionTitle(container, "⑤ 近一年股性活跃")
        addToggleField(container, ToggleField(
            "启用股性活跃检测",
            { ScreenerConfig.ENABLE_ACTIVITY_CHECK }, { ScreenerConfig.ENABLE_ACTIVITY_CHECK = it }
        ))
        addNumberField(container, NumberField(
            "日均换手率下限(%)", "例如 3",
            { ScreenerConfig.MIN_AVG_TURNOVER_RATE }, { ScreenerConfig.MIN_AVG_TURNOVER_RATE = it }
        ))
        addNumberField(container, NumberField(
            "日均振幅下限(%)", "例如 4",
            { ScreenerConfig.MIN_AVG_AMPLITUDE }, { ScreenerConfig.MIN_AVG_AMPLITUDE = it }
        ))
        addNumberField(container, NumberField(
            "近一年涨停次数下限", "例如 3",
            { ScreenerConfig.MIN_LIMIT_UP_TIMES.toDouble() }, { ScreenerConfig.MIN_LIMIT_UP_TIMES = it.toInt() }
        ))

        addSectionTitle(container, "⑥ 加分汇总 / 性能")
        addNumberField(container, NumberField(
            "加分项至少命中几项才入选", "5项里至少命中，例如 2",
            { ScreenerConfig.MIN_CONDITIONS_MET.toDouble() }, { ScreenerConfig.MIN_CONDITIONS_MET = it.toInt() }
        ))
        addNumberField(container, NumberField(
            "最多对前几只候选股做加分评估", "太大会很慢，例如 30",
            { ScreenerConfig.MAX_STOCKS_TO_ENRICH.toDouble() }, { ScreenerConfig.MAX_STOCKS_TO_ENRICH = it.toInt() }
        ))
        addNumberField(container, NumberField(
            "自动刷新间隔(秒)", "建议不低于10秒，避免被限流",
            { ScreenerConfig.POLL_INTERVAL_MS / 1000.0 }, { ScreenerConfig.POLL_INTERVAL_MS = (it * 1000).toLong() }
        ))
    }

    private fun addSectionTitle(container: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 32, 0, 8)
        }
        container.addView(tv)
    }

    private fun addNumberField(container: LinearLayout, field: NumberField) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val label = TextView(this).apply {
            text = field.label
            textSize = 13f
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = field.hint
            setText(formatNumber(field.get()))
        }
        row.addView(label)
        row.addView(input)
        container.addView(row)
        numberInputs.add(field to input)
    }

    private fun addToggleField(container: LinearLayout, field: ToggleField) {
        val sw = SwitchMaterial(this).apply {
            text = field.label
            textSize = 13f
            isChecked = field.get()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        container.addView(sw)
        toggleInputs.add(field to sw)
    }

    private fun formatNumber(value: Double): String {
        return if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }

    private fun saveAll() {
        for ((field, editText) in numberInputs) {
            val value = editText.text.toString().toDoubleOrNull()
            if (value != null) field.set(value)
            // 输入框留空或者格式不对就跳过，保留原值，不强行报错打断保存流程
        }
        for ((field, switchView) in toggleInputs) {
            field.set(switchView.isChecked)
        }
        ScreenerConfig.save(this)
    }
}
