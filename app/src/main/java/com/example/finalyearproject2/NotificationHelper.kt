package com.example.finalyearproject2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/*
 * NotificationHelper
 * ─────────────────────────────────────────────────────────────────────────────
 * Manages creation and delivery of energy alerts and daily reports.
 */
object NotificationHelper {

    private const val CHANNEL_ID_LIMIT  = "energy_limit"
    private const val CHANNEL_ID_DAILY  = "daily_report"
    private const val NOTIF_ID_LIMIT    = 1001
    private const val NOTIF_ID_DAILY    = 1002

    /**
     * Create notification channels (Required for Android 8.0+)
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel for high-priority limit alerts
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID_LIMIT,
                "Energy Limit Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when daily usage exceeds ${MainActivity.DAILY_LIMIT_KWH} kWh"
            })

            // Channel for standard daily summary reports
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID_DAILY,
                "Daily Energy Report",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Summary of daily energy consumption"
            })
        }
    }

    /**
     * Limit-exceeded notification: Sent when live total >= 6.5 kWh.
     * Tapping opens DeviceDetailActivity in "All Devices" mode.
     */
    fun sendLimitNotification(context: Context, totalUnits: Double) {
        val detailIntent = Intent(context, DeviceDetailActivity::class.java).apply {
            putExtra("from_notification", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pi = PendingIntent.getActivity(
            context, 0, detailIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID_LIMIT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ Daily Limit Exceeded!")
            .setContentText("Used ${String.format("%.2f", totalUnits)} kWh (Limit: ${MainActivity.DAILY_LIMIT_KWH} kWh)")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your total energy usage has reached ${String.format("%.2f", totalUnits)} kWh today. " +
                        "This exceeds your daily limit of ${MainActivity.DAILY_LIMIT_KWH} kWh. " +
                        "Tap to analyze device-specific consumption."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // Ensures the phone doesn't vibrate every second
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_LIMIT, notif)
        } catch (e: SecurityException) {
            // Permission missing on Android 13+
        }
    }

    /**
     * Daily report: Sent by DailyReportWorker at 9:00 PM.
     */
    fun sendDailyReport(context: Context, totalUnits: Double,
                        b1u: Double, b2u: Double, f1u: Double, f2u: Double) {

        val detailIntent = Intent(context, DeviceDetailActivity::class.java).apply {
            putExtra("from_notification", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pi = PendingIntent.getActivity(
            context, 1, detailIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusEmoji = if (totalUnits >= MainActivity.DAILY_LIMIT_KWH) "⚠" else "✅"

        val notif = NotificationCompat.Builder(context, CHANNEL_ID_DAILY)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("$statusEmoji Daily Energy Report")
            .setContentText("Total today: ${String.format("%.2f", totalUnits)} kWh")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(
                    "📊 Today's Energy Summary\n\n" +
                            "💡 Bulb 1:  ${String.format("%.3f", b1u)} kWh\n" +
                            "💡 Bulb 2:  ${String.format("%.3f", b2u)} kWh\n" +
                            "🌀 Fan 1:   ${String.format("%.3f", f1u)} kWh\n" +
                            "🌀 Fan 2:   ${String.format("%.3f", f2u)} kWh\n" +
                            "─────────────────────\n" +
                            "Total: ${String.format("%.2f", totalUnits)} / ${MainActivity.DAILY_LIMIT_KWH} kWh"
                ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_DAILY, notif)
        } catch (e: SecurityException) {
            // Permission missing
        }
    }
}