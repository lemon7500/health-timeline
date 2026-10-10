package com.healthtimeline.app.reminders

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAKEN) return
        val scheduleId = intent.getLongExtra(ReminderReceiver.EXTRA_ID, -1)
        // Old APKs may leave broadcast actions in existing notifications. Do not
        // let these bypass authentication or write a stale course's dose.
        if (scheduleId > 0) {
            context.getSystemService(NotificationManager::class.java)
                .cancel((scheduleId % Int.MAX_VALUE).toInt() + 500_000)
        }
        Toast.makeText(context, "请打开病程日历，核对后记录用药", Toast.LENGTH_LONG).show()
    }

    companion object { const val ACTION_TAKEN = "com.healthtimeline.app.MEDICATION_TAKEN" }
}
