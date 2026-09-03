package com.healthtimeline.app.reminders

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.healthtimeline.app.HealthTimelineApplication
import com.healthtimeline.app.MainActivity
import com.healthtimeline.app.NotificationChannels
import com.healthtimeline.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as HealthTimelineApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_FOLLOW_UP -> handleFollowUp(context, app, intent)
                    ACTION_MEDICATION -> handleMedication(context, app, intent)
                }
            } catch (error: Exception) {
                Log.e("ReminderReceiver", "Unable to process reminder safely", error)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleFollowUp(context: Context, app: HealthTimelineApplication, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val due = intent.getStringExtra(EXTRA_DUE)?.let(LocalDate::parse) ?: return
        if (id < 0) return
        app.repository.processDueFollowUp(id, due) ?: return
        showFollowUpNotification(context, id, due)
    }

    private suspend fun handleMedication(context: Context, app: HealthTimelineApplication, intent: Intent) {
        val scheduleId = intent.getLongExtra(EXTRA_ID, -1L)
        val medicationId = intent.getLongExtra(EXTRA_MEDICATION_ID, -1L)
        val scheduledAt = intent.getStringExtra(EXTRA_SCHEDULED_AT) ?: LocalDateTime.now().toString()
        if (scheduleId < 0 || medicationId < 0) return
        val medication = app.repository.medicationById(medicationId) ?: return
        val scheduledDate = runCatching { LocalDateTime.parse(scheduledAt).toLocalDate() }.getOrNull() ?: return
        if (medication.archived || scheduledDate.isBefore(LocalDate.parse(medication.startDate)) ||
            medication.endDate?.let { scheduledDate.isAfter(LocalDate.parse(it)) } == true
        ) return
        showMedicationNotification(context, medicationId, scheduleId, scheduledAt)
        app.repository.enabledMedicationSchedules().firstOrNull { it.id == scheduleId }?.let { schedule ->
            app.alarmScheduler.scheduleMedication(schedule)
        }
    }

    private fun showFollowUpNotification(context: Context, scheduleId: Long, due: LocalDate) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, NotificationChannels.FOLLOW_UP)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("复查提醒")
            .setContentText("有一项复查计划需要关注（${due}）")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify((scheduleId % Int.MAX_VALUE).toInt(), notification)
    }

    private fun showMedicationNotification(
        context: Context,
        medicationId: Long,
        scheduleId: Long,
        scheduledAt: String
    ) {
        if (!canNotify(context)) return
        val actionIntent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_TAKEN
            putExtra(EXTRA_MEDICATION_ID, medicationId)
            putExtra(EXTRA_ID, scheduleId)
            putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
        }
        val action = PendingIntent.getBroadcast(
            context,
            (scheduleId % 400_000).toInt() + 1_100_000,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.MEDICATION)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("用药提醒")
            .setContentText("到服药时间了")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "已服", action)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify((scheduleId % Int.MAX_VALUE).toInt() + 500_000, notification)
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_FOLLOW_UP = "com.healthtimeline.app.FOLLOW_UP"
        const val ACTION_MEDICATION = "com.healthtimeline.app.MEDICATION"
        const val EXTRA_ID = "id"
        const val EXTRA_DUE = "due"
        const val EXTRA_MEDICATION_ID = "medication_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
    }
}
