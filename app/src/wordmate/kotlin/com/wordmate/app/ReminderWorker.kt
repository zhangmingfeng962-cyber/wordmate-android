package com.wordmate.app

import android.Manifest
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
import androidx.work.*
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object Reminders {
    fun schedule(context: Context, settings: AppSettings) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("daily-word-reminder")
        if (!settings.reminder) return
        val now = ZonedDateTime.now()
        var next = now.withHour(settings.reminderHour).withMinute(settings.reminderMinute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(Duration.between(now, next).seconds.coerceAtLeast(1), TimeUnit.SECONDS).build()
        manager.enqueueUniqueWork("daily-word-reminder", ExistingWorkPolicy.REPLACE, request)
    }
}

class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).get()
        if (!settings.reminder) return Result.success()
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel("daily", "每日单词学习", NotificationManager.IMPORTANCE_DEFAULT))
        val canNotify = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            val pending = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(applicationContext, "daily")
                .setSmallIcon(com.wordmate.app.R.drawable.ic_notification).setContentTitle("词伴 · 今天也记住一点")
                .setContentText("打开你的词本，完成今天的单词复习。").setContentIntent(pending).setAutoCancel(true).build()
            NotificationManagerCompat.from(applicationContext).notify(1001, notification)
        }
        // Use a different name for the successor to avoid cancelling this running worker.
        val now = ZonedDateTime.now()
        var next = now.withHour(settings.reminderHour).withMinute(settings.reminderMinute).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        WorkManager.getInstance(applicationContext).enqueueUniqueWork("daily-word-reminder",
            ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(Duration.between(now, next).seconds.coerceAtLeast(60), TimeUnit.SECONDS).build())
        return Result.success()
    }
}
