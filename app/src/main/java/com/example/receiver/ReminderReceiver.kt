package com.example.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.Reminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val reminderId = intent.getIntExtra("REMINDER_ID", -1)

        Log.d("ReminderReceiver", "onReceive action=$action, id=$reminderId")

        if (action == Intent.ACTION_BOOT_COMPLETED) {
            rescheduleAllActiveReminders(context)
            return
        }

        if (reminderId == -1) return

        when (action) {
            ACTION_COMPLETE_TASK -> {
                completeTask(context, reminderId)
            }
            ACTION_SNOOZE_TASK -> {
                snoozeTask(context, reminderId)
            }
            else -> {
                triggerReminderNotification(context, reminderId)
            }
        }
    }

    private fun triggerReminderNotification(context: Context, reminderId: Int) {
        val db = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            val reminder = db.reminderDao().getReminderById(reminderId) ?: return@launch
            if (reminder.isCompleted) return@launch

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "remindme_alerts"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Task Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Chime alerts for repeating tasks and reminders"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val appIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val appPendingIntent = PendingIntent.getActivity(
                context,
                reminderId * 10 + 1,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val completeIntent = Intent(context, ReminderReceiver::class.java).apply {
                this.action = ACTION_COMPLETE_TASK
                putExtra("REMINDER_ID", reminderId)
            }
            val completePendingIntent = PendingIntent.getBroadcast(
                context,
                reminderId * 10 + 2,
                completeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
                this.action = ACTION_SNOOZE_TASK
                putExtra("REMINDER_ID", reminderId)
            }
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                reminderId * 10 + 3,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(reminder.title)
                .setContentText(reminder.description.ifEmpty { "You have an active reminder" })
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(appPendingIntent)
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setVibrate(longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400))
                .addAction(
                    android.R.drawable.ic_menu_today,
                    "Mark Done",
                    completePendingIntent
                )
                .addAction(
                    android.R.drawable.ic_menu_recent_history,
                    "Snooze (5m)",
                    snoozePendingIntent
                )

            notificationManager.notify(reminderId, builder.build())

            if (reminder.repeatIntervalMinutes > 0) {
                // If the alarm is snoozed, we keep targetTime unchanged and schedule snooze.
                // If it's a standard repeat ring, we calculate next targetTime.
                if (!reminder.isSnoozed) {
                    val nextOccurrence = System.currentTimeMillis() + (reminder.repeatIntervalMinutes * 60 * 1000)
                    val updatedReminder = reminder.copy(
                        targetTime = nextOccurrence,
                        updatedAt = System.currentTimeMillis()
                    )
                    db.reminderDao().updateReminder(updatedReminder)
                    ReminderScheduler.schedule(context, updatedReminder)
                }
            }
        }
    }

    private fun completeTask(context: Context, reminderId: Int) {
        val db = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            val reminder = db.reminderDao().getReminderById(reminderId) ?: return@launch
            val updated = reminder.copy(
                isCompleted = true,
                completedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            db.reminderDao().updateReminder(updated)
            ReminderScheduler.cancel(context, updated)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(reminderId)
        }
    }

    private fun snoozeTask(context: Context, reminderId: Int) {
        val db = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            val reminder = db.reminderDao().getReminderById(reminderId) ?: return@launch
            val snoozeMinutes = 5
            val snoozeTime = System.currentTimeMillis() + (snoozeMinutes * 60 * 1000)
            val updated = reminder.copy(
                isSnoozed = true,
                snoozeTime = snoozeTime,
                updatedAt = System.currentTimeMillis()
            )
            db.reminderDao().updateReminder(updated)
            ReminderScheduler.schedule(context, updated)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(reminderId)
        }
    }

    private fun rescheduleAllActiveReminders(context: Context) {
        val db = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            val activeReminders = db.reminderDao().getActiveReminders()
            for (reminder in activeReminders) {
                val now = System.currentTimeMillis()
                var updatedReminder = reminder
                val ringTime = if (reminder.isSnoozed && reminder.snoozeTime > 0) reminder.snoozeTime else reminder.targetTime

                if (ringTime < now) {
                    if (reminder.repeatIntervalMinutes > 0) {
                        var nextOccurrence = reminder.targetTime
                        while (nextOccurrence < now) {
                            nextOccurrence += reminder.repeatIntervalMinutes * 60 * 1000
                        }
                        updatedReminder = reminder.copy(
                            targetTime = nextOccurrence,
                            isSnoozed = false,
                            snoozeTime = 0
                        )
                        db.reminderDao().updateReminder(updatedReminder)
                    } else {
                        updatedReminder = reminder.copy(targetTime = now + 3000) // fire in 3 seconds
                        db.reminderDao().updateReminder(updatedReminder)
                    }
                }
                ReminderScheduler.schedule(context, updatedReminder)
            }
        }
    }

    companion object {
        const val ACTION_COMPLETE_TASK = "com.example.ACTION_COMPLETE_TASK"
        const val ACTION_SNOOZE_TASK = "com.example.ACTION_SNOOZE_TASK"
    }
}
