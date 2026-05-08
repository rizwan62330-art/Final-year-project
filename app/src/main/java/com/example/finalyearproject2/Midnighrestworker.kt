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
            val tPrefs   = ctx.getSharedPreferences(MainActivity.PREFS_TODAY_VALS, MODE_PRIVATE)

            // ── Step 1: Read today-units from SharedPreferences ───────────────
            // SharedPreferences is written with commit() on every sensor update
            // so it always has the latest value — even if Firebase push was pending.
            fun readPrefs(dev: String): Double =
                tPrefs.getString("${dev}_$todayKey", null)?.toDoubleOrNull() ?: 0.0

            var b1u = readPrefs("bulb1")
            var b2u = readPrefs("bulb2")
            var f1u = readPrefs("fan1")
            var f2u = readPrefs("fan2")

            // ── Step 2: Fallback — if prefs are all zero, read Firebase ───────
            // This handles the case where the app was never opened today
            // (rare — the baseline was never set so Firebase today_units is used)
            val db = FirebaseDatabase
                .getInstance("https://final-year-project-a75d4-default-rtdb.firebaseio.com/")
                .getReference("energy_monitor")

            if ((b1u + b2u + f1u + f2u) == 0.0) {
                suspend fun readFb(dev: String): Double = try {
                    db.child(dev)
                        .child("today_units")
                        .get()
                        .await()
                        .getValue(Double::class.java) ?: 0.0
                } catch (e: Exception) {
                    0.0
                }
                b1u = readFb("bulb1"); b2u = readFb("bulb2")
                f1u = readFb("fan1");  f2u = readFb("fan2")
            }

            val total = b1u + b2u + f1u + f2u

            // ── Step 3: Save to history ───────────────────────────────────────
            if (total > 0.0) {
                HistoryManager.saveTodaySnapshot(ctx, b1u, b2u, f1u, f2u)
            }

            // ── Step 4: Send daily summary notification ───────────────────────
            NotificationHelper.createChannel(ctx)
            NotificationHelper.sendDailyReport(ctx, total, b1u, b2u, f1u, f2u)

            // ── Step 5: Reset today_units in Firebase to 0 ───────────────────
            MainActivity.DEVICES.forEach { dev ->
                db.child(dev).child("today_units").setValue(0.0).await()
            }

            // ── Step 6: Signal ESP32 to reset its cumulative /units counter ───
            db.child("reset_units").setValue(true).await()

            // ── Step 7: Update today_date in Firebase to tomorrow ─────────────
            val tomorrow = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(Date(System.currentTimeMillis() + 86400000L))
            db.child("today_date").setValue(tomorrow).await()

            // ── Step 8: Clear SharedPreferences for today ─────────────────────
            tPrefs.edit().apply {
                MainActivity.DEVICES.forEach { remove("${it}_$todayKey") }
                commit()  // synchronous — must complete before next day starts
            }
            ctx.getSharedPreferences(MainActivity.PREFS_BASELINE, MODE_PRIVATE).edit().apply {
                MainActivity.DEVICES.forEach { remove("${it}_$todayKey") }
                commit()
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
