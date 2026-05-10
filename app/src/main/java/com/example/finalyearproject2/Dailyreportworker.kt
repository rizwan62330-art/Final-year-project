package com.example.finalyearproject2

import android.content.Context
import androidx.work.*
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class DailyReportWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

            // 1. Access the Baselines saved by MainActivity
            val bPrefs = ctx.getSharedPreferences(MainActivity.PREFS_BASELINE, Context.MODE_PRIVATE)

            // 2. Connect to Firebase to get the RAW cumulative units
            val db = FirebaseDatabase
                .getInstance("https://final-year-project-a75d4-default-rtdb.firebaseio.com/")
                .getReference("energy_monitor")

            val deviceData = mutableMapOf<String, Double>()

            // 3. Calculate: (Current Raw Firebase Units) - (Stored Morning Baseline)
            MainActivity.DEVICES.forEach { device ->
                val rawFirebaseUnits = try {
                    db.child(device).child("units").get().await().getValue(Double::class.java) ?: 0.0
                } catch (e: Exception) { 0.0 }

                val baseline = bPrefs.getString("${device}_$todayKey", null)?.toDoubleOrNull() ?: rawFirebaseUnits

                val daily = (rawFirebaseUnits - baseline).coerceAtLeast(0.0)
                deviceData[device] = daily
            }

            val b1u = deviceData["bulb1"] ?: 0.0
            val b2u = deviceData["bulb2"] ?: 0.0
            val f1u = deviceData["fan1"] ?: 0.0
            val f2u = deviceData["fan2"] ?: 0.0
            val total = b1u + b2u + f1u + f2u

            // 4. Send the notification
            NotificationHelper.createChannel(ctx)
            NotificationHelper.sendDailyReport(ctx, total, b1u, b2u, f1u, f2u)

            scheduleNext9PM(ctx)
            Result.success()

        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun scheduleNext9PM(ctx: Context) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 21)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }

            val delay = target.timeInMillis - now.timeInMillis

            val request = OneTimeWorkRequestBuilder<DailyReportWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag("daily_report")
                .build()

            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "daily_report",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}