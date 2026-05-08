package com.example.finalyearproject2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/*
 * HistoryDayAdapter
 * Binds a list of DayRecord objects into the history RecyclerView.
 */
class HistoryDayAdapter(
    private var records: List<HistoryManager.DayRecord>
) : RecyclerView.Adapter<HistoryDayAdapter.DayVH>() {

    inner class DayVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate:      TextView = view.findViewById(R.id.tv_hist_date)
        val tvDayLabel:  TextView = view.findViewById(R.id.tv_hist_day_label)
        val tvLimitBadge:TextView = view.findViewById(R.id.tv_hist_limit_badge)
        val tvTotal:     TextView = view.findViewById(R.id.tv_hist_total)
        val tvBulb1:     TextView = view.findViewById(R.id.tv_hist_bulb1)
        val tvBulb2:     TextView = view.findViewById(R.id.tv_hist_bulb2)
        val tvFan1:      TextView = view.findViewById(R.id.tv_hist_fan1)
        val tvFan2:      TextView = view.findViewById(R.id.tv_hist_fan2)
        val stripe:      View    = view.findViewById(R.id.hist_stripe)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_day, parent, false)
        return DayVH(view)
    }

    override fun onBindViewHolder(holder: DayVH, position: Int) {
        val r   = records[position]
        val ctx = holder.itemView.context
        val df  = "%.3f"

        holder.tvDate.text     = r.date
        holder.tvDayLabel.text = r.dayLabel
        holder.tvTotal.text    = "%.2f kWh".format(r.total)
        holder.tvBulb1.text    = df.format(r.bulb1)
        holder.tvBulb2.text    = df.format(r.bulb2)
        holder.tvFan1.text     = df.format(r.fan1)
        holder.tvFan2.text     = df.format(r.fan2)

        // Colour total + stripe based on limit
        if (r.limitHit) {
            holder.tvTotal.setTextColor(ctx.getColor(R.color.status_offline))
            holder.stripe.setBackgroundColor(ctx.getColor(R.color.status_offline))
            holder.tvLimitBadge.visibility = View.VISIBLE
        } else {
            holder.tvTotal.setTextColor(ctx.getColor(R.color.accent_green))
            holder.stripe.setBackgroundColor(ctx.getColor(R.color.accent_green))
            holder.tvLimitBadge.visibility = View.GONE
        }
    }

    override fun getItemCount() = records.size

    fun updateRecords(newRecords: List<HistoryManager.DayRecord>) {
        records = newRecords
        notifyDataSetChanged()
    }
}
