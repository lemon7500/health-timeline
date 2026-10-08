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
import com.healthtimeline.app.data.AppDatabase
import com.healthtimeline.app.data.AttachmentStore
import com.healthtimeline.app.data.FamilyMemberEntity
import com.healthtimeline.app.data.HealthRepository
import com.healthtimeline.app.data.MemberSelectionStore
import com.healthtimeline.app.data.SafetyCenterService
import com.healthtimeline.app.data.SafetyStatusStore
import com.healthtimeline.app.reminders.AlarmScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppViewModelMemberTest {
    private lateinit var context: IsolatedTestContext
    private lateinit var database: AppDatabase
    private lateinit var repository: HealthRepository
    private lateinit var selection: MemberSelectionStore
    private val viewModelStore = ViewModelStore()

    @Before fun setUp() {
        context = IsolatedTestContext(ApplicationProvider.getApplicationContext())
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = HealthRepository(database, AttachmentStore(context, database.attachmentDao()))
        selection = MemberSelectionStore(context)
    }

    @After fun tearDown() {
        // Clear the owner before closing Room, so both init collectors are cancelled.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModelStore.clear() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        database.close()
        context.clearTestResources()
    }

    @Test fun coldStartSelectsExistingActiveMemberWhileSharedMembersHasNoSubscribers(): Unit = runBlocking {
        val archivedId = database.familyMemberDao().insert(member("旧测试成员", archived = true))
        val activeId = database.familyMemberDao().insert(member("有效测试成员"))
        selection.write(archivedId)
        val viewModel = createViewModel()

        // Subscribe only to the selection. members stays at its initial cache value.
        withTimeout(10_000) { viewModel.selectedMemberId.first { it == activeId } }

        assertTrue(viewModel.members.value.isEmpty())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertEquals(activeId, selection.read())
        }
        assertEquals(listOf(archivedId, activeId), database.familyMemberDao().all().map { it.id })
    }

    @Test fun archivingSelectedMemberAutomaticallySelectsAnotherActiveMember(): Unit = runBlocking {
        val firstId = database.familyMemberDao().insert(member("测试成员甲"))
        val secondId = database.familyMemberDao().insert(member("测试成员乙"))
        selection.write(firstId)
        val viewModel = createViewModel()

        repository.archiveMember(firstId)
        withTimeout(10_000) { viewModel.selectedMemberId.first { it == secondId } }

        assertTrue(viewModel.members.value.isEmpty())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertEquals(secondId, selection.read())
        }
        val members = database.familyMemberDao().all()
        assertEquals(2, members.size)
        assertTrue(members.first { it.id == firstId }.archived)
        assertTrue(!members.first { it.id == secondId }.archived)
    }

    private fun createViewModel(): AppViewModel {
        lateinit var viewModel: AppViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val safetyStore = SafetyStatusStore(context)
            viewModel = AppViewModel(
                repository,
                AlarmScheduler(context, repository),
                PortableBackupService(context, repository, safetyStatusStore = safetyStore),
                selection,
                SafetyCenterService(context, database, repository, safetyStore)
            )
            viewModelStore.put("member-fallback-test", viewModel)
        }
        return viewModel
    }

    private fun member(name: String, archived: Boolean = false) = FamilyMemberEntity(
        name = name, nickname = "测试", relationship = "测试", archived = archived,
        createdAt = "2026-10-08T00:00:00Z", updatedAt = "2026-10-08T00:00:00Z"
    )

    /** Never let the empty test database's initialization clean real attachments. */
    private class IsolatedTestContext(base: Context) : ContextWrapper(base) {
        private val prefix = "viewmodel-member-${UUID.randomUUID()}-"
        private val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        private val testFiles = File(root, "files").apply { check(mkdirs()) }
        private val testCache = File(root, "cache").apply { check(mkdirs()) }
        private val preferenceNames = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = testFiles
        override fun getCacheDir(): File = testCache

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            preferenceNames += isolatedName
            return baseContext.getSharedPreferences(isolatedName, mode)
        }

        fun clearTestResources() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            check(root.canonicalPath.startsWith(baseContext.cacheDir.canonicalPath + File.separator))
            root.deleteRecursively()
        }
    }
}
