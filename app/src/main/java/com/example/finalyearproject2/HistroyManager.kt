package com.example.finalyearproject2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/*
 * HistoryManager
 * ─────────────────────────────────────────────────────────────────────────────
 * Stores and retrieves daily energy snapshots to/from local phone storage.
 */
object HistoryManager {

    private const val FILE_NAME = "energy_history.json"
    private val fmtKey   = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val fmtLabel = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())
    private val fmtYM    = SimpleDateFormat("yyyy-MM", Locale.getDefault())

    data class DayRecord(
        val date:     String,
        val dayLabel: String,
        val bulb1:    Double,
        val bulb2:    Double,
        val fan1:     Double,
        val fan2:     Double,
        val total:    Double,
        val limitHit: Boolean
    )

    fun saveTodaySnapshot(
        ctx:   Context,
        bulb1: Double,
        bulb2: Double,
        fan1:  Double,
        fan2:  Double,
        date:  Date = Date()
    ) {
        val total    = bulb1 + bulb2 + fan1 + fan2
        val todayKey = fmtKey.format(date)
        val label    = fmtLabel.format(date)
        val hitLimit = total >= MainActivity.DAILY_LIMIT_KWH

        val records = loadAll(ctx).toMutableList()

        records.removeAll { it.date == todayKey }
        records.add(0, DayRecord(todayKey, label, bulb1, bulb2, fan1, fan2, total, hitLimit))

        val trimmed = records.take(31)

        val cal = Calendar.getInstance()
        cal.time = date
        if (cal.get(Calendar.DAY_OF_MONTH) >= 7) {
            val cleanupCal = Calendar.getInstance().apply { time = date }
            cleanupCal.add(Calendar.MONTH, -1)
            val prevMonth = fmtYM.format(cleanupCal.time)
            val cleaned   = trimmed.filterNot { it.date.startsWith(prevMonth) }
            saveAll(ctx, cleaned)
        } else {
            saveAll(ctx, trimmed)
        }
    }

    fun purgeOldDataIfNeeded(ctx: Context) {
        val records = loadAll(ctx)
        val cal = Calendar.getInstance()

        if (cal.get(Calendar.DAY_OF_MONTH) >= 7) {
            cal.add(Calendar.MONTH, -1)
            val prevMonth = fmtYM.format(cal.time)
            val hasPrev   = records.any { it.date.startsWith(prevMonth) }

            if (hasPrev) {
                val cleaned = records.filterNot { it.date.startsWith(prevMonth) }
                saveAll(ctx, cleaned)
            }
        }
    }

    fun removeSuspiciousRecords(ctx: Context) {
        val records  = loadAll(ctx)
        val cleaned  = records.filter { it.total <= 24.0 }
        if (cleaned.size != records.size) saveAll(ctx, cleaned)
    }

    fun loadAll(ctx: Context): List<DayRecord> {
        val file = historyFile(ctx)
        if (!file.exists()) return emptyList()

        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DayRecord(
                    date     = o.getString("date"),
                    dayLabel = o.getString("dayLabel"),
                    bulb1    = o.getDouble("bulb1"),
                    bulb2    = o.getDouble("bulb2"),
                    fan1     = o.getDouble("fan1"),
                    fan2     = o.getDouble("fan2"),
                    total    = o.getDouble("total"),
                    limitHit = o.getBoolean("limitHit")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun loadMonth(ctx: Context, yearMonth: String): List<DayRecord> =
        loadAll(ctx).filter { it.date.startsWith(yearMonth) }

    fun availableMonths(ctx: Context): List<String> =
        loadAll(ctx)
            .map  { it.date.substring(0, 7) }
            .distinct()
            .sortedDescending()

    fun monthDisplayName(yearMonth: String): String = try {
        val parse  = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val format = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        format.format(parse.parse(yearMonth)!!)
    } catch (_: Exception) { yearMonth }

    private fun saveAll(ctx: Context, records: List<DayRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("date",     r.date)
                put("dayLabel", r.dayLabel)
                put("bulb1",    r.bulb1)
                put("bulb2",    r.bulb2)
                put("fan1",     r.fan1)
                put("fan2",     r.fan2)
                put("total",    r.total)
                put("limitHit", r.limitHit)
            })
        }
        historyFile(ctx).writeText(arr.toString())
    }

    private fun historyFile(ctx: Context): File =
        File(ctx.filesDir, FILE_NAME)
}
