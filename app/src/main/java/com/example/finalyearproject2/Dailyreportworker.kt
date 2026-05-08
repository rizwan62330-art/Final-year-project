package com.example.finalyearproject2

import android.content.Context.MODE_PRIVATE
import androidx.work.*
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class DailyReportWorker(
    private val ctx: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val tPrefs   = ctx.getSharedPreferences(MainActivity.PREFS_TODAY_VALS, MODE_PRIVATE)

            // ── Read from SharedPreferences first (always up-to-date) ─────────
            fun readPrefs(dev: String): Double =
                tPrefs.getString("${dev}_$todayKey", null)?.toDoubleOrNull() ?: 0.0

            var b1u = readPrefs("bulb1")
            var b2u = readPrefs("bulb2")
            var f1u = readPrefs("fan1")
            var f2u = readPrefs("fan2")

            // ── Fallback to Firebase today_units if prefs are empty ───────────
            if ((b1u + b2u + f1u + f2u) == 0.0) {
                val db = FirebaseDatabase
                    .getInstance("https://final-year-project-a75d4-default-rtdb.firebaseio.com/")
                    .getReference("energy_monitor")
                
                // Fixed: Marked local function as suspend to allow calling .await()
                suspend fun readFb(dev: String): Double = try {
                    db.child(dev).child("today_units").get().await()
                        .getValue(Double::class.java) ?: 0.0
                } catch (e: Exception) { 0.0 }

                b1u = readFb("bulb1"); b2u = readFb("bulb2")
                f1u = readFb("fan1");  f2u = readFb("fan2")
            }

            val total = b1u + b2u + f1u + f2u

            NotificationHelper.createChannel(ctx)
            NotificationHelper.sendDailyReport(ctx, total, b1u, b2u, f1u, f2u)
            scheduleNext9PM(ctx)
            Result.success()

        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun scheduleNext9PM(ctx: android.content.Context) {
            val now    = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 21); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "daily_report", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DailyReportWorker>()
                    .setInitialDelay(target.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                    .addTag("daily_report").build()
            )
        }
    }
}
