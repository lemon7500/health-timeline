package com.healthtimeline.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.healthtimeline.app.data.FollowUpScheduleEntity
import com.healthtimeline.app.data.HealthRepository
import com.healthtimeline.app.data.MedicationScheduleEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class AlarmScheduler(
    private val context: Context,
    private val repository: HealthRepository
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    suspend fun rescheduleAll() {
        repository.enabledFollowUps().forEach { scheduleFollowUp(it) }
        repository.enabledMedicationSchedules().forEach { scheduleMedication(it) }
    }

    suspend fun cancelAllPersisted() {
        repository.allFollowUpsForAlarmMaintenance().forEach { cancelFollowUp(it.id) }
        repository.allMedicationSchedulesForAlarmMaintenance().forEach { cancelMedication(it.id) }
    }

    fun cancelAllNotifications() {
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    fun scheduleFollowUp(value: FollowUpScheduleEntity) {
        cancelFollowUp(value.id)
        if (!value.enabled) return
        val dueDate = LocalDate.parse(value.nextDueDate)
        val reminderAt = dueDate.minusDays(value.leadDays.toLong())
            .atTime(LocalTime.parse(value.reminderTime))
        val actualAt = if (reminderAt.isBefore(LocalDateTime.now())) LocalDateTime.now().plusSeconds(3) else reminderAt
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FOLLOW_UP
            data = Uri.parse("healthtimeline://follow-up/${value.id}")
            putExtra(ReminderReceiver.EXTRA_ID, value.id)
            putExtra(ReminderReceiver.EXTRA_DUE, dueDate.toString())
        }
        schedule(actualAt, pendingIntent(followUpRequestCode(value.id), intent))
    }

    suspend fun scheduleMedication(value: MedicationScheduleEntity) {
        cancelMedication(value.id)
        if (!value.enabled) return
        val medication = repository.medicationById(value.medicationId) ?: return cancelMedication(value.id)
        if (medication.archived) return cancelMedication(value.id)
        val time = LocalTime.parse(value.localTime)
        val now = LocalDateTime.now()
        val start = LocalDate.parse(medication.startDate)
        val end = medication.endDate?.let(LocalDate::parse)
        var nextDate = maxOf(LocalDate.now(), start)
        var next = LocalDateTime.of(nextDate, time)
        if (!next.isAfter(now)) {
            nextDate = nextDate.plusDays(1)
            next = LocalDateTime.of(nextDate, time)
        }
        if (end != null && nextDate.isAfter(end)) return cancelMedication(value.id)
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_MEDICATION
            data = Uri.parse("healthtimeline://medication/${value.id}")
            putExtra(ReminderReceiver.EXTRA_ID, value.id)
            putExtra(ReminderReceiver.EXTRA_MEDICATION_ID, value.medicationId)
            putExtra(ReminderReceiver.EXTRA_SCHEDULED_AT, next.toString())
        }
        schedule(next, pendingIntent(medicationRequestCode(value.id), intent))
    }

    fun cancelFollowUp(id: Long) {
        existingPendingIntent(
            followUpRequestCode(id),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_FOLLOW_UP)
                .setData(Uri.parse("healthtimeline://follow-up/$id"))
        )?.let(alarmManager::cancel)
        existingPendingIntent(
            legacyFollowUpRequestCode(id),
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FOLLOW_UP)
        )?.let(alarmManager::cancel)
    }

    fun cancelMedication(id: Long) {
        existingPendingIntent(
            medicationRequestCode(id),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_MEDICATION)
                .setData(Uri.parse("healthtimeline://medication/$id"))
        )?.let(alarmManager::cancel)
        existingPendingIntent(
            legacyMedicationRequestCode(id),
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_MEDICATION)
        )?.let(alarmManager::cancel)
    }

    private fun schedule(at: LocalDateTime, pendingIntent: PendingIntent) {
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
            }
        } catch (_: SecurityException) {
            // Permission can be revoked between the capability check and scheduling.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
        }
    }

    private fun pendingIntent(requestCode: Int, intent: Intent, extraFlags: Int = 0): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or extraFlags
        )

    private fun existingPendingIntent(requestCode: Int, intent: Intent): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

    private fun followUpRequestCode(id: Long) = id.hashCode()
    private fun medicationRequestCode(id: Long) = id.hashCode()
    private fun legacyFollowUpRequestCode(id: Long) = (id % 400_000).toInt() + 100_000
    private fun legacyMedicationRequestCode(id: Long) = (id % 400_000).toInt() + 600_000
}
