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
    private lateinit var tvStatus:       TextView
    private lateinit var tvLastUpdated:  TextView
    private lateinit var tvTotalUnits:   TextView
    private lateinit var tvUnitsLimit:   TextView
    private lateinit var btnHistory:     TextView
    private lateinit var tvBulb1Voltage: TextView
    private lateinit var tvBulb1Current: TextView
    private lateinit var tvBulb1Units:   TextView
    private lateinit var btnBulb1Toggle: TextView
    private lateinit var tvBulb2Voltage: TextView
    private lateinit var tvBulb2Current: TextView
    private lateinit var tvBulb2Units:   TextView
    private lateinit var btnBulb2Toggle: TextView
    private lateinit var tvFan1Voltage:  TextView
    private lateinit var tvFan1Current:  TextView
    private lateinit var tvFan1Units:    TextView
    private lateinit var btnFan1Toggle:  TextView
    private lateinit var tvFan2Voltage:  TextView
    private lateinit var tvFan2Current:  TextView
    private lateinit var tvFan2Units:    TextView
    private lateinit var btnFan2Toggle:  TextView

    // ── State ─────────────────────────────────────────────────────────────────
    private val toggleState = mutableMapOf(
        "bulb1" to false, "bulb2" to false,
        "fan1"  to false, "fan2"  to false
    )
    val todayUnits = mutableMapOf(
        "bulb1" to 0.0, "bulb2" to 0.0,
        "fan1"  to 0.0, "fan2"  to 0.0
    )
    // In-memory baseline — rebuilt from prefs each session
    private val sessionBaseline = mutableMapOf(
        "bulb1" to -1.0, "bulb2" to -1.0,
        "fan1"  to -1.0, "fan2"  to -1.0
    )

    private val df         = DecimalFormat("#.###")
    private val prevValues = mutableMapOf<String, Double>()
    private var lastLimitNotifTime = 0L
    private val NOTIF_INTERVAL_MS  = 60 * 60 * 1000L

    companion object {
        const val DAILY_LIMIT_KWH  = 6.5
        const val PREFS_UNITS      = "sp_today_units"    // stores today's kWh per device
        const val PREFS_BASELINE   = "sp_day_baseline"   // stores today's ESP32 start value
        const val PREFS_TODAY_VALS = "today_values_prefs"

        val DEVICES = listOf("bulb1", "bulb2", "fan1", "fan2")
    }
    private fun uPrefs(): SharedPreferences = getSharedPreferences(PREFS_UNITS,    MODE_PRIVATE)
    private fun bPrefs(): SharedPreferences = getSharedPreferences(PREFS_BASELINE, MODE_PRIVATE)

    // Returns today's date as "yyyy-MM-dd" — used as part of the prefs key
    // so old days' data is automatically ignored (different key)
    private fun dk(): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    // Read today's saved unit for a device. Returns 0.0 if nothing saved yet.
    fun readUnit(device: String): Double =
        uPrefs().getString("${device}_${dk()}", null)?.toDoubleOrNull() ?: 0.0

    // Write today's unit — commit() guarantees it's on disk before returning
    private fun saveUnit(device: String, value: Double) {
        uPrefs().edit().putString("${device}_${dk()}", value.toString()).commit()
    }

    // Read today's baseline (ESP32 cumulative value at start of day)
    private fun readBaseline(device: String): Double? =
        bPrefs().getString("${device}_${dk()}", null)?.toDoubleOrNull()

    // Write today's baseline — commit()
    private fun saveBaseline(device: String, value: Double) {
        bPrefs().edit().putString("${device}_${dk()}", value.toString()).commit()
    }

    // ═════════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        initFirebase()

        // ── Load saved values from SharedPreferences IMMEDIATELY ──────────────
        // commit() guarantees these were written to disk. This shows the
        // correct values the instant the app opens, before Firebase responds.
        DEVICES.forEach { todayUnits[it] = readUnit(it) }
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

        attachToggleListeners()
        attachSensorListeners()
        attachResetListener()
    }

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
    //
    //  Calculates daily = firebaseUnits - baseline, saves to SharedPreferences,
    //  and also writes today_units to Firebase so workers can read it.
    //
    //  SharedPreferences (not Firebase today_units) is the source of truth
    //  for the UI. Firebase today_units is only for workers/notifications.
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

                    // ── Establish baseline once per session ───────────────────
                    if ((sessionBaseline[device] ?: -1.0) < 0.0) {
                        // Try to restore from prefs first
                        val saved = readBaseline(device)
                        val sessionBaseline_val = if (saved != null) {
                            // Baseline was saved earlier today — restore it
                            saved
                        } else {
                            // First read today — set baseline = current Firebase value
                            // and save immediately with commit()
                            saveBaseline(device, firebaseUnits)
                            firebaseUnits
                        }
                        sessionBaseline[device] = sessionBaseline_val
                    }

                    val baseline = sessionBaseline[device]!!
                    val raw      = firebaseUnits - baseline

                    val daily: Double = when {
                        raw < -0.01 -> {
                            // ESP32 restarted mid-day — rebase, keep accumulated value
                            val keep = readUnit(device)
                            sessionBaseline[device] = firebaseUnits
                            saveBaseline(device, firebaseUnits)
                            keep
                        }
                        raw < 0.0 -> readUnit(device)
                        else      -> raw
                    }

                    // ── Always save the latest value to prefs ─────────────────
                    // We do NOT use "if (daily > stored)" because:
                    //   - When raw < -0.01, daily = readUnit() which equals stored,
                    //     so the condition is false and nothing gets saved.
                    //   - This means on the next restart, prefs still has the old
                    //     value and the baseline is recalculated wrong.
                    // Fix: always write, every time. commit() is fast (<1ms on
                    // most devices) and SharedPreferences handles deduplication.
                    todayUnits[device] = daily
                    saveUnit(device, daily)   // commit() — always, every update

                    // Also push to Firebase for workers/notifications
                    database.child(device).child("today_units").setValue(daily)

                    // ── Update unit TextView ──────────────────────────────────
                    val uView = unitTextView(device)
                    val p     = devicePrefix(device)
                    if (uView != null) updateCard("${p}u", todayUnits[device]!!, uView)

                    // ── Voltage and current — only when device is ON ──────────
                    if (toggleState[device] == true) {
                        val vView = voltageTextView(device)
                        val iView = currentTextView(device)
                        if (vView != null) updateCard("${p}v", voltage, vView)
                        if (iView != null) updateCard("${p}i", current, iView)
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
    //  RESET LISTENER — midnight signal from MidnightResetWorker
    // ══════════════════════════════════════════════════════════════════════════
    private fun attachResetListener() {
        resetListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (snap.getValue(Boolean::class.java) != true) return

                val today = dk()
                // Clear units and baselines for today from prefs
                uPrefs().edit().apply { DEVICES.forEach { remove("${it}_$today") }; commit() }
                bPrefs().edit().apply { DEVICES.forEach { remove("${it}_$today") }; commit() }

                // Reset in-memory state
                DEVICES.forEach { device ->
                    sessionBaseline[device] = -1.0
                    todayUnits[device]      = 0.0
                }
                prevValues.clear()
                lastLimitNotifTime = 0L
                refreshTotalUnits()
                refreshUnitTextViews()

                // Clear the reset flag so it doesn't trigger again on next restart
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
        val btn   = toggleButton(device)    ?: return
        val vView = voltageTextView(device) ?: return
        val iView = currentTextView(device) ?: return
        if (isOn) {
            btn.text = "ON";  btn.setTextColor(getColor(R.color.accent_green))
            btn.setBackgroundResource(R.drawable.btn_toggle_on)
        } else {
            btn.text = "OFF"; btn.setTextColor(getColor(R.color.status_offline))
            btn.setBackgroundResource(R.drawable.btn_toggle_off)
            vView.text = "--"; iView.text = "--"
        }
    }

    // ── View helpers ──────────────────────────────────────────────────────────
    private fun unitTextView(d: String): TextView? = when (d) {
        "bulb1" -> tvBulb1Units;   "bulb2" -> tvBulb2Units
        "fan1"  -> tvFan1Units;    "fan2"  -> tvFan2Units; else -> null
    }
    private fun voltageTextView(d: String): TextView? = when (d) {
        "bulb1" -> tvBulb1Voltage; "bulb2" -> tvBulb2Voltage
        "fan1"  -> tvFan1Voltage;  "fan2"  -> tvFan2Voltage; else -> null
    }
    private fun currentTextView(d: String): TextView? = when (d) {
        "bulb1" -> tvBulb1Current; "bulb2" -> tvBulb2Current
        "fan1"  -> tvFan1Current;  "fan2"  -> tvFan2Current; else -> null
    }
    private fun toggleButton(d: String): TextView? = when (d) {
        "bulb1" -> btnBulb1Toggle; "bulb2" -> btnBulb2Toggle
        "fan1"  -> btnFan1Toggle;  "fan2"  -> btnFan2Toggle; else -> null
    }
    private fun devicePrefix(d: String) = when (d) {
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

    // ── Card taps ─────────────────────────────────────────────────────────────
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

    // ── Toggle taps ───────────────────────────────────────────────────────────
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
                            "Could not signal $device.",
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
            }
        }
    }

    // ── Workers ───────────────────────────────────────────────────────────────
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

        // Read yesterday's values from PREFS_UNITS using yesterday's date key
        fun readYest(dev: String) =
            uPrefs().getString("${dev}_$yesterday", null)?.toDoubleOrNull() ?: 0.0

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
                        .setMessage("To save daily energy data at midnight reliably, disable battery optimisation for this app.")
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
