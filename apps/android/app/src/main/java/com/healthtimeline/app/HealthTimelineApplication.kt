package com.healthtimeline.app

import android.app.Application
import com.healthtimeline.app.backup.PortableBackupService
import com.healthtimeline.app.data.AppDatabase
import com.healthtimeline.app.data.AttachmentStore
import com.healthtimeline.app.data.HealthRepository
import com.healthtimeline.app.reminders.AlarmScheduler

class HealthTimelineApplication : Application() {
    val database by lazy { AppDatabase.get(this) }
    val repository by lazy {
        HealthRepository(database, AttachmentStore(this, database.attachmentDao()))
    }
    val alarmScheduler by lazy { AlarmScheduler(this, repository) }
    val backupService by lazy { PortableBackupService(this, repository) }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
    }
}
