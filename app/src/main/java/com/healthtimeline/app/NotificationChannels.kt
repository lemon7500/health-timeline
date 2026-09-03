package com.healthtimeline.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannels {
    const val FOLLOW_UP = "follow_up_reminders"
    const val MEDICATION = "medication_reminders"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(FOLLOW_UP, context.getString(R.string.follow_up_channel), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(MEDICATION, context.getString(R.string.medication_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        )
    }
}
