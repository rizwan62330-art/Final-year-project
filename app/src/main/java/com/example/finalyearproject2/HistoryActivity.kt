package com.example.finalyearproject2

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/*
 * HistoryActivity
 * ─────────────────────────────────────────────────────────────────────────────
 * Shows monthly energy history stored on the phone.
 * Updated to refresh on Resume to ensure latest Worker data is visible.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var spinnerMonth: Spinner
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvMonthTotal: TextView
    private lateinit var tvMonthAvg: TextView
    private lateinit var tvMonthDays: TextView

    private lateinit var adapter: HistoryDayAdapter
    private var months = listOf<String>()   // "yyyy-MM" list

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Energy History" // Give it a title
        }

        bindViews()
        setupRecyclerView()
    }

    // Refresh data whenever user returns to this screen
    override fun onResume() {
        super.onResume()
        loadMonths()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun bindViews() {
        spinnerMonth  = findViewById(R.id.spinner_month)
        recyclerView  = findViewById(R.id.rv_history)
        tvEmpty       = findViewById(R.id.tv_history_empty)
        tvMonthTotal  = findViewById(R.id.tv_month_total)
        tvMonthAvg    = findViewById(R.id.tv_month_avg)
        tvMonthDays   = findViewById(R.id.tv_month_days)
    }

    private fun setupRecyclerView() {
        adapter = HistoryDayAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun loadMonths() {
        months = HistoryManager.availableMonths(this)

        if (months.isEmpty()) {
            showEmpty()
            // Reset stats to zero if no data
            tvMonthTotal.text = "0.00 kWh"
            tvMonthAvg.text   = "0.00 kWh/day"
            tvMonthDays.text  = "0 days recorded"
            return
        }

        val labels = months.map { HistoryManager.monthDisplayName(it) }

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMonth.adapter = spinnerAdapter

        spinnerMonth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                loadMonthData(months[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Always show newest data first
        loadMonthData(months[0])
    }

    private fun loadMonthData(yearMonth: String) {
        val records = HistoryManager.loadMonth(this, yearMonth)

        if (records.isEmpty()) {
            showEmpty()
            return
        }

        hideEmpty()
        adapter.updateRecords(records)

        val totalKwh = records.sumOf { it.total }
        val avgKwh   = totalKwh / records.size

        tvMonthTotal.text = "%.2f kWh".format(totalKwh)
        tvMonthAvg.text   = "%.2f kWh/day".format(avgKwh)
        tvMonthDays.text  = "${records.size} days recorded"
    }

    private fun showEmpty() {
        tvEmpty.visibility      = View.VISIBLE
        recyclerView.visibility = View.GONE
    }

    private fun hideEmpty() {
        tvEmpty.visibility      = View.GONE
        recyclerView.visibility = View.VISIBLE
    }
}