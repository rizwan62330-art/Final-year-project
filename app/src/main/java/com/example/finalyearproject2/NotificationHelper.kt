package com.example.finalyearproject2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    private const val CHANNEL_ID_LIMIT  = "energy_limit"
    private const val CHANNEL_ID_DAILY  = "daily_report"
    private const val NOTIF_ID_LIMIT    = 1001
    private const val NOTIF_ID_DAILY    = 1002

    // ── Create both notification channels (call once in onCreate) ────────────
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID_LIMIT,
                "Energy Limit Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Alerts when daily usage exceeds 7 kWh" })

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID_DAILY,
                "Daily Energy Report",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Summary of daily energy consumption" })
        }
    }

    // ── Limit-exceeded notification ───────────────────────────────────────────
    // Tapping opens DeviceDetailActivity with all device readings shown
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
            .setContentText("You have used ${String.format("%.2f", totalUnits)} kWh — limit is 7 kWh/day")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your total energy usage has reached ${String.format("%.2f", totalUnits)} kWh today, exceeding the 7 kWh daily limit. Tap to see all device readings."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)   // Don't spam — only alert first time
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_LIMIT, notif)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted (Android 13+)
        }
    }

    // ── Daily summary notification (sent by DailyReportWorker at 9 PM) ───────
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
                            "Total: ${String.format("%.2f", totalUnits)} / 6.50 kWh"
                ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_DAILY, notif)
        } catch (e: SecurityException) { /* permission not granted */ }
    }
}
