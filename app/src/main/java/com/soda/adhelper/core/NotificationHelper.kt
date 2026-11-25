package com.soda.adhelper.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.soda.adhelper.R
import com.soda.adhelper.ui.MainActivity

object NotificationHelper {
    const val CHANNEL_ALERTS = "soda_ad_helper_alerts"
    const val CHANNEL_OVERLAY = "soda_ad_helper_overlay"
    const val NOTIFICATION_ID_OVERLAY = 1001
    private const val NOTIFICATION_ID_ALERT = 1002

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "任务状态与异常提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "当广告领取完成、达到上限、跳出应用或出现人机验证时发出提醒"
        }

        val overlayChannel = NotificationChannel(
            CHANNEL_OVERLAY,
            "悬浮窗运行保活",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "用于在后台维持悬浮窗控制条稳定显示"
        }

        manager.createNotificationChannel(alertChannel)
        manager.createNotificationChannel(overlayChannel)
    }

    fun buildOverlayForegroundNotification(context: Context, contentText: String): Notification {
        ensureChannels(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("汽水广告助手运行中")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun notifyAlert(context: Context, title: String, body: String) {
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_ALERT, notification)
    }
}
