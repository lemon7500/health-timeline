package com.healthtimeline.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthtimeline.app.backup.PortableBackupService
import com.healthtimeline.app.data.*
import com.healthtimeline.app.reminders.AlarmScheduler
import com.healthtimeline.app.reminders.NotificationReviewRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotificationReviewNavigationTest {
    private lateinit var context: IsolatedTestContext
    private lateinit var database: AppDatabase
    private lateinit var repository: HealthRepository
    private lateinit var selection: MemberSelectionStore
    private lateinit var viewModel: AppViewModel
    private lateinit var request: NotificationReviewRequest
    private val owner = ViewModelStore()
    private var initialMemberId = 0L
    private var targetMemberId = 0L
    private lateinit var reviewedDate: LocalDate

    @Before fun setUp(): Unit = runBlocking {
        context = IsolatedTestContext(ApplicationProvider.getApplicationContext())
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = HealthRepository(database, AttachmentStore(context, database.attachmentDao()))
        selection = MemberSelectionStore(context)
        initialMemberId = repository.ensureDefaultMember().id
        val now = Instant.now().toString()
        targetMemberId = repository.saveMember(FamilyMemberEntity(
            name = "通知核对测试成员", nickname = "亲属", relationship = "亲属", createdAt = now, updatedAt = now
        ))
        val today = LocalDate.now()
        reviewedDate = today.minusDays(1)
        val medicationId = repository.saveMedication(MedicationEntity(
            memberId = targetMemberId, name = "虚构通知药物", doseAmount = "1", doseUnit = "片",
            startDate = today.minusDays(2).toString(), createdAt = now, updatedAt = now
        ), listOf(LocalTime.of(8, 0))).medicationId
        val schedule = database.medicationDao().schedulesForMedication(medicationId).single()
        // A past plan remains reviewable, but must not install real system alarms during VM init.
        database.medicationDao().updateSchedule(schedule.copy(enabled = false, effectiveTo = today.toString()))
        request = NotificationReviewRequest(medicationId, schedule.id, reviewedDate.atTime(8, 0).toString())
        selection.write(initialMemberId)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val safetyStore = SafetyStatusStore(context)
            viewModel = AppViewModel(repository, AlarmScheduler(context, repository),
                PortableBackupService(context, repository, safetyStatusStore = safetyStore), selection,
                SafetyCenterService(context, database, repository, safetyStore))
            owner.put("notification-review-test", viewModel)
        }
    }

    @After fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.clear() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) context.clearTestResources()
    }

    @Test fun validNotificationSelectsAuthoritativeMemberAndDateWithoutRecordingMedication(): Unit = runBlocking {
        val medicationsBefore = database.medicationDao().allMedications()
        val schedulesBefore = database.medicationDao().allSchedules()

        val destination = viewModel.reviewMedicationNotification(request)

        assertNotNull(destination)
        assertEquals(targetMemberId, destination?.memberId)
        assertEquals(reviewedDate, destination?.date)
        assertEquals(targetMemberId, selection.read())
        assertEquals(targetMemberId, viewModel.selectedMemberId.value)
        assertTrue(database.medicationDao().allLogs().isEmpty())
        assertEquals(medicationsBefore, database.medicationDao().allMedications())
        assertEquals(schedulesBefore, database.medicationDao().allSchedules())
    }

    @Test fun invalidFutureArchivedAndDeletedRequestsNeverRecordMedication(): Unit = runBlocking {
        val medicationsBefore = database.medicationDao().allMedications()
        val schedulesBefore = database.medicationDao().allSchedules()
        val invalid = listOf(
            request.copy(medicationId = -1L),
            request.copy(scheduleId = request.scheduleId + 100L),
            request.copy(scheduledAt = "not-a-date"),
            request.copy(scheduledAt = reviewedDate.atTime(9, 0).toString()),
            request.copy(scheduledAt = LocalDate.now().plusDays(1).atTime(8, 0).toString())
        )
        invalid.forEach { assertNull(viewModel.reviewMedicationNotification(it)) }
        assertEquals(medicationsBefore, database.medicationDao().allMedications())
        assertEquals(schedulesBefore, database.medicationDao().allSchedules())

        repository.archiveMember(targetMemberId)
        val archivedMembers = database.familyMemberDao().all()
        assertNull(viewModel.reviewMedicationNotification(request))
        assertEquals(archivedMembers, database.familyMemberDao().all())
        repository.restoreMember(targetMemberId)
        repository.deleteMedication(request.medicationId)
        val deletedMedications = database.medicationDao().allMedications()
        assertNull(viewModel.reviewMedicationNotification(request))
        assertEquals(deletedMedications, database.medicationDao().allMedications())
        assertTrue(database.medicationDao().allLogs().isEmpty())
        assertEquals(initialMemberId, selection.read())
        assertEquals(initialMemberId, viewModel.selectedMemberId.value)
    }

    @Test fun failedMemberPreferenceCommitReturnsNullWithoutThrowingOrRecordingMedication(): Unit = runBlocking {
        val medicationsBefore = database.medicationDao().allMedications()
        val schedulesBefore = database.medicationDao().allSchedules()
        context.rejectMemberSelectionWrites = true

        assertNull(viewModel.reviewMedicationNotification(request))

        assertEquals(initialMemberId, selection.read())
        assertEquals(initialMemberId, viewModel.selectedMemberId.value)
        assertTrue(database.medicationDao().allLogs().isEmpty())
        assertEquals(medicationsBefore, database.medicationDao().allMedications())
        assertEquals(schedulesBefore, database.medicationDao().allSchedules())
    }

    private class IsolatedTestContext(base: Context) : ContextWrapper(base) {
        private val prefix = "notification-review-${UUID.randomUUID()}-"
        private val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        private val testFiles = File(root, "files").apply { check(mkdirs()) }
        private val testCache = File(root, "cache").apply { check(mkdirs()) }
        private val preferenceNames = mutableSetOf<String>()
        var rejectMemberSelectionWrites = false

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = testFiles
        override fun getCacheDir(): File = testCache

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            preferenceNames += isolatedName
            val delegate = baseContext.getSharedPreferences(isolatedName, mode)
            if (name != "member_selection") return delegate
            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                            editor.putLong(key, value)
                            return this
                        }

                        override fun commit(): Boolean = !rejectMemberSelectionWrites && editor.commit()
                    }
                }
            }
        }

        fun clearTestResources() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            check(root.canonicalPath.startsWith(baseContext.cacheDir.canonicalPath + File.separator))
            root.deleteRecursively()
        }
    }
}
