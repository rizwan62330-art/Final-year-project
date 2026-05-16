package com.example.finalyearproject2

import android.content.Context.MODE_PRIVATE
import androidx.work.*
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

            // Read from SharedPreferences — always accurate, written with commit()
            val prefs = ctx.getSharedPreferences(MainActivity.PREFS_UNITS, MODE_PRIVATE)
            fun readPrefs(dev: String): Double =
                prefs.getString("${dev}_$todayKey", null)?.toDoubleOrNull() ?: 0.0

            val b1u   = readPrefs("bulb1")
            val b2u   = readPrefs("bulb2")
            val f1u   = readPrefs("fan1")
            val f2u   = readPrefs("fan2")
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
                set(Calendar.SECOND, 0);       set(Calendar.MILLISECOND, 0)
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
