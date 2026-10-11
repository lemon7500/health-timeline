package com.healthtimeline.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.healthtimeline.app.data.AttachmentImportTarget
import org.junit.Assert.*
import org.junit.Test

class ExternalImportFlowViewModelTest {
    private val member = ExternalImportMember(1, "member-a")
    private val target = AttachmentImportTarget(5, "record-a", member.id, member.uuid)

    @Test fun lockedResultIsRetainedUntilAuthenticationAndConsumedOnlyOnce() {
        val flow = ExternalImportFlowViewModel()
        assertTrue(flow.beginAttachments(target))
        flow.receiveDocuments(listOf("content://test/report"))
        assertNull(flow.takeCompletion(member.id, member.uuid, locked = true))
        assertTrue(flow.busy.value)
        val ready = flow.takeCompletion(member.id, member.uuid, false) as ExternalImportFlowViewModel.Completion.Attachments
        assertEquals(target, ready.target)
        assertEquals(listOf("content://test/report"), ready.uris)
        assertNull(flow.takeCompletion(member.id, member.uuid, false))
    }

    @Test fun retainedMemberIdentityWaitsForInitialLoadButRejectsLoadedReplacementUuid() {
        val flow = ExternalImportFlowViewModel()
        assertTrue(flow.beginAttachments(target))
        flow.receiveDocuments(listOf("content://test/report"))
        flow.bindMember(1, null)
        assertNull(flow.takeCompletion(1, null, false))
        flow.bindMember(1, "restored-member")
        assertTrue(flow.takeCompletion(1, "restored-member", false) is ExternalImportFlowViewModel.Completion.Aborted)
    }

    @Test fun loadedMissingOrArchivedMemberReleasesAResultWaitingForItsUuid() {
        val flow = ExternalImportFlowViewModel()
        assertTrue(flow.beginAttachments(target))
        flow.receiveDocuments(listOf("content://test/report"))
        assertNull(flow.takeCompletion(1, null, false))
        val revision = flow.revision.value
        flow.bindMember(1, null, memberListLoaded = true)
        assertTrue(flow.revision.value > revision)
        assertTrue(flow.takeCompletion(1, null, false) is ExternalImportFlowViewModel.Completion.Aborted)
        assertFalse(flow.busy.value)
    }

    @Test fun switchingAwayAndBackBeforeResultPermanentlyInvalidatesTarget() {
        val flow = ExternalImportFlowViewModel()
        flow.bindMember(1, member.uuid)
        val epoch = flow.selectionEpoch
        assertTrue(flow.beginCamera(target, "content://test/camera"))
        flow.bindMember(2, null)
        flow.bindMember(1, null)
        assertTrue(flow.selectionEpoch > epoch)
        flow.receiveCamera(true, null)
        val abort = flow.takeCompletion(1, member.uuid, false) as ExternalImportFlowViewModel.Completion.Aborted
        assertEquals("content://test/camera", abort.cameraUri)
        assertFalse(flow.busy.value)
    }

    @Test fun backupSelectionSurvivesOwnerRecreationAndIsClearedByExplicitFinishOnly() {
        val store = ViewModelStore()
        val original = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ExternalImportFlowViewModel::class.java]
        assertTrue(original.beginBackup(member))
        original.receiveBackup("content://test/backup")
        val selection = (original.takeCompletion(1, member.uuid, false) as ExternalImportFlowViewModel.Completion.Backup).selection
        val rebuilt = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ExternalImportFlowViewModel::class.java]
        assertSame(original, rebuilt)
        assertEquals(selection, rebuilt.backupSource.value)
        rebuilt.finishBackup("different-token")
        assertEquals(selection, rebuilt.backupSource.value)
        rebuilt.finishBackup(selection.token)
        assertNull(rebuilt.backupSource.value)
        store.clear()
    }

    @Test fun memberChangeAlsoInvalidatesAlreadySelectedBackupSource() {
        val flow = ExternalImportFlowViewModel()
        assertTrue(flow.beginBackup(member))
        flow.receiveBackup("content://test/backup")
        flow.takeCompletion(1, member.uuid, false)
        flow.bindMember(2, "member-b")
        flow.bindMember(1, member.uuid)
        assertNull(flow.backupSource.value)
        assertNotNull(flow.abortMessage.value)
    }

    @Test fun missingProcessRequestAbortsInsteadOfGuessingOwnerOrCreatingRecord() {
        val fresh = ExternalImportFlowViewModel()
        fresh.receiveCamera(true, "content://test/camera")
        val abort = fresh.takeCompletion(1, member.uuid, false) as ExternalImportFlowViewModel.Completion.Aborted
        assertEquals("content://test/camera", abort.cameraUri)
        assertTrue(requireNotNull(fresh.abortMessage.value).contains("页面状态丢失"))
        assertNull(fresh.backupSource.value)
    }

    @Test fun cancelledAndFailedLaunchesReleaseBusyStateWithoutImporting() {
        val flow = ExternalImportFlowViewModel()
        assertTrue(flow.beginBackup(member))
        assertFalse(flow.beginAttachments(target))
        flow.receiveBackup(null)
        assertEquals(ExternalImportFlowViewModel.Completion.Cancelled(null), flow.takeCompletion(1, member.uuid, false))
        assertTrue(flow.beginCamera(target, "content://test/camera"))
        assertEquals("content://test/camera", flow.launchFailed(ExternalImportKind.CAMERA))
        assertFalse(flow.busy.value)
        assertNull(flow.takeCompletion(1, member.uuid, false))
    }

    @Test fun unrelatedContractResultDoesNotCompleteAnActiveRequest() {
        val flow = ExternalImportFlowViewModel()
        assertTrue(flow.beginAttachments(target))
        flow.receiveBackup("content://test/stale-backup")
        assertTrue(flow.busy.value)
        assertNull(flow.takeCompletion(1, member.uuid, false))
        flow.receiveDocuments(listOf("content://test/report"))
        assertTrue(flow.takeCompletion(1, member.uuid, false) is ExternalImportFlowViewModel.Completion.Attachments)
    }
}
