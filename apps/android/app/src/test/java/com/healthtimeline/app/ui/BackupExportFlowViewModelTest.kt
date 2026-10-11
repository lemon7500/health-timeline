package com.healthtimeline.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Test

class BackupExportFlowViewModelTest {
    @Test fun frozenUuidWaitsForDatabaseLoadAndSurvivesRecreation() {
        val model = BackupExportFlowViewModel()
        assertTrue(model.begin(BackupExportKind.SAFETY_BEFORE_REPLACE, 1, "test-password".toCharArray(), "content://test/source", "member-a"))
        model.receive("content://test/destination")
        assertNull(model.takeCompletion(1))
        val result = model.takeCompletion(1, "member-a") as BackupExportFlowViewModel.Completion.Ready
        assertEquals("member-a", result.memberUuid)
        result.password.fill('\u0000')
    }

    @Test fun restoringSameNumericMemberIdWithDifferentUuidInvalidatesSafetyExport() {
        val model = BackupExportFlowViewModel()
        val password = "test-password".toCharArray()
        assertTrue(model.begin(BackupExportKind.SAFETY_BEFORE_REPLACE, 1, password, "content://test/source", "member-a"))
        model.bindMember(1, "member-restored")
        model.bindMember(1, "member-a")
        model.receive("content://test/destination")
        assertTrue(model.takeCompletion(1, "member-a") is BackupExportFlowViewModel.Completion.Aborted)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun loadedMissingMemberAbortsInsteadOfWaitingForUuidForever() {
        val model = BackupExportFlowViewModel()
        assertTrue(model.begin(BackupExportKind.EXPORT, 1, "test-password".toCharArray(), memberUuid = "member-a"))
        model.receive("content://test/destination")
        assertNull(model.takeCompletion(1))
        val revision = model.revision.value
        model.bindMember(1, null, memberListLoaded = true)
        assertTrue(model.revision.value > revision)
        assertTrue(model.takeCompletion(1) is BackupExportFlowViewModel.Completion.Aborted)
        assertFalse(model.busy.value)
        assertEquals(listOf("content://test/destination"), model.abortedDestinations.value)
    }

    @Test fun processDeathOrphanOnlyQueuesCleanupAndDoesNotCompleteNewExport() {
        val model = BackupExportFlowViewModel()
        val password = "test-new-password".toCharArray()
        assertTrue(model.begin(BackupExportKind.EXPORT, 1, password, memberUuid = "member-a"))
        model.reportInterruptedProcess("content://test/old-destination")
        assertTrue(model.busy.value)
        assertNull(model.takeCompletion(1, "member-a"))
        assertEquals(listOf("content://test/old-destination"), model.abortedDestinations.value)
        model.receive("content://test/new-destination")
        val completion = model.takeCompletion(1, "member-a") as BackupExportFlowViewModel.Completion.Ready
        assertEquals("content://test/new-destination", completion.destination)
        completion.password.fill('\u0000')
    }

    @Test fun pickerRequestSurvivesRecreationOfThePageOwner() {
        val store = ViewModelStore()
        val first = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[BackupExportFlowViewModel::class.java]
        val password = "test-password".toCharArray()
        assertTrue(first.begin(BackupExportKind.EXPORT, 1, password))
        val rebuilt = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[BackupExportFlowViewModel::class.java]
        assertSame(first, rebuilt)
        rebuilt.receive("content://test/new-backup")
        val result = rebuilt.takeCompletion(1) as BackupExportFlowViewModel.Completion.Ready
        assertArrayEquals(password, result.password)
        assertEquals(BackupExportKind.EXPORT, result.kind)
        assertEquals("content://test/new-backup", result.destination)
        assertNull(rebuilt.takeCompletion(1))
        result.password.fill('\u0000')
        store.clear()
    }

    @Test fun resultCanWaitWhileApplicationIsLockedWithoutLosingSafetySource() {
        val model = BackupExportFlowViewModel()
        assertTrue(model.begin(BackupExportKind.SAFETY_BEFORE_REPLACE, 1, "test-password".toCharArray(), "content://test/import"))
        model.receive("content://test/safety-backup")
        // Root intentionally does not consume the result until identity verification succeeds.
        assertTrue(model.busy.value)
        val result = model.takeCompletion(1) as BackupExportFlowViewModel.Completion.Ready
        assertEquals("content://test/import", result.source)
        assertEquals(BackupExportKind.SAFETY_BEFORE_REPLACE, result.kind)
        result.password.fill('\u0000')
    }

    @Test fun processDeathOrLostRequestExplicitlyAbortsInsteadOfPretendingSuccess() {
        val fresh = BackupExportFlowViewModel()
        fresh.receive("content://test/empty-backup")
        val result = fresh.takeCompletion(1) as BackupExportFlowViewModel.Completion.Aborted
        assertEquals("content://test/empty-backup", result.destination)
        assertTrue(result.message.contains("未导出备份、未替换资料"))
    }

    @Test fun cancelledPickerAndMemberSwitchEraseThePendingPassword() {
        val model = BackupExportFlowViewModel()
        val cancelled = "test-password".toCharArray()
        assertTrue(model.begin(BackupExportKind.EXPORT, 1, cancelled))
        model.receive(null)
        assertEquals(BackupExportFlowViewModel.Completion.Cancelled, model.takeCompletion(1))
        assertTrue(cancelled.all { it == '\u0000' })
        val switched = "test-password".toCharArray()
        assertTrue(model.begin(BackupExportKind.EXPORT, 1, switched))
        model.receive("content://test/new-backup")
        assertTrue(model.takeCompletion(2) is BackupExportFlowViewModel.Completion.Aborted)
        assertTrue(switched.all { it == '\u0000' })
    }

    @Test fun duplicateLaunchFailureAndOwnerClearCannotRetainPasswords() {
        val store = ViewModelStore()
        val model = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[BackupExportFlowViewModel::class.java]
        val pending = "test-password".toCharArray()
        assertTrue(model.begin(BackupExportKind.EXPORT, 1, pending))
        val duplicate = "another-password".toCharArray()
        assertFalse(model.begin(BackupExportKind.EXPORT, 1, duplicate))
        assertTrue(duplicate.all { it == '\u0000' })
        model.cancelAfterLaunchFailure()
        assertTrue(pending.all { it == '\u0000' })
        val last = "test-password".toCharArray()
        assertTrue(model.begin(BackupExportKind.EXPORT, 1, last))
        store.clear()
        assertTrue(last.all { it == '\u0000' })
    }

    @Test fun memberSwitchAndReturnCannotReviveThePickerRequest() {
        val model = BackupExportFlowViewModel()
        val password = "test-password".toCharArray()
        assertTrue(model.begin(BackupExportKind.SAFETY_BEFORE_REPLACE, 1, password, "content://test/import"))
        model.bindMember(2)
        model.bindMember(1)
        model.receive("content://test/safety-backup")
        assertTrue(model.takeCompletion(1) is BackupExportFlowViewModel.Completion.Aborted)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun interruptedAbortPresentationRetainsMessageUntilAcknowledged() {
        val model = BackupExportFlowViewModel()
        model.receive("content://test/empty-backup")
        val aborted = model.takeCompletion(1) as BackupExportFlowViewModel.Completion.Aborted
        // A recreated or temporarily locked screen must not lose a message already consumed for cleanup.
        assertNull(model.takeCompletion(1))
        assertEquals(aborted.message, model.abortMessage.value)
        model.acknowledgeAbort("outdated-message")
        assertEquals(aborted.message, model.abortMessage.value)
        model.acknowledgeAbort(aborted.message)
        assertNull(model.abortMessage.value)
    }
}
