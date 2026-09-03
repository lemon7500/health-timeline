package com.healthtimeline.app

import android.app.Application
import com.healthtimeline.app.backup.PortableBackupService
import com.healthtimeline.app.data.AppDatabase
import com.healthtimeline.app.data.AttachmentStore
import com.healthtimeline.app.data.HealthRepository
import com.healthtimeline.app.data.MemberSelectionStore
import com.healthtimeline.app.reminders.AlarmScheduler
import com.healthtimeline.app.security.AppLockManager

class HealthTimelineApplication : Application() {
    val database by lazy { AppDatabase.get(this) }
    val repository by lazy {
        HealthRepository(database, AttachmentStore(this, database.attachmentDao()))
    }
    val alarmScheduler by lazy { AlarmScheduler(this, repository) }
    val backupService by lazy { PortableBackupService(this, repository) }
    val memberSelectionStore by lazy { MemberSelectionStore(this) }
    val appLockManager by lazy { AppLockManager(this) }

    override fun onCreate() {
        super.onCreate()
        appLockManager.registerScreenOffReceiver()
        NotificationChannels.create(this)
    }
}
