package com.example.finalyearproject2

import android.content.Context
import androidx.work.*
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.util.*
import java.util.concurrent.TimeUnit

class MidnightResetWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            // 1. SAVE HISTORY FIRST
            // We fetch current raw units and subtract today's baseline
            // before the day is officially wiped.
            HistoryManager.fetchAndSaveDailySnapshot(ctx)

            // 2. SIGNAL FIREBASE RESET
            // We set 'reset_units' to true.
            // MainActivity listens for this to clear SharedPreferences and local UI.
            val database = FirebaseDatabase
                .getInstance("https://final-year-project-a75d4-default-rtdb.firebaseio.com/")
                .getReference("energy_monitor")

            database.child("reset_units").setValue(true).await()

            // 3. SCHEDULE NEXT RUN
            scheduleNextMidnight(ctx)

            Result.success()
        } catch (e: Exception) {
            // If Firebase is down, retry up to 3 times
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun scheduleNextMidnight(ctx: Context) {
            val now = Calendar.getInstance()
            val midnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // If it's already past midnight (current time), schedule for tomorrow
                if (before(now) || timeInMillis <= now.timeInMillis) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val delay = midnight.timeInMillis - now.timeInMillis

            val request = OneTimeWorkRequestBuilder<MidnightResetWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag("midnight_reset")
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "midnight_reset",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}