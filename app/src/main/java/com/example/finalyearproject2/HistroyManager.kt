package com.example.finalyearproject2

import android.content.Context
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/*
 * HistoryManager
 * ─────────────────────────────────────────────────────────────────────────────
 * Stores and retrieves daily energy snapshots.
 * Updated to calculate consumption independently from Firebase.
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

    /**
     * NEW: Fetches raw data from Firebase and saves a snapshot.
     * Call this from MidnightResetWorker BEFORE resetting.
     */
    suspend fun fetchAndSaveDailySnapshot(ctx: Context) {
        val todayKey = fmtKey.format(Date())

        // 1. Access the Baselines saved by MainActivity when the day started
        val bPrefs = ctx.getSharedPreferences(MainActivity.PREFS_BASELINE, Context.MODE_PRIVATE)

        // 2. Connect to Firebase to get the RAW cumulative units
        val db = FirebaseDatabase
            .getInstance("https://final-year-project-a75d4-default-rtdb.firebaseio.com/")
            .getReference("energy_monitor")

        val results = mutableMapOf<String, Double>()

        // 3. Calculate: (Current Raw Firebase Units) - (Stored Morning Baseline)
        MainActivity.DEVICES.forEach { device ->
            val rawFirebase = try {
                db.child(device).child("units").get().await().getValue(Double::class.java) ?: 0.0
            } catch (e: Exception) { 0.0 }

            val baseline = bPrefs.getString("${device}_$todayKey", null)?.toDoubleOrNull() ?: rawFirebase

            results[device] = (rawFirebase - baseline).coerceAtLeast(0.0)
        }

        // 4. Save to JSON
        saveTodaySnapshot(
            ctx,
            results["bulb1"] ?: 0.0,
            results["bulb2"] ?: 0.0,
            results["fan1"]  ?: 0.0,
            results["fan2"]  ?: 0.0
        )
    }

    /**
     * Saves the provided values to the local JSON history file.
     */
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

        // Remove existing entry for today if it exists, then add the new one at the top
        records.removeAll { it.date == todayKey }
        records.add(0, DayRecord(todayKey, label, bulb1, bulb2, fan1, fan2, total, hitLimit))

        // Keep last 31 days or purge old month data
        val trimmed = records.take(60) // Increased buffer for month-viewing
        saveAll(ctx, trimmed)
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
        val cleaned  = records.filter { it.total <= 24.0 } // 24kWh is a very high daily max
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