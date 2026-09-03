package com.healthtimeline.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.healthtimeline.app.HealthTimelineApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SystemRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val allowedActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            EXACT_ALARM_PERMISSION_CHANGED
        )
        if (intent.action !in allowedActions) return
        val pending = goAsync()
        val app = context.applicationContext as HealthTimelineApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.alarmScheduler.rescheduleAll()
            } catch (error: Exception) {
                Log.e("ReminderReschedule", "Unable to reschedule reminders safely", error)
            } finally { pending.finish() }
        }
    }

    private companion object {
        const val EXACT_ALARM_PERMISSION_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
