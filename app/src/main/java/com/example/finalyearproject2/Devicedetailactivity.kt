package com.example.finalyearproject2

import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.database.*
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceDetailActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference
    private var rootListener: ValueEventListener? = null

    private val df      = DecimalFormat("#.##")
    private val dfUnits = DecimalFormat("#.###")
    private val prev    = mutableMapOf<String, Double>()

    private var deviceKey = ""
    private var fromNotif = false

    // ── Header ────────────────────────────────────────────────────────────────
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTimestamp: TextView

    // ── Single device section ─────────────────────────────────────────────────
    private lateinit var sectionSingle: LinearLayout
    private lateinit var tvSingleIcon: TextView
    private lateinit var tvSingleLabel: TextView
    private lateinit var tvSingleVoltage: TextView
    private lateinit var tvSingleCurrent: TextView
    private lateinit var tvSingleUnits: TextView
    private lateinit var tvSinglePower: TextView
    private lateinit var singleStripe: View

    // ── All-devices section (notification) ────────────────────────────────────
    private lateinit var sectionAll: LinearLayout
    private lateinit var tvTotalUnits: TextView
    private lateinit var tvB1V: TextView; private lateinit var tvB1I: TextView; private lateinit var tvB1U: TextView
    private lateinit var tvB2V: TextView; private lateinit var tvB2I: TextView; private lateinit var tvB2U: TextView
    private lateinit var tvF1V: TextView; private lateinit var tvF1I: TextView; private lateinit var tvF1U: TextView
    private lateinit var tvF2V: TextView; private lateinit var tvF2I: TextView; private lateinit var tvF2U: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_detail)

        deviceKey = intent.getStringExtra("device_key")  ?: ""
        val deviceName = intent.getStringExtra("device_name") ?: "Device"
        fromNotif = intent.getBooleanExtra("from_notification", false)

        bindViews()
        configureHeader(deviceName)
        initFirebase()
        attachListener()
    }

    private fun bindViews() {
        tvTitle     = findViewById(R.id.tv_detail_title)
        tvSubtitle  = findViewById(R.id.tv_detail_subtitle)
        tvStatus    = findViewById(R.id.tv_detail_status)
        tvTimestamp = findViewById(R.id.tv_detail_timestamp)

        sectionSingle   = findViewById(R.id.section_single)
        tvSingleIcon    = findViewById(R.id.tv_single_icon)
        tvSingleLabel   = findViewById(R.id.tv_single_label)
        tvSingleVoltage = findViewById(R.id.tv_single_voltage)
        tvSingleCurrent = findViewById(R.id.tv_single_current)
        tvSingleUnits   = findViewById(R.id.tv_single_units)
        tvSinglePower   = findViewById(R.id.tv_single_power)
        singleStripe    = findViewById(R.id.single_stripe)

        sectionAll   = findViewById(R.id.section_all)
        tvTotalUnits = findViewById(R.id.tv_d_total_units)
        tvB1V = findViewById(R.id.tv_d_bulb1_voltage); tvB1I = findViewById(R.id.tv_d_bulb1_current); tvB1U = findViewById(R.id.tv_d_bulb1_units)
        tvB2V = findViewById(R.id.tv_d_bulb2_voltage); tvB2I = findViewById(R.id.tv_d_bulb2_current); tvB2U = findViewById(R.id.tv_d_bulb2_units)
        tvF1V = findViewById(R.id.tv_d_fan1_voltage);  tvF1I = findViewById(R.id.tv_d_fan1_current);  tvF1U = findViewById(R.id.tv_d_fan1_units)
        tvF2V = findViewById(R.id.tv_d_fan2_voltage);  tvF2I = findViewById(R.id.tv_d_fan2_current);  tvF2U = findViewById(R.id.tv_d_fan2_units)
    }

    private fun configureHeader(deviceName: String) {
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = "" }

        if (fromNotif) {
            tvTitle.text    = "⚠  Limit Exceeded"
            tvSubtitle.text = "All device readings — daily limit reached"
            sectionSingle.visibility = View.GONE
            sectionAll.visibility    = View.VISIBLE
        } else {
            tvTitle.text    = deviceName
            tvSubtitle.text = "Live voltage, current & unit consumption"
            sectionSingle.visibility = View.VISIBLE
            sectionAll.visibility    = View.GONE

            val isFan = deviceKey.startsWith("fan")
            tvSingleIcon.text  = if (isFan) "🌀" else "💡"
            tvSingleLabel.text = deviceName.uppercase()

            val stripeColor = if (isFan) getColor(R.color.accent_cyan)
            else       getColor(R.color.accent_yellow)
            singleStripe.setBackgroundColor(stripeColor)
            tvSingleLabel.setTextColor(stripeColor)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun initFirebase() {
        val root = FirebaseDatabase
            .getInstance("https://final-year-project-a75d4-default-rtdb.firebaseio.com/")
            .getReference("energy_monitor")

        database = if (fromNotif) root else root.child(deviceKey)
        database.keepSynced(true)
    }

    // ─── Read today's baseline ────────────────────────────────────────────────
    private fun getTodayBaseline(key: String): Double {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val prefs    = getSharedPreferences(MainActivity.PREFS_BASELINE, Context.MODE_PRIVATE)
        return prefs.getString("${key}_${todayKey}", null)?.toDoubleOrNull() ?: -1.0
    }

    // ─── Read persisted today-units (written by MainActivity sensor listener) ─
    private fun getPersistedTodayUnits(key: String): Double {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val prefs    = getSharedPreferences(MainActivity.PREFS_TODAY_VALS, Context.MODE_PRIVATE)
        return prefs.getString("${key}_${todayKey}", null)?.toDoubleOrNull() ?: 0.0
    }

    // ─── Convert raw Firebase value → today-only ─────────────────────────────
    // Returns 0.0 when baseline is unknown (not -1/raw like before)
    private fun toTodayUnits(rawFirebase: Double, key: String): Double {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val prefs = getSharedPreferences(MainActivity.PREFS_BASELINE, Context.MODE_PRIVATE)

        // 1. Try to get today's baseline
        val baseline = prefs.getString("${key}_${todayKey}", null)?.toDoubleOrNull()

        return if (baseline != null) {
            // 2. If we have a baseline, the math is accurate even if the app was closed!
            (rawFirebase - baseline).coerceAtLeast(0.0)
        } else {
            // 3. Fallback: If no baseline exists for TODAY, it means the app was
            // NEVER opened today. We must treat the current raw units as the baseline.
            0.0
        }
    }

    private fun attachListener() {
        rootListener = object : ValueEventListener {

            override fun onDataChange(snap: DataSnapshot) {
                if (!snap.exists()) return

                fun d(p: String) = snap.child(p).getValue(Double::class.java) ?: 0.0
                fun s(p: String) = snap.child(p).getValue(String::class.java) ?: "OFF"

                if (fromNotif) {
                    // ── All devices view (notification tap) ───────────────────
                    val b1On = s("bulb1/toggle").trim().uppercase() == "ON"
                    val b2On = s("bulb2/toggle").trim().uppercase() == "ON"
                    val f1On = s("fan1/toggle").trim().uppercase()  == "ON"
                    val f2On = s("fan2/toggle").trim().uppercase()  == "ON"

                    // Voltage/Current — only when ON
                    if (b1On) { anim("b1v", d("bulb1/voltage"), tvB1V); anim("b1i", d("bulb1/current"), tvB1I) }
                    else      { tvB1V.text = "--"; tvB1I.text = "--" }

                    if (b2On) { anim("b2v", d("bulb2/voltage"), tvB2V); anim("b2i", d("bulb2/current"), tvB2I) }
                    else      { tvB2V.text = "--"; tvB2I.text = "--" }

                    if (f1On) { anim("f1v", d("fan1/voltage"), tvF1V); anim("f1i", d("fan1/current"), tvF1I) }
                    else      { tvF1V.text = "--"; tvF1I.text = "--" }

                    if (f2On) { anim("f2v", d("fan2/voltage"), tvF2V); anim("f2i", d("fan2/current"), tvF2I) }
                    else      { tvF2V.text = "--"; tvF2I.text = "--" }

                    // Units — always show TODAY-ONLY value (baseline subtracted)
                    val b1u = toTodayUnits(d("bulb1/units"), "bulb1")
                    val b2u = toTodayUnits(d("bulb2/units"), "bulb2")
                    val f1u = toTodayUnits(d("fan1/units"),  "fan1")
                    val f2u = toTodayUnits(d("fan2/units"),  "fan2")

                    animUnits("b1u", b1u, tvB1U)
                    animUnits("b2u", b2u, tvB2U)
                    animUnits("f1u", f1u, tvF1U)
                    animUnits("f2u", f2u, tvF2U)

                    // Total = sum of ALL devices' today-only units
                    val total = b1u + b2u + f1u + f2u
                    animUnits("tot", total, tvTotalUnits)
                    tvTotalUnits.setTextColor(
                        if (total >= MainActivity.DAILY_LIMIT_KWH) getColor(R.color.status_offline)
                        else getColor(R.color.accent_green)
                    )

                } else {
                    // ── Single device view ────────────────────────────────────
                    val isOn    = s("toggle").trim().uppercase() == "ON"
                    val voltage = d("voltage")
                    val current = d("current")
                    val power   = voltage * current

                    // Units: subtract today's baseline so we show only today's kWh
                    val rawUnits  = d("units")
                    val todayOnly = toTodayUnits(rawUnits, deviceKey)

                    // Units ALWAYS show even when device is OFF
                    animUnits("su", todayOnly, tvSingleUnits)

                    if (isOn) {
                        anim("sv", voltage, tvSingleVoltage)
                        anim("si", current, tvSingleCurrent)
                        anim("sp", power,   tvSinglePower)
                    } else {
                        tvSingleVoltage.text = "--"
                        tvSingleCurrent.text = "--"
                        tvSinglePower.text   = "--"
                    }
                }

                tvStatus.text = "● LIVE"
                tvStatus.setTextColor(getColor(R.color.accent_cyan))
                val sdf = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
                tvTimestamp.text = "Updated: ${sdf.format(Date())}"
            }

            override fun onCancelled(error: DatabaseError) {
                tvStatus.text = "● OFFLINE"
                tvStatus.setTextColor(getColor(R.color.status_offline))
                tvTimestamp.text = "No data available"
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "Could not load data: ${error.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
        database.addValueEventListener(rootListener!!)
    }

    private fun anim(key: String, to: Double, tv: TextView) {
        val from = prev[key] ?: 0.0
        ValueAnimator.ofFloat(from.toFloat(), to.toFloat()).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener { tv.text = df.format((it.animatedValue as Float).toDouble()) }
            start()
        }
        prev[key] = to
    }

    private fun animUnits(key: String, to: Double, tv: TextView) {
        val from = prev[key] ?: 0.0
        ValueAnimator.ofFloat(from.toFloat(), to.toFloat()).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener { tv.text = dfUnits.format((it.animatedValue as Float).toDouble()) }
            start()
        }
        prev[key] = to
    }

    override fun onDestroy() {
        super.onDestroy()
        rootListener?.let { database.removeEventListener(it) }
    }
}
