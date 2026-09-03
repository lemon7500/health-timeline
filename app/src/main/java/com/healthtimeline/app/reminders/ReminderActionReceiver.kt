package com.healthtimeline.app.reminders

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.healthtimeline.app.HealthTimelineApplication
import com.healthtimeline.app.data.MedicationLogStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAKEN) return
        val pending = goAsync()
        val medicationId = intent.getLongExtra(ReminderReceiver.EXTRA_MEDICATION_ID, -1)
        val scheduleId = intent.getLongExtra(ReminderReceiver.EXTRA_ID, -1)
        val scheduledAt = intent.getStringExtra(ReminderReceiver.EXTRA_SCHEDULED_AT) ?: return pending.finish()
        val app = context.applicationContext as HealthTimelineApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (app.repository.markDose(medicationId, scheduleId, scheduledAt, MedicationLogStatus.TAKEN)) {
                    context.getSystemService(NotificationManager::class.java)
                        .cancel((scheduleId % Int.MAX_VALUE).toInt() + 500_000)
                }
            } catch (error: Exception) {
                Log.e("ReminderAction", "Unable to record medication action safely", error)
            } finally { pending.finish() }
        }
    }

    companion object { const val ACTION_TAKEN = "com.healthtimeline.app.MEDICATION_TAKEN" }
}
