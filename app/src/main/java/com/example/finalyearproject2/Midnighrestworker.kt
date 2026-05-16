package com.example.finalyearproject2

import android.content.Context
import android.content.Context.MODE_PRIVATE
import androidx.work.*
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MidnightResetWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

            // ── Step 1: Read from SharedPreferences (PREFS_UNITS = "sp_today_units") ─
            // This is written by the sensor listener with commit() on every update,
            // so it is always accurate — even if hardware is off.
            val prefs = ctx.getSharedPreferences(MainActivity.PREFS_UNITS, MODE_PRIVATE)
            fun readPrefs(dev: String): Double =
                prefs.getString("${dev}_$todayKey", null)?.toDoubleOrNull() ?: 0.0

            val b1u = readPrefs("bulb1")
            val b2u = readPrefs("bulb2")
            val f1u = readPrefs("fan1")
            val f2u = readPrefs("fan2")
            val total = b1u + b2u + f1u + f2u

            // ── Step 2: Save to history ───────────────────────────────────────
            if (total > 0.0) {
                HistoryManager.saveTodaySnapshot(ctx, b1u, b2u, f1u, f2u)
            }

            // ── Step 3: Send daily summary notification ───────────────────────
            NotificationHelper.createChannel(ctx)
            NotificationHelper.sendDailyReport(ctx, total, b1u, b2u, f1u, f2u)

            // ── Step 4: Clear today's prefs so new day starts at 0 ───────────
            val bPrefs = ctx.getSharedPreferences(MainActivity.PREFS_BASELINE, MODE_PRIVATE)
            prefs.edit().apply {
                MainActivity.DEVICES.forEach { remove("${it}_$todayKey") }
                commit()
            }
            bPrefs.edit().apply {
                MainActivity.DEVICES.forEach { remove("${it}_$todayKey") }
                commit()
            }

            // ── Step 5: Signal ESP32 to reset its cumulative /units counter ───
            val db = FirebaseDatabase
                .getInstance("https://final-year-project-a75d4-default-rtdb.firebaseio.com/")
                .getReference("energy_monitor")
            db.child("reset_units").setValue(true).await()

            // ── Step 6: Reset today_units in Firebase for workers ─────────────
            MainActivity.DEVICES.forEach { dev ->
                db.child(dev).child("today_units").setValue(0.0).await()
            }

            HistoryManager.purgeOldDataIfNeeded(ctx)
            scheduleNextMidnight(ctx)
            Result.success()

        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun scheduleNextMidnight(ctx: Context) {
            val now    = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
            }
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "midnight_reset", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MidnightResetWorker>()
                    .setInitialDelay(target.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                    .addTag("midnight_reset")
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                    .build()
            )
        }
    }
}
