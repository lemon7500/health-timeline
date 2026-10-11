package com.healthtimeline.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity recreation and registry delivery; no device camera or documents provider is required. */
@RunWith(AndroidJUnit4::class)
class ExternalImportLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun legacyDetailLocalLauncherLosesItsResultAfterRecreation() {
        launch(legacy = true).use { scenario ->
            scenario.onActivity { it.openLegacyDetail() }
            idle()
            var requestCode = 0
            scenario.onActivity { activity ->
                activity.launchLegacyDocuments()
                requestCode = activity.registry.lastRequestCode
            }
            scenario.recreate()
            idle()
            scenario.onActivity { activity ->
                activity.registry.dispatchResult(requestCode, Activity.RESULT_OK, documentsResult())
            }
            idle()
            scenario.onActivity { assertTrue(it.fixture.attachments.isEmpty()) }
        }
    }

    @Test fun documentResultSurvivesRecreationAndWaitsForUnlockExactlyOnce() {
        launch().use { scenario ->
            var requestCode = 0
            scenario.onActivity { activity ->
                assertTrue(activity.actions.attachments(activity.fixture.target))
                requestCode = activity.registry.lastRequestCode
                activity.fixture.locked.value = true
            }
            idle()
            scenario.recreate()
            idle()
            scenario.onActivity { activity ->
                assertTrue(activity.registry.dispatchResult(requestCode, Activity.RESULT_OK, documentsResult()))
            }
            idle()
            scenario.onActivity { activity ->
                assertTrue(activity.fixture.attachments.isEmpty())
                assertTrue(activity.flow.busy.value)
                activity.fixture.locked.value = false
            }
            idle()
            scenario.onActivity { activity ->
                assertEquals(1, activity.fixture.attachments.size)
                assertEquals(activity.fixture.target, activity.fixture.attachments.single().first)
                assertEquals(listOf(FIRST, SECOND), activity.fixture.attachments.single().second)
                assertFalse(activity.flow.busy.value)
            }
            scenario.recreate()
            idle()
            scenario.onActivity { assertEquals(1, it.fixture.attachments.size) }
        }
    }

    @Test fun cameraResultRetainsItsOwnedUriAfterRecreationButDoesNotCopyWhileLocked() {
        launch().use { scenario ->
            var requestCode = 0
            scenario.onActivity { activity ->
                assertTrue(activity.actions.camera(activity.fixture.target, CAMERA))
                requestCode = activity.registry.lastRequestCode
                activity.fixture.locked.value = true
            }
            idle()
            scenario.recreate()
            idle()
            scenario.onActivity { it.registry.dispatchResult(requestCode, Activity.RESULT_OK, null) }
            idle()
            scenario.onActivity { activity ->
                assertTrue(activity.fixture.attachments.isEmpty())
                assertTrue(activity.fixture.discarded.isEmpty())
                activity.fixture.locked.value = false
            }
            idle()
            scenario.onActivity { activity ->
                assertEquals(listOf(CAMERA), activity.fixture.attachments.single().second)
                // Successful camera files belong to the import job until copying has finished.
                assertTrue(activity.fixture.discarded.isEmpty())
                assertEquals(CAMERA, activity.fixture.cameraAfterImport.single())
            }
        }
    }

    @Test fun backupSourceSurvivesLockAndRecreationUntilExplicitCancellation() {
        launch().use { scenario ->
            var requestCode = 0
            scenario.onActivity { activity ->
                assertTrue(activity.actions.backup(activity.fixture.member))
                requestCode = activity.registry.lastRequestCode
                activity.fixture.locked.value = true
            }
            idle()
            scenario.recreate()
            idle()
            scenario.onActivity { it.registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(FIRST)) }
            idle()
            scenario.onActivity { activity ->
                assertNull(activity.flow.backupSource.value)
                activity.fixture.locked.value = false
            }
            idle()
            var token = ""
            scenario.onActivity { activity ->
                val source = requireNotNull(activity.flow.backupSource.value)
                assertEquals(FIRST.toString(), source.uri)
                assertEquals(activity.fixture.member, source.member)
                token = source.token
            }
            scenario.recreate()
            idle()
            scenario.onActivity { activity ->
                assertEquals(token, activity.flow.backupSource.value?.token)
                activity.flow.finishBackup(token)
                assertNull(activity.flow.backupSource.value)
            }
        }
    }

    @Test fun switchingAwayAndBackPermanentlyAbortsCameraAndCleansOnlyItsOwnTemporaryUri() {
        launch().use { scenario ->
            var requestCode = 0
            scenario.onActivity { activity ->
                assertTrue(activity.actions.camera(activity.fixture.target, CAMERA))
                requestCode = activity.registry.lastRequestCode
                activity.flow.bindMember(2, "member-b")
                activity.flow.bindMember(1, "member-a")
                activity.fixture.locked.value = true
            }
            idle()
            scenario.recreate()
            idle()
            scenario.onActivity { it.registry.dispatchResult(requestCode, Activity.RESULT_OK, null) }
            idle()
            scenario.onActivity { activity ->
                assertTrue(activity.fixture.attachments.isEmpty())
                activity.fixture.locked.value = false
            }
            idle()
            scenario.onActivity { activity ->
                assertTrue(activity.fixture.attachments.isEmpty())
                assertEquals(listOf(CAMERA), activity.fixture.discarded)
                assertTrue(requireNotNull(activity.flow.abortMessage.value).contains("未保存报告"))
            }
        }
    }

    @Test fun sameNumericMemberWithDifferentUuidAbortsBackupInsteadOfOpeningImport() {
        launch().use { scenario ->
            var requestCode = 0
            scenario.onActivity { activity ->
                assertTrue(activity.actions.backup(activity.fixture.member))
                requestCode = activity.registry.lastRequestCode
                activity.fixture.memberState.value = ExternalImportMember(1, "restored-member")
            }
            idle()
            scenario.onActivity { it.registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(FIRST)) }
            idle()
            scenario.onActivity { activity ->
                assertNull(activity.flow.backupSource.value)
                assertNotNull(activity.flow.abortMessage.value)
            }
        }
    }

    @Test fun lostProcessMemoryDoesNotImportAndDiscardsRecoveredCameraMetadata() {
        launch().use { scenario ->
            var requestCode = 0
            scenario.onActivity { activity ->
                assertTrue(activity.actions.camera(activity.fixture.target, CAMERA))
                requestCode = activity.registry.lastRequestCode
                // Registry/SavedState metadata survives, but the process-only target does not.
                activity.simulateLostProcessMemoryOnRecreation()
            }
            scenario.recreate()
            idle()
            scenario.onActivity { it.registry.dispatchResult(requestCode, Activity.RESULT_OK, null) }
            idle()
            scenario.onActivity { activity ->
                assertTrue(activity.fixture.attachments.isEmpty())
                assertEquals(listOf(CAMERA), activity.fixture.discarded)
                assertTrue(requireNotNull(activity.flow.abortMessage.value).contains("页面状态丢失"))
            }
        }
    }

    @Test fun cancelledPickerDoesNotCreateAnImportOrBackupSelection() {
        launch().use { scenario ->
            var requestCode = 0
            scenario.onActivity { activity ->
                assertTrue(activity.actions.attachments(activity.fixture.target))
                requestCode = activity.registry.lastRequestCode
            }
            scenario.onActivity { it.registry.dispatchResult(requestCode, Activity.RESULT_CANCELED, null) }
            idle()
            scenario.onActivity { activity ->
                assertTrue(activity.fixture.attachments.isEmpty())
                assertNull(activity.flow.backupSource.value)
                assertFalse(activity.flow.busy.value)
                assertNull(activity.flow.abortMessage.value)
            }
        }
    }

    @Test fun memoryLossAfterCameraResultDeliveredWhileLockedStillAbortsAndCleansWithoutRedelivery() {
        launch().use { scenario ->
            var requestCode = 0
            scenario.onActivity { activity ->
                assertTrue(activity.actions.camera(activity.fixture.target, CAMERA))
                requestCode = activity.registry.lastRequestCode
                activity.fixture.locked.value = true
            }
            idle()
            scenario.onActivity { it.registry.dispatchResult(requestCode, Activity.RESULT_OK, null) }
            idle()
            scenario.onActivity { activity ->
                assertTrue(activity.fixture.attachments.isEmpty())
                activity.simulateLostProcessMemoryOnRecreation()
            }
            // Simulates lost process memory plus restored registry/SavedState; not an actual OS kill.
            scenario.recreate()
            idle()
            scenario.onActivity { activity ->
                assertTrue(activity.fixture.attachments.isEmpty())
                assertEquals(listOf(CAMERA), activity.fixture.discarded)
                assertNotNull(activity.flow.abortMessage.value)
                assertFalse(activity.flow.busy.value)
            }
        }
    }

    @Test fun orphanCameraResultCannotCompleteOrCancelANewDocumentRequest() {
        launch().use { scenario ->
            var oldRequestCode = 0
            scenario.onActivity { activity ->
                assertTrue(activity.actions.camera(activity.fixture.target, CAMERA))
                oldRequestCode = activity.registry.lastRequestCode
                activity.simulateLostProcessMemoryOnRecreation()
            }
            scenario.recreate()
            idle()
            var newRequestCode = 0
            scenario.onActivity { activity ->
                assertTrue(activity.actions.attachments(activity.fixture.target))
                newRequestCode = activity.registry.lastRequestCode
                assertNotEquals(oldRequestCode, newRequestCode)
                activity.registry.dispatchResult(oldRequestCode, Activity.RESULT_OK, null)
                assertTrue(activity.flow.busy.value)
                assertEquals(0, activity.fixture.finished)
            }
            idle()
            scenario.onActivity { it.registry.dispatchResult(newRequestCode, Activity.RESULT_OK, documentsResult()) }
            idle()
            scenario.onActivity { activity ->
                assertEquals(1, activity.fixture.attachments.size)
                assertEquals(listOf(FIRST, SECOND), activity.fixture.attachments.single().second)
                assertFalse(activity.flow.busy.value)
            }
        }
    }

    @Test fun exportResultSurvivesRotationAndWaitsForUnlock() {
        launch().use { scenario ->
            var requestCode = 0
            scenario.onActivity { activity ->
                assertTrue(activity.backupFlow.begin(BackupExportKind.EXPORT, 1, "synthetic-password".toCharArray(), memberUuid = "member-a"))
                assertTrue(activity.backupActions.launch(BackupExportKind.EXPORT, "synthetic.htbackup"))
                requestCode = activity.registry.lastRequestCode
                activity.fixture.locked.value = true
            }
            idle()
            scenario.recreate()
            idle()
            scenario.onActivity { it.registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(FIRST)) }
            idle()
            scenario.onActivity { activity ->
                assertTrue(activity.fixture.exportDestinations.isEmpty())
                assertTrue(activity.backupFlow.busy.value)
                activity.fixture.locked.value = false
            }
            idle()
            scenario.onActivity { activity ->
                assertEquals(listOf(BackupExportKind.EXPORT to FIRST.toString()), activity.fixture.exportDestinations)
                assertFalse(activity.backupFlow.busy.value)
            }
        }
    }

    @Test fun delayedOldExportAndSafetyResultsCannotCompleteNewSameKindRequests() {
        for (kind in BackupExportKind.entries) {
            launch().use { scenario ->
                var oldRequestCode = 0
                val source = if (kind == BackupExportKind.SAFETY_BEFORE_REPLACE) "content://synthetic/import-source" else null
                scenario.onActivity { activity ->
                    assertTrue(activity.backupFlow.begin(kind, 1, "old-password".toCharArray(), source, "member-a"))
                    assertTrue(activity.backupActions.launch(kind, "old.htbackup"))
                    oldRequestCode = activity.registry.lastRequestCode
                    activity.simulateLostProcessMemoryOnRecreation()
                }
                scenario.recreate()
                idle()
                var newRequestCode = 0
                scenario.onActivity { activity ->
                    assertTrue(activity.backupFlow.begin(kind, 1, "new-password".toCharArray(), source, "member-a"))
                    assertTrue(activity.backupActions.launch(kind, "new.htbackup"))
                    newRequestCode = activity.registry.lastRequestCode
                    assertNotEquals(oldRequestCode, newRequestCode)
                    assertTrue(activity.registry.dispatchResult(oldRequestCode, Activity.RESULT_OK, Intent().setData(FIRST)))
                    assertTrue(activity.backupFlow.busy.value)
                    assertTrue(activity.fixture.exportDestinations.isEmpty())
                    assertEquals(0, activity.fixture.finished)
                }
                idle()
                scenario.onActivity { activity ->
                    assertEquals(listOf(FIRST.toString()), activity.fixture.abortedExportDestinations)
                    activity.registry.dispatchResult(newRequestCode, Activity.RESULT_OK, Intent().setData(SECOND))
                }
                idle()
                scenario.onActivity { activity ->
                    assertEquals(listOf(kind to SECOND.toString()), activity.fixture.exportDestinations)
                    assertEquals(listOf(source), activity.fixture.exportSourceUris)
                    assertFalse(activity.backupFlow.busy.value)
                }
            }
        }
    }

    @Test fun deliveredLockedExportThenMemoryLossQueuesDestinationWithoutAnotherCallback() {
        launch().use { scenario ->
            var requestCode = 0
            scenario.onActivity { activity ->
                assertTrue(activity.backupFlow.begin(BackupExportKind.EXPORT, 1, "synthetic-password".toCharArray(), memberUuid = "member-a"))
                assertTrue(activity.backupActions.launch(BackupExportKind.EXPORT, "synthetic.htbackup"))
                requestCode = activity.registry.lastRequestCode
                activity.fixture.locked.value = true
            }
            idle()
            scenario.onActivity { it.registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(FIRST)) }
            idle()
            scenario.onActivity { it.simulateLostProcessMemoryOnRecreation() }
            scenario.recreate()
            idle()
            scenario.onActivity { activity ->
                assertTrue(activity.fixture.exportDestinations.isEmpty())
                assertEquals(listOf(FIRST.toString()), activity.fixture.abortedExportDestinations)
                assertNotNull(activity.backupFlow.abortMessage.value)
                assertFalse(activity.backupFlow.busy.value)
            }
        }
    }

    private fun launch(legacy: Boolean = false): ActivityScenario<ExternalImportHarnessActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch<ExternalImportHarnessActivity>(
            Intent(context, ExternalImportHarnessActivity::class.java).putExtra("legacy", legacy)
        )
        idle()
        return scenario
    }

    private fun idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitForIdle()
    }

    private fun documentsResult() = Intent().apply {
        clipData = ClipData.newRawUri("synthetic", FIRST).apply { addItem(ClipData.Item(SECOND)) }
    }

    companion object {
        private val FIRST = Uri.parse("content://synthetic/first.pdf")
        private val SECOND = Uri.parse("content://synthetic/second.png")
        private val CAMERA = Uri.parse("content://com.healthtimeline.app.files/camera/report-test.jpg")
    }
}
