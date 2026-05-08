package com.example.finalyearproject2

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.database.*
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference
    private val toggleListeners = mutableMapOf<String, ValueEventListener>()
    private val sensorListeners = mutableMapOf<String, ValueEventListener>()
    private var resetListener: ValueEventListener? = null

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvLastUpdated: TextView
    private lateinit var tvTotalUnits: TextView
    private lateinit var tvUnitsLimit: TextView
    private lateinit var btnHistory: TextView
    private lateinit var tvBulb1Voltage: TextView
    private lateinit var tvBulb1Current: TextView
    private lateinit var tvBulb1Units: TextView
    private lateinit var btnBulb1Toggle: TextView
    private lateinit var tvBulb2Voltage: TextView
    private lateinit var tvBulb2Current: TextView
    private lateinit var tvBulb2Units: TextView
    private lateinit var btnBulb2Toggle: TextView
    private lateinit var tvFan1Voltage: TextView
    private lateinit var tvFan1Current: TextView
    private lateinit var tvFan1Units: TextView
    private lateinit var btnFan1Toggle: TextView
    private lateinit var tvFan2Voltage: TextView
    private lateinit var tvFan2Current: TextView
    private lateinit var tvFan2Units: TextView
    private lateinit var btnFan2Toggle: TextView

    // ── State ─────────────────────────────────────────────────────────────────
    private val toggleState = mutableMapOf(
        "bulb1" to false, "bulb2" to false,
        "fan1"  to false, "fan2"  to false
    )

    // today's consumption — loaded from SharedPreferences on every open
    val todayUnits = mutableMapOf(
        "bulb1" to 0.0, "bulb2" to 0.0,
        "fan1"  to 0.0, "fan2"  to 0.0
    )

    // in-memory baseline — set once per session from SharedPreferences
    private val sessionStart = mutableMapOf(
        "bulb1" to -1.0, "bulb2" to -1.0,
        "fan1"  to -1.0, "fan2"  to -1.0
    )

    private val df         = DecimalFormat("#.###")
    private val prevValues = mutableMapOf<String, Double>()
    private var lastLimitNotifTime = 0L
    private val NOTIF_INTERVAL_MS  = 60 * 60 * 1000L

    companion object {
        const val DAILY_LIMIT_KWH  = 6.5
        const val PREFS_BASELINE   = "daily_baseline"
        const val PREFS_TODAY_VALS = "today_units"
        val DEVICES = listOf("bulb1", "bulb2", "fan1", "fan2")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SharedPreferences — ALL writes use commit() not apply()
    //  commit() is synchronous — guaranteed written to disk even if OS kills
    //  the app process immediately after. apply() is async and can be lost.
    // ══════════════════════════════════════════════════════════════════════════
    private fun bPrefs(): SharedPreferences =
        getSharedPreferences(PREFS_BASELINE,   MODE_PRIVATE)
    private fun tPrefs(): SharedPreferences =
        getSharedPreferences(PREFS_TODAY_VALS, MODE_PRIVATE)

    private fun dateKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    // Read saved today-unit for a device (null = never saved today)
    fun readTodayUnit(device: String): Double =
        tPrefs().getString("${device}_${dateKey()}", null)?.toDoubleOrNull() ?: 0.0

    // Write today-unit — commit() guarantees disk write before returning
    private fun writeTodayUnit(device: String, value: Double) {
        tPrefs().edit().putString("${device}_${dateKey()}", value.toString()).commit()
    }

    // Read baseline for today (null = not set yet)
    private fun readBaseline(device: String): Double? =
        bPrefs().getString("${device}_${dateKey()}", null)?.toDoubleOrNull()

    // Write baseline — commit() guarantees disk write
    private fun writeBaseline(device: String, value: Double) {
        bPrefs().edit().putString("${device}_${dateKey()}", value.toString()).commit()
    }

    // Clear both prefs for a given date (used at midnight reset)
    private fun clearPrefsForDate(dk: String) {
        bPrefs().edit().apply { DEVICES.forEach { remove("${it}_$dk") }; commit() }
        tPrefs().edit().apply { DEVICES.forEach { remove("${it}_$dk") }; commit() }
    }

    // ═════════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        initFirebase()

        // ── Load saved values IMMEDIATELY — before any Firebase call ──────────
        // This is the single most important step. SharedPreferences is read
        // synchronously and always has the latest values (written with commit()).
        // The UI shows correct numbers the instant the app opens.
        DEVICES.forEach { device -> todayUnits[device] = readTodayUnit(device) }
        refreshUnitTextViews()
        refreshTotalUnits()

        setupCardClickListeners()
        setupToggleClickListeners()
        scheduleWorkers()
        NotificationHelper.createChannel(this)
        requestBatteryOptimisationExemption()
        checkAndSaveMissedYesterday()
        HistoryManager.purgeOldDataIfNeeded(this)
        HistoryManager.removeSuspiciousRecords(this)
        btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        // ── Attach Firebase listeners ─────────────────────────────────────────
        attachToggleListeners()
        attachSensorListeners()
        attachResetListener()
    }

    // ─── Views ────────────────────────────────────────────────────────────────
    private fun bindViews() {
        tvStatus       = findViewById(R.id.tv_status)
        tvLastUpdated  = findViewById(R.id.tv_last_updated)
        tvTotalUnits   = findViewById(R.id.tv_total_units)
        tvUnitsLimit   = findViewById(R.id.tv_units_limit)
        btnHistory     = findViewById(R.id.btn_history)
        tvBulb1Voltage = findViewById(R.id.tv_bulb1_voltage)
        tvBulb1Current = findViewById(R.id.tv_bulb1_current)
        tvBulb1Units   = findViewById(R.id.tv_bulb1_units)
        btnBulb1Toggle = findViewById(R.id.btn_bulb1_toggle)
        tvBulb2Voltage = findViewById(R.id.tv_bulb2_voltage)
        tvBulb2Current = findViewById(R.id.tv_bulb2_current)
        tvBulb2Units   = findViewById(R.id.tv_bulb2_units)
        btnBulb2Toggle = findViewById(R.id.btn_bulb2_toggle)
        tvFan1Voltage  = findViewById(R.id.tv_fan1_voltage)
        tvFan1Current  = findViewById(R.id.tv_fan1_current)
        tvFan1Units    = findViewById(R.id.tv_fan1_units)
        btnFan1Toggle  = findViewById(R.id.btn_fan1_toggle)
        tvFan2Voltage  = findViewById(R.id.tv_fan2_voltage)
        tvFan2Current  = findViewById(R.id.tv_fan2_current)
        tvFan2Units    = findViewById(R.id.tv_fan2_units)
        btnFan2Toggle  = findViewById(R.id.btn_fan2_toggle)
    }

    private fun initFirebase() {
        database = FirebaseDatabase
            .getInstance("https://final-year-project-a75d4-default-rtdb.firebaseio.com/")
            .getReference("energy_monitor")
        database.keepSynced(true)
    }

    private fun refreshUnitTextViews() {
        tvBulb1Units.text = df.format(todayUnits["bulb1"] ?: 0.0)
        tvBulb2Units.text = df.format(todayUnits["bulb2"] ?: 0.0)
        tvFan1Units.text  = df.format(todayUnits["fan1"]  ?: 0.0)
        tvFan2Units.text  = df.format(todayUnits["fan2"]  ?: 0.0)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SENSOR LISTENER
    //  For each device, calculates daily = firebaseUnits - baseline.
    //  Both baseline and daily are saved with commit() immediately.
    // ══════════════════════════════════════════════════════════════════════════
    private fun attachSensorListeners() {
        DEVICES.forEach { device ->
            val listener = object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (!snap.exists()) return
                    fun d(f: String) = snap.child(f).getValue(Double::class.java) ?: 0.0

                    val voltage       = d("voltage")
                    val current       = d("current")
                    val firebaseUnits = d("units")  // ESP32 cumulative counter

                    // ── Set baseline once per session ─────────────────────────
                    if ((sessionStart[device] ?: -1.0) < 0.0) {
                        val savedBaseline = readBaseline(device)
                        val existingToday = readTodayUnit(device)

                        val baseline = when {
                            // Baseline exists — use it (normal case after restart)
                            savedBaseline != null -> savedBaseline

                            // No baseline but have today data — reconstruct baseline
                            // so we don't lose accumulated units
                            existingToday > 0.0 -> {
                                val reconstructed = (firebaseUnits - existingToday)
                                    .coerceAtLeast(0.0)
                                writeBaseline(device, reconstructed) // commit()
                                reconstructed
                            }

                            // First read of the day — set baseline = current value
                            else -> {
                                writeBaseline(device, firebaseUnits) // commit()
                                firebaseUnits
                            }
                        }
                        sessionStart[device] = baseline
                    }

                    val baseline = sessionStart[device]!!
                    val raw      = firebaseUnits - baseline

                    val daily: Double = when {
                        raw < -0.01 -> {
                            // ESP32 restarted and reset its counter mid-day.
                            // Rebase but KEEP what we already accumulated.
                            val keep = readTodayUnit(device)
                            sessionStart[device] = firebaseUnits
                            writeBaseline(device, firebaseUnits) // commit()
                            keep
                        }
                        raw < 0.0 -> readTodayUnit(device) // tiny float error — keep stored
                        else      -> raw
                    }

                    // ── Only accept increases — energy never decreases ─────────
                    val stored = todayUnits[device] ?: 0.0
                    if (daily > stored) {
                        todayUnits[device] = daily
                        writeTodayUnit(device, daily)          // commit() — safe on close
                        // Also push to Firebase so MidnightResetWorker can read it
                        database.child(device).child("today_units").setValue(daily)
                    }

                    // ── Update UI ─────────────────────────────────────────────
                    val (vView, iView, uView) = deviceValueViews(device) ?: return
                    val p = devicePrefix(device)
                    updateCard("${p}u", todayUnits[device]!!, uView)
                    if (toggleState[device] == true) {
                        updateCard("${p}v", voltage, vView)
                        updateCard("${p}i", current, iView)
                    }
                    refreshTotalUnits()

                    tvStatus.text = "● LIVE"
                    tvStatus.setTextColor(getColor(R.color.accent_cyan))
                    tvLastUpdated.text = "Updated: ${
                        SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
                    }"
                }
                override fun onCancelled(e: DatabaseError) {
                    tvStatus.text = "● OFFLINE"
                    tvStatus.setTextColor(getColor(R.color.status_offline))
                    tvLastUpdated.text = "No data — check connection"
                }
            }
            sensorListeners[device] = listener
            database.child(device).addValueEventListener(listener)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TOGGLE LISTENER
    // ══════════════════════════════════════════════════════════════════════════
    private fun attachToggleListeners() {
        DEVICES.forEach { device ->
            val listener = object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    val isOn = (snap.getValue(String::class.java) ?: "OFF")
                        .trim().uppercase() == "ON"
                    toggleState[device] = isOn
                    applyToggleUI(device, isOn)
                    refreshTotalUnits()
                }
                override fun onCancelled(e: DatabaseError) {}
            }
            toggleListeners[device] = listener
            database.child(device).child("toggle").addValueEventListener(listener)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  RESET LISTENER — midnight reset signal from MidnightResetWorker
    // ══════════════════════════════════════════════════════════════════════════
    private fun attachResetListener() {
        resetListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (snap.getValue(Boolean::class.java) != true) return

                clearPrefsForDate(dateKey()) // commit() inside
                DEVICES.forEach { device ->
                    sessionStart[device] = -1.0
                    todayUnits[device]   = 0.0
                }
                prevValues.clear()
                lastLimitNotifTime = 0L
                refreshTotalUnits()
                refreshUnitTextViews()
                // Clear flag so it doesn't trigger again on next restart
                database.child("reset_units").setValue(false)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        database.child("reset_units").addValueEventListener(resetListener!!)
    }

    // ── Total bar ─────────────────────────────────────────────────────────────
    private fun refreshTotalUnits() {
        val total = DEVICES.sumOf { todayUnits[it] ?: 0.0 }
        updateCard("tot", total, tvTotalUnits)
        tvTotalUnits.setTextColor(
            if (total >= DAILY_LIMIT_KWH) getColor(R.color.status_offline)
            else getColor(R.color.accent_green)
        )
        if (total >= DAILY_LIMIT_KWH) {
            val now = System.currentTimeMillis()
            if (now - lastLimitNotifTime >= NOTIF_INTERVAL_MS) {
                lastLimitNotifTime = now
                NotificationHelper.sendLimitNotification(this, total)
            }
        }
    }

    // ── Toggle button UI ──────────────────────────────────────────────────────
    private fun applyToggleUI(device: String, isOn: Boolean) {
        val (btn, vView, iView, _) = when (device) {
            "bulb1" -> Quad(btnBulb1Toggle, tvBulb1Voltage, tvBulb1Current, tvBulb1Units)
            "bulb2" -> Quad(btnBulb2Toggle, tvBulb2Voltage, tvBulb2Current, tvBulb2Units)
            "fan1"  -> Quad(btnFan1Toggle,  tvFan1Voltage,  tvFan1Current,  tvFan1Units)
            "fan2"  -> Quad(btnFan2Toggle,  tvFan2Voltage,  tvFan2Current,  tvFan2Units)
            else    -> return
        }
        if (isOn) {
            btn.text = "ON"; btn.setTextColor(getColor(R.color.accent_green))
            btn.setBackgroundResource(R.drawable.btn_toggle_on)
        } else {
            btn.text = "OFF"; btn.setTextColor(getColor(R.color.status_offline))
            btn.setBackgroundResource(R.drawable.btn_toggle_off)
            vView.text = "--"; iView.text = "--"
        }
    }

    private data class Quad(val btn: TextView, val v: TextView, val i: TextView, val u: TextView)

    // ── Card & toggle taps ────────────────────────────────────────────────────
    private fun setupCardClickListeners() {
        listOf(
            Triple(R.id.card_bulb1, "Bulb 1", "bulb1"),
            Triple(R.id.card_bulb2, "Bulb 2", "bulb2"),
            Triple(R.id.card_fan1,  "Fan 1",  "fan1"),
            Triple(R.id.card_fan2,  "Fan 2",  "fan2")
        ).forEach { (id, name, key) ->
            findViewById<CardView>(id).setOnClickListener {
                startActivity(Intent(this, DeviceDetailActivity::class.java).apply {
                    putExtra("device_name", name); putExtra("device_key", key)
                })
            }
        }
    }

    private fun setupToggleClickListeners() {
        mapOf(
            btnBulb1Toggle to "bulb1", btnBulb2Toggle to "bulb2",
            btnFan1Toggle  to "fan1",  btnFan2Toggle  to "fan2"
        ).forEach { (btn, device) ->
            btn.setOnClickListener {
                it.parent?.requestDisallowInterceptTouchEvent(true)
                val newState = !(toggleState[device] ?: false)
                database.child(device).child("toggle")
                    .setValue(if (newState) "ON" else "OFF")
                    .addOnFailureListener {
                        Snackbar.make(
                            findViewById(android.R.id.content),
                            "Could not signal $device. Check internet.",
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun deviceValueViews(device: String): Triple<TextView, TextView, TextView>? =
        when (device) {
            "bulb1" -> Triple(tvBulb1Voltage, tvBulb1Current, tvBulb1Units)
            "bulb2" -> Triple(tvBulb2Voltage, tvBulb2Current, tvBulb2Units)
            "fan1"  -> Triple(tvFan1Voltage,  tvFan1Current,  tvFan1Units)
            "fan2"  -> Triple(tvFan2Voltage,  tvFan2Current,  tvFan2Units)
            else    -> null
        }

    private fun devicePrefix(device: String) = when (device) {
        "bulb1" -> "b1"; "bulb2" -> "b2"; "fan1" -> "f1"; else -> "f2"
    }

    private fun updateCard(key: String, newVal: Double, tv: TextView) {
        if (!prevValues.containsKey(key)) {
            tv.text = df.format(newVal); prevValues[key] = newVal; return
        }
        val from = prevValues[key] ?: 0.0
        if (from == newVal) return
        ValueAnimator.ofFloat(from.toFloat(), newVal.toFloat()).apply {
            duration = 600; interpolator = DecelerateInterpolator()
            addUpdateListener { tv.text = df.format((it.animatedValue as Float).toDouble()) }
            start()
        }
        prevValues[key] = newVal
    }

    // ── Workers & background ──────────────────────────────────────────────────
    private fun scheduleWorkers() {
        DailyReportWorker.scheduleNext9PM(this)
        MidnightResetWorker.scheduleNextMidnight(this)
    }

    // ── Save yesterday if midnight worker was missed ───────────────────────────
    private fun checkAndSaveMissedYesterday() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val yesterday = sdf.format(java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -1)
        }.time)
        if (HistoryManager.loadAll(this).any { it.date == yesterday }) return

        // Read from PREFS_TODAY_VALS using yesterday's key — always correct
        val tPrefsLocal = getSharedPreferences(PREFS_TODAY_VALS, MODE_PRIVATE)
        fun readYest(dev: String) =
            tPrefsLocal.getString("${dev}_$yesterday", null)?.toDoubleOrNull() ?: 0.0

        val b1u = readYest("bulb1"); val b2u = readYest("bulb2")
        val f1u = readYest("fan1");  val f2u = readYest("fan2")
        if ((b1u + b2u + f1u + f2u) > 0.0) {
            HistoryManager.saveTodaySnapshot(this, b1u, b2u, f1u, f2u)
        }
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryOptimisationExemption() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                if (!prefs.getBoolean("battery_opt_asked", false)) {
                    prefs.edit().putBoolean("battery_opt_asked", true).apply()
                    AlertDialog.Builder(this)
                        .setTitle("Enable Background Sync")
                        .setMessage("To save daily energy data at midnight reliably, please disable battery optimisation for this app.")
                        .setPositiveButton("OK") { _, _ ->
                            startActivity(android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:$packageName")
                            ))
                        }.setNegativeButton("Later", null).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        toggleListeners.forEach { (d, l) -> database.child(d).child("toggle").removeEventListener(l) }
        sensorListeners.forEach { (d, l) -> database.child(d).removeEventListener(l) }
        resetListener?.let { database.child("reset_units").removeEventListener(it) }
    }
}
