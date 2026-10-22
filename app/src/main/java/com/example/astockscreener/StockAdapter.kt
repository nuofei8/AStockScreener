package com.example.astockscreener

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class StockAdapter(private var items: List<StockItem> = emptyList()) :
    RecyclerView.Adapter<StockAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvCode: TextView = view.findViewById(R.id.tvCode)
        val tvPrice: TextView = view.findViewById(R.id.tvPrice)
        val tvInflow: TextView = view.findViewById(R.id.tvInflow)
        val tvBadges: TextView = view.findViewById(R.id.tvBadges)
        val tvDetail: TextView = view.findViewById(R.id.tvDetail)
    }

    fun submitList(newItems: List<StockItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stock, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvCode.text = item.code
        holder.tvPrice.text = String.format(Locale.CHINA, "¥%.2f  %+.2f%%", item.price, item.changePct)
        holder.tvInflow.text = String.format(Locale.CHINA, "净流入占比 %.2f%%", item.netInflowPct)

        val badges = mutableListOf<String>()
        if (item.auctionPriceUp == true) badges.add("竞价上行")
        if (item.auctionVolumeSurgeHit == true) badges.add("竞价量比")
        if (item.auctionVolumeHit == true) badges.add("竞价放量")
        if (item.auctionAmountHit == true) badges.add("竞价金额")
        if (item.superBigNetHit == true) badges.add("超大单")
        if (item.bigNetHit == true) badges.add("大单")
        if (item.hotSectorHit == true) badges.add("热点题材")
        if (item.newsHit == true) badges.add("利好消息")
        if (item.activityHit == true) badges.add("股性活跃")

        if (item.conditionsEvaluated > 0) {
            holder.tvBadges.visibility = View.VISIBLE
            holder.tvBadges.text = if (badges.isNotEmpty()) {
                "加分 ${item.conditionsMet}/${item.conditionsEvaluated}：${badges.joinToString(" · ")}"
            } else {
                "加分 0/${item.conditionsEvaluated}：暂无命中加分项"
            }
        } else {
            holder.tvBadges.visibility = View.GONE
        }

        val detailParts = mutableListOf<String>()
        if (!item.superBigNetPct.isNaN() || !item.bigNetPct.isNaN()) {
            val superBigStr = if (item.superBigNetPct.isNaN()) "-" else String.format(Locale.CHINA, "%.2f%%", item.superBigNetPct)
            val bigStr = if (item.bigNetPct.isNaN()) "-" else String.format(Locale.CHINA, "%.2f%%", item.bigNetPct)
            detailParts.add("超大单占比$superBigStr · 大单占比$bigStr")
        }
        if (item.auctionAmountWan925 != null) {
            detailParts.add(String.format(Locale.CHINA, "竞价成交额约%.0f万元", item.auctionAmountWan925))
        }
        if (item.hotSectorNames.isNotEmpty()) detailParts.add("板块: ${item.hotSectorNames.joinToString("/")}")
        if (item.newsTitles.isNotEmpty()) detailParts.add("消息: ${item.newsTitles.first()}")
        if (item.activityDetail.isNotEmpty()) detailParts.add(item.activityDetail)
        if (detailParts.isNotEmpty()) {
            holder.tvDetail.visibility = View.VISIBLE
            holder.tvDetail.text = detailParts.joinToString("\n")
        } else {
            holder.tvDetail.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            val context = holder.itemView.context
            val intent = Intent(context, StockDetailActivity::class.java).apply {
                putExtra(StockDetailActivity.EXTRA_CODE, item.code)
                putExtra(StockDetailActivity.EXTRA_NAME, item.name)
            }
            context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int = items.size
}
