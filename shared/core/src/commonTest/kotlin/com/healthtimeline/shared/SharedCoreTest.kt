package com.healthtimeline.shared

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SharedCoreTest {
    @Test
    fun everyThreeMonthsPreservesAnchor() {
        val rule = RecurrenceRule(RecurrenceKind.EVERY_N_MONTHS, 3, "2026-07-09", 9)
        assertEquals(LocalDate(2026, 10, 9), SharedRecurrenceCalculator.nextAfter(rule, LocalDate(2026, 7, 9)))
    }

    @Test
    fun monthEndAnchorReturnsAfterShortMonth() {
        val rule = RecurrenceRule(RecurrenceKind.EVERY_N_MONTHS, 1, "2025-01-31", 31)
        val february = SharedRecurrenceCalculator.nextAfter(rule, LocalDate(2025, 1, 31))!!
        assertEquals(LocalDate(2025, 2, 28), february)
        assertEquals(LocalDate(2025, 3, 31), SharedRecurrenceCalculator.nextAfter(rule, february))
    }

    @Test
    fun weeklyRuleHonorsConfiguredWeekday() {
        val rule = RecurrenceRule(RecurrenceKind.EVERY_N_WEEKS, 1, "2026-09-07", 7, weekday = 4)
        assertEquals(LocalDate(2026, 9, 17), SharedRecurrenceCalculator.nextAfter(rule, LocalDate(2026, 9, 7)))
    }

    @Test
    fun jsonRoundTripAndValidation() {
        val snapshot = snapshot(recordTitle = "乳腺彩超复查")
        assertEquals(5, snapshot.schemaVersion)
        assertEquals(snapshot, PortableJson.decode(PortableJson.encode(snapshot)))
        assertFails { PortableSnapshotValidator.validate(snapshot.copy(sourceInstallationId = "bad")) }
        assertFails { PortableSnapshotValidator.validate(snapshot.copy(sourcePlatform = "unknown")) }
    }

    @Test
    fun searchUsesConditionAndClinicalFields() {
        val snapshot = snapshot(recordTitle = "复查")
        assertEquals(1, searchPortableRecords(snapshot.records, snapshot.conditions, "乳腺").size)
        assertEquals(1, searchPortableRecords(snapshot.records, snapshot.conditions, "彩超").size)
        assertTrue(searchPortableRecords(snapshot.records, snapshot.conditions, "不存在").isEmpty())
    }

    @Test
    fun mergeIsIdempotentAndDefaultsToLocalOnConflict() {
        val local = snapshot(recordTitle = "本机标题")
        val imported = snapshot(recordTitle = "导入标题", exportedAt = "2026-09-04T00:00:00Z")
        val preview = MergePlanner.preview(local, imported)
        assertEquals(1, preview.conflicts.size)
        assertEquals("本机标题", MergePlanner.merge(local, imported).records.single().title)
        val decision = mapOf("record:${local.records.single().uuid}" to MergeChoice.USE_IMPORTED)
        val merged = MergePlanner.merge(local, imported, decision)
        assertEquals("导入标题", merged.records.single().title)
        assertEquals(0, MergePlanner.preview(merged, imported).additions)
    }

    @Test
    fun iosUppercaseUuidAndLowercaseAttachmentPathDecodeCanonically() {
        val original = letterUuidSnapshot()
        val imported = original.copy(
            sourceInstallationId = original.sourceInstallationId.uppercase(),
            members = original.members.map { it.copy(uuid = it.uuid.uppercase()) },
            conditions = original.conditions.map { it.copy(uuid = it.uuid.uppercase(), memberUuid = it.memberUuid?.uppercase()) },
            records = original.records.map {
                it.copy(uuid = it.uuid.uppercase(), memberUuid = it.memberUuid?.uppercase(), conditionUuid = it.conditionUuid?.uppercase())
            },
            attachments = original.attachments.map { it.copy(uuid = it.uuid.uppercase(), recordUuid = it.recordUuid.uppercase()) }
        )
        PortableSnapshotValidator.validate(imported)
        assertEquals(original, PortableJson.decode(PortableJson.encode(imported)))
        assertEquals(0, MergePlanner.preview(original, imported).additions)
        assertEquals(original.records.size, MergePlanner.merge(original, imported).records.size)
        assertEquals(original.attachments.size, MergePlanner.merge(original, imported).attachments.size)
    }

    @Test
    fun caseOnlyUuidDuplicatesAreRejectedInsteadOfMergedTwice() {
        val current = letterUuidSnapshot()
        val invalid = current.copy(members = current.members + current.members.single().copy(uuid = current.members.single().uuid.uppercase()))
        assertFails { PortableSnapshotValidator.validate(invalid) }
        assertFails { MergePlanner.merge(current, invalid) }
    }

    @Test
    fun uppercaseConflictDecisionStillSelectsImportedVersion() {
        val local = letterUuidSnapshot()
        val incoming = local.copy(records = local.records.map { it.copy(uuid = it.uuid.uppercase(), title = "导入版本") })
        val merged = MergePlanner.merge(local, incoming, mapOf("record:${local.records.single().uuid.uppercase()}" to MergeChoice.USE_IMPORTED))
        assertEquals("导入版本", merged.records.single().title)
        assertEquals(local.records.single().uuid, merged.records.single().uuid)
    }

    @Test
    fun sameDayOrderCollisionsAppendIncomingInStableOrderAndRemainIdempotent() {
        val base = snapshot(recordTitle = "本机后一条")
        val record = base.records.single()
        val local = base.copy(records = listOf(
            record.copy(dayOrder = 2),
            record.copy(uuid = "88888888-8888-4888-8888-888888888888", title = "本机前一条", dayOrder = 0)
        ))
        val incoming = base.copy(records = listOf(
            record.copy(uuid = "99999999-9999-4999-8999-999999999999", title = "导入第二条", dayOrder = 1),
            record.copy(uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", title = "导入第一条", dayOrder = 0)
        ))
        val merged = MergePlanner.merge(local, incoming)
        assertEquals(listOf("本机前一条", "本机后一条", "导入第一条", "导入第二条"), merged.records.map { it.title })
        assertEquals(listOf(0L, 2L, 3L, 4L), merged.records.map { it.dayOrder })
        assertEquals(merged, MergePlanner.merge(merged, incoming))
        assertEquals(0, MergePlanner.preview(merged, incoming).conflicts.size)
        assertEquals(0, MergePlanner.preview(merged, incoming).additions)
    }

    @Test
    fun canonicalizationCoversMedicationAndFollowUpReferencesWithoutChangingClinicalText() {
        val current = letterUuidSnapshot()
        val member = current.members.single().uuid.uppercase()
        val followUp = "BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB"
        val medication = "CCCCCCCC-CCCC-4CCC-8CCC-CCCCCCCCCCCC"
        val schedule = "DDDDDDDD-DDDD-4DDD-8DDD-DDDDDDDDDDDD"
        val expanded = current.copy(
            followUps = listOf(PortableFollowUpSchedule(
                uuid = followUp, title = "CT MRI 不改写", recurrenceType = "ONCE", interval = 1,
                anchorDate = "2026-09-03", anchorDayOfMonth = 3, reminderTime = "09:00", leadDays = 3,
                nextDueDate = "2026-09-03", enabled = true, createdAt = current.exportedAt, updatedAt = current.exportedAt, memberUuid = member
            )),
            occurrences = listOf(PortableFollowUpOccurrence(
                uuid = "EEEEEEEE-EEEE-4EEE-8EEE-EEEEEEEEEEEE", scheduleUuid = followUp, dueDate = "2026-09-03",
                status = "PENDING", createdAt = current.exportedAt
            )),
            medications = listOf(PortableMedication(
                uuid = medication, name = "ABC 测试药", doseAmount = "1", doseUnit = "片", instructions = "保留ABC",
                startDate = "2026-09-01", mode = "SCHEDULED", archived = false,
                createdAt = current.exportedAt, updatedAt = current.exportedAt, memberUuid = member
            )),
            medicationSchedules = listOf(PortableMedicationSchedule(
                uuid = schedule, medicationUuid = medication, localTime = "08:00", enabled = true,
                updatedAt = current.exportedAt, effectiveFrom = "2026-09-01", doseAmountSnapshot = "1", doseUnitSnapshot = "片"
            )),
            medicationLogs = listOf(PortableMedicationLog(
                uuid = "FFFFFFFF-FFFF-4FFF-8FFF-FFFFFFFFFFFF", medicationUuid = medication, scheduleUuid = schedule,
                scheduledAt = "2026-09-03T08:00", status = "TAKEN", doseAmountSnapshot = "1", doseUnitSnapshot = "片", createdAt = current.exportedAt
            ))
        )
        val canonical = PortableJson.decode(PortableJson.encode(expanded))
        assertEquals(followUp.lowercase(), canonical.occurrences.single().scheduleUuid)
        assertEquals(medication.lowercase(), canonical.medicationSchedules.single().medicationUuid)
        assertEquals(schedule.lowercase(), canonical.medicationLogs.single().scheduleUuid)
        assertEquals("ABC 测试药", canonical.medications.single().name)
        assertEquals("CT MRI 不改写", canonical.followUps.single().title)
    }

    @Test
    fun extremeDayOrderIsRejectedBeforeAnyIncrementOrPersistence() {
        val current = snapshot(recordTitle = "安全拒绝极值")
        for (order in listOf(Long.MAX_VALUE, Long.MAX_VALUE - 1)) {
            val invalid = current.copy(records = current.records.map { it.copy(dayOrder = order) })
            assertFails { PortableSnapshotValidator.validate(invalid) }
            assertFails { MergePlanner.merge(current, invalid) }
        }
    }

    @Test
    fun legacyV2PortableBackupStillDecodesBeforeMemberMapping() {
        val current = snapshot(recordTitle = "旧版记录")
        val legacy = current.copy(
            schemaVersion = LEGACY_PORTABLE_BACKUP_SCHEMA_VERSION,
            members = emptyList(),
            conditions = current.conditions.map { it.copy(memberUuid = null) },
            records = current.records.map { it.copy(memberUuid = null) }
        )
        assertEquals(LEGACY_PORTABLE_BACKUP_SCHEMA_VERSION, PortableJson.decode(PortableJson.encode(legacy)).schemaVersion)
    }

    @Test
    fun familyV3PortableBackupStillDecodesBeforeV4Conversion() {
        val current = snapshot(recordTitle = "旧版家庭记录")
        val legacy = current.copy(schemaVersion = FAMILY_BACKUP_SCHEMA_VERSION)

        assertEquals(FAMILY_BACKUP_SCHEMA_VERSION, PortableJson.decode(PortableJson.encode(legacy)).schemaVersion)
    }

    @Test
    fun v4PortableBackupStillDecodesBeforeTrashConversion() {
        val previous = snapshot(recordTitle = "v4 记录").copy(schemaVersion = PREVIOUS_BACKUP_SCHEMA_VERSION)
        assertEquals(PREVIOUS_BACKUP_SCHEMA_VERSION, PortableJson.decode(PortableJson.encode(previous)).schemaVersion)
    }

    @Test
    fun v5TrashFieldsRoundTripAndRequireDeletedChildAttachments() {
        val deletedAt = "2026-09-10T01:02:03Z"
        val current = snapshot(recordTitle = "回收站记录")
        val record = current.records.single().copy(deletedAt = deletedAt)
        val attachment = PortableAttachment(
            uuid = "77777777-7777-4777-8777-777777777777",
            recordUuid = record.uuid,
            kind = "PDF",
            displayName = "报告.pdf",
            mimeType = "application/pdf",
            archivePath = "files/77777777-7777-4777-8777-777777777777",
            sizeBytes = 4,
            sha256 = "a".repeat(64),
            createdAt = "2026-09-03T00:00:00Z",
            deletedAt = deletedAt
        )
        val deleted = current.copy(records = listOf(record), attachments = listOf(attachment))
        assertEquals(deleted, PortableJson.decode(PortableJson.encode(deleted)))
        assertFails {
            PortableSnapshotValidator.validate(deleted.copy(attachments = listOf(attachment.copy(deletedAt = null))))
        }
    }

    @Test
    fun v4MedicationHistoryFieldsRoundTrip() {
        val current = snapshot(recordTitle = "用药历史").let { value ->
            val medicationUuid = "55555555-5555-4555-8555-555555555555"
            value.copy(
                medications = listOf(
                    PortableMedication(
                        uuid = medicationUuid,
                        name = "测试药物",
                        doseAmount = "2",
                        doseUnit = "片",
                        instructions = "饭后",
                        startDate = "2026-09-01",
                        mode = "SCHEDULED",
                        archived = false,
                        createdAt = "2026-09-01T00:00:00Z",
                        updatedAt = "2026-09-05T00:00:00Z",
                        memberUuid = value.members.single().uuid
                    )
                ),
                medicationSchedules = listOf(
                    PortableMedicationSchedule(
                        uuid = "66666666-6666-4666-8666-666666666666",
                        medicationUuid = medicationUuid,
                        localTime = "08:05",
                        enabled = true,
                        updatedAt = "2026-09-05T00:00:00Z",
                        effectiveFrom = "2026-09-06",
                        effectiveTo = null,
                        doseAmountSnapshot = "2",
                        doseUnitSnapshot = "片"
                    )
                )
            )
        }

        val restored = PortableJson.decode(PortableJson.encode(current))

        assertEquals("2026-09-06", restored.medicationSchedules.single().effectiveFrom)
        assertEquals("2", restored.medicationSchedules.single().doseAmountSnapshot)
    }

    @Test
    fun v4RequiresCanonicalRelationshipCompatibilityField() {
        val current = snapshot(recordTitle = "关系字段")
        val invalid = current.copy(
            members = current.members.map { it.copy(nickname = "妈妈", relationship = "母亲") }
        )

        assertFails { PortableSnapshotValidator.validate(invalid) }
    }

    @Test
    fun crossMemberConditionReferenceIsRejected() {
        val current = snapshot(recordTitle = "复查")
        val other = PortableFamilyMember(
            "44444444-4444-4444-8444-444444444444", "李先生", "父亲", "父亲", false,
            "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z"
        )
        val invalid = current.copy(
            members = current.members + other,
            records = current.records.map { it.copy(memberUuid = other.uuid) }
        )
        assertFails { PortableSnapshotValidator.validate(invalid) }
    }

    @Test
    fun backupWithOnlyArchivedMembersIsRejected() {
        val invalid = snapshot(recordTitle = "复查").let { value ->
            value.copy(members = value.members.map { it.copy(archived = true) })
        }
        assertFails { PortableSnapshotValidator.validate(invalid) }
    }

    @Test
    fun duplicateMedicationSlotsAreRejectedBeforeDatabaseWrite() {
        val current = snapshot(recordTitle = "复查")
        val memberId = current.members.single().uuid
        val medicationId = "55555555-5555-4555-8555-555555555555"
        val medication = PortableMedication(
            uuid = medicationId,
            name = "药物",
            doseAmount = "1",
            doseUnit = "片",
            instructions = "",
            startDate = "2026-09-01",
            mode = "SCHEDULED",
            archived = false,
            createdAt = "2026-09-01T00:00:00Z",
            updatedAt = "2026-09-01T00:00:00Z",
            memberUuid = memberId
        )
        fun log(uuid: String) = PortableMedicationLog(
            uuid = uuid,
            medicationUuid = medicationId,
            scheduledAt = "2026-09-03T08:00",
            status = "TAKEN",
            doseAmountSnapshot = "1",
            doseUnitSnapshot = "片",
            createdAt = "2026-09-03T00:00:00Z"
        )
        val invalid = current.copy(
            medications = listOf(medication),
            medicationLogs = listOf(
                log("66666666-6666-4666-8666-666666666666"),
                log("77777777-7777-4777-8777-777777777777")
            )
        )
        assertFails { PortableSnapshotValidator.validate(invalid) }
    }

    private fun letterUuidSnapshot(): PortableSnapshot {
        val base = snapshot(recordTitle = "保留 CT MRI 原文")
        val member = "abcdefab-cdef-4abc-8abc-abcdefabcdef"
        val condition = "bcdefabc-defa-4bcd-8bcd-bcdefabcdefa"
        val record = "cdefabcd-efab-4cde-8cde-cdefabcdefab"
        val attachment = "defabcde-fabc-4def-8def-defabcdefabc"
        return base.copy(
            sourceInstallationId = "efabcdef-abcd-4efa-8efa-efabcdefabcd",
            members = base.members.map { it.copy(uuid = member) },
            conditions = base.conditions.map { it.copy(uuid = condition, memberUuid = member) },
            records = base.records.map { it.copy(uuid = record, memberUuid = member, conditionUuid = condition, dayOrder = 0) },
            attachments = listOf(PortableAttachment(
                uuid = attachment, recordUuid = record, kind = "PDF", displayName = "CT报告.pdf", mimeType = "application/pdf",
                archivePath = "files/$attachment", sizeBytes = 4, sha256 = "a".repeat(64), createdAt = base.exportedAt
            ))
        )
    }

    private fun snapshot(recordTitle: String, exportedAt: String = "2026-09-03T00:00:00Z"): PortableSnapshot {
        val conditionId = "11111111-1111-4111-8111-111111111111"
        val memberId = "33333333-3333-4333-8333-333333333333"
        return PortableSnapshot(
            exportedAt = exportedAt,
            sourcePlatform = "test",
            sourceInstallationId = "00000000-0000-4000-8000-000000000001",
            members = listOf(
                PortableFamilyMember(memberId, "张女士", "母亲", "母亲", false, "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")
            ),
            conditions = listOf(
                PortableCondition(conditionId, "乳腺", 0, "", false, "2026-09-01T00:00:00Z", memberUuid = memberId)
            ),
            records = listOf(
                PortableClinicalRecord(
                    uuid = "22222222-2222-4222-8222-222222222222",
                    conditionUuid = conditionId,
                    recordDate = "2026-09-03",
                    title = recordTitle,
                    stage = "CHECKUP",
                    symptoms = "",
                    diagnosis = "彩超结果稳定",
                    treatment = "",
                    medicationNotes = "",
                    hospital = "",
                    clinician = "",
                    notes = "",
                    createdAt = "2026-09-03T00:00:00Z",
                    updatedAt = "2026-09-03T00:00:00Z",
                    memberUuid = memberId,
                    dayOrder = 3
                )
            )
        )
    }
}
