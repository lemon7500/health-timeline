import XCTest
@testable import HealthTimeline

final class HealthTimelineTests: XCTestCase {
    func testAndroidLocalDateTimeWithoutSecondsIsAccepted() {
        XCTAssertNotNil(HealthDate.parseLocalDateTime("2026-09-03T08:05"))
        XCTAssertNotNil(HealthDate.parseLocalDateTime("2026-09-03T08:05:00"))
    }

    func testEveryThreeMonths() throws {
        let july = try XCTUnwrap(HealthDate.parseDay("2026-07-09"))
        let schedule = FollowUpSchedule(recurrenceType: "EVERY_N_MONTHS", interval: 3,
                                        anchorDate: july, anchorDayOfMonth: 9, nextDueDate: july)
        XCTAssertEqual(HealthDate.day(try XCTUnwrap(HealthDate.nextOccurrence(for: schedule))), "2026-10-09")
    }

    func testMonthlyAnchorReturnsAfterShortMonth() throws {
        let january = try XCTUnwrap(HealthDate.parseDay("2027-01-31"))
        var schedule = FollowUpSchedule(recurrenceType: "EVERY_N_MONTHS", interval: 1,
                                        anchorDate: january, anchorDayOfMonth: 31, nextDueDate: january)
        schedule.nextDueDate = try XCTUnwrap(HealthDate.nextOccurrence(for: schedule))
        XCTAssertEqual(HealthDate.day(schedule.nextDueDate), "2027-02-28")
        XCTAssertEqual(HealthDate.day(try XCTUnwrap(HealthDate.nextOccurrence(for: schedule))), "2027-03-31")
    }

    func testLegacyStateGetsMemberOrderAndScheduleHistory() throws {
        var state = HealthState()
        state.schemaVersion = 1
        state.records = [ClinicalRecord(recordDate: try XCTUnwrap(HealthDate.parseDay("2026-09-08")), title: "测试病历")]
        state.medications = [Medication(name: "测试药", doseAmount: "1", doseUnit: "片", startDate: try XCTUnwrap(HealthDate.parseDay("2026-09-01")), times: ["08:00"])]
        XCTAssertTrue(state.normalizeLegacyData())
        XCTAssertEqual(state.members.count, 1)
        XCTAssertEqual(state.records.first?.memberId, state.members.first?.id)
        XCTAssertEqual(state.records.first?.dayOrder, 0)
        XCTAssertEqual(state.medicationSchedules.first?.doseAmountSnapshot, "1")
    }

    func testLegacyOrderDoesNotCollideWithExistingOrder() throws {
        let member = FamilyMember()
        let day = try XCTUnwrap(HealthDate.parseDay("2026-09-08"))
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        state.records = [ClinicalRecord(memberId: member.id, recordDate: day, dayOrder: nil, title: "待迁移"),
                         ClinicalRecord(memberId: member.id, recordDate: day, dayOrder: 0, title: "已有顺序")]
        _ = state.normalizeLegacyData()
        XCTAssertEqual(Set(state.records.compactMap(\.dayOrder)), Set([0, 1]))
    }

    func testEncryptedContainerRoundTripAndWrongPassword() throws {
        let source = Data("跨平台备份测试".utf8)
        let encrypted = try BackupCrypto.encrypt(source, password: "password-123")
        XCTAssertEqual(String(data: encrypted.prefix(8), encoding: .ascii), "HTBACKUP")
        XCTAssertEqual(try BackupCrypto.decrypt(encrypted, password: "password-123"), source)
        XCTAssertThrowsError(try BackupCrypto.decrypt(encrypted, password: "wrong-password"))
    }

    func testPortableBackupZipRoundTripWithoutAttachments() throws {
        let member = FamilyMember()
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        state.records = [ClinicalRecord(memberId: member.id, recordDate: try XCTUnwrap(HealthDate.parseDay("2026-09-08")), dayOrder: 0, title: "上颌窦炎 复查")]
        let encrypted = try PortableBackupService.export(state: state, password: "password-123")
        let prepared = try PortableBackupService.prepareImport(data: encrypted, password: "password-123", local: HealthState(), targetMember: nil)
        XCTAssertEqual(prepared.snapshot.schemaVersion, 4)
        XCTAssertEqual(prepared.importedState.records.first?.title, "上颌窦炎 复查")
        XCTAssertTrue(prepared.attachmentBytes.isEmpty)
    }

    func testAiPromptNeverContainsClinicalInput() {
        let sensitive = "绝不能出现在提示词中的病历"
        let prompt = AiRecordFormattingPromptBuilder.build(defaultYear: 2026)
        XCTAssertTrue(prompt.contains("默认年份 2026"))
        XCTAssertTrue(prompt.contains("标题的第一部分必须是病情分类中的病名"))
        XCTAssertFalse(prompt.contains(sensitive))
    }

    func testStructuredQuickEntryKeepsOrderAndOriginalText() {
        let input = """
        日期：2026-08-29
        标题：颌骨骨髓炎 复查
        病情分类：颌骨骨髓炎
        记录类型：复查
        症状/病情：疼痛减轻
        诊断：疑似颌骨骨髓炎
        治疗方案：继续观察
        就诊用药记录：按医嘱
        医院：某医院
        医生：关医生
        其他备注：原文记录：第一段原文。

        日期：2026-12-01
        标题：颌骨骨髓炎 再次复查
        病情分类：颌骨骨髓炎
        记录类型：复查
        症状/病情：
        诊断：
        治疗方案：评估是否手术
        就诊用药记录：
        医院：某医院
        医生：关医生
        其他备注：原文记录：第二段原文。
        """
        let result = StructuredQuickEntryParser.parse(input)
        XCTAssertTrue(result.issues.isEmpty, result.issues.joined(separator: "\n"))
        XCTAssertEqual(result.drafts.map(\.recordDate), ["2026-08-29", "2026-12-01"])
        XCTAssertTrue(result.drafts[0].notes.contains("第一段原文"))
        XCTAssertTrue(result.drafts[1].notes.contains("第二段原文"))
    }

    func testStructuredQuickEntryRejectsMissingOriginalTextAndWrongTitleOrder() {
        var draft = QuickClinicalRecordDraft(recordDate: "2026-09-08", title: "复查 上颌窦炎", conditionName: "上颌窦炎", notes: "普通备注")
        var issues = StructuredQuickEntryParser.validate(draft, index: 1)
        XCTAssertTrue(issues.contains { $0.contains("原文记录") })
        XCTAssertTrue(issues.contains { $0.contains("标题必须先写病名") })
        draft.title = "上颌窦炎 复查"; draft.notes = "原文记录：完整原文"
        issues = StructuredQuickEntryParser.validate(draft, index: 1)
        XCTAssertTrue(issues.isEmpty)
    }

    func testMergeKeepsLocalConflictAndAddsNewRecord() throws {
        let member = FamilyMember()
        var local = HealthState(); local.members = [member]; local.selectedMemberId = member.id
        let sharedId = UUID()
        local.records = [ClinicalRecord(id: sharedId, memberId: member.id, title: "本机版本")]
        var imported = HealthState(); imported.members = [member]; imported.selectedMemberId = member.id
        imported.records = [ClinicalRecord(id: sharedId, memberId: member.id, title: "导入版本"), ClinicalRecord(memberId: member.id, title: "新增资料")]
        let result = try PortableBackupService.merge(local: local, imported: imported)
        XCTAssertEqual(result.records.count, 2)
        XCTAssertEqual(result.records.first(where: { $0.id == sharedId })?.title, "本机版本")
        let importedResult = try PortableBackupService.merge(local: local, imported: imported, useImported: ["record:\(sharedId.uuidString)"])
        XCTAssertEqual(importedResult.records.first(where: { $0.id == sharedId })?.title, "导入版本")
    }

    func testAttachmentConflictUsesImportedVersionOnlyWhenSelected() throws {
        let member = FamilyMember()
        let record = ClinicalRecord(memberId: member.id, title: "测试病历")
        let attachmentId = UUID()
        let localAttachment = Attachment(id: attachmentId, recordId: record.id, displayName: "本机.pdf", relativePath: "local/file", sizeBytes: 1, sha256: String(repeating: "a", count: 64))
        let importedAttachment = Attachment(id: attachmentId, recordId: record.id, displayName: "导入.pdf", relativePath: "import/file", sizeBytes: 1, sha256: String(repeating: "b", count: 64))
        var local = HealthState(); local.members = [member]; local.selectedMemberId = member.id; local.records = [record]; local.attachments = [localAttachment]
        var imported = local; imported.attachments = [importedAttachment]

        let preview = PortableBackupService.preview(local: local, imported: imported)
        XCTAssertEqual(preview.conflicts.first?.key, "attachment:\(attachmentId.uuidString)")
        XCTAssertEqual(try PortableBackupService.merge(local: local, imported: imported).attachments.first?.displayName, "本机.pdf")
        XCTAssertEqual(
            try PortableBackupService.merge(local: local, imported: imported, useImported: ["attachment:\(attachmentId.uuidString)"]).attachments.first?.displayName,
            "导入.pdf"
        )
    }

    @MainActor
    func testStartupLoadFailureCannotBeDismissedIntoWritableEmptyStore() throws {
        let database = TestHealthStateDatabase()
        database.loadFailure = StorageError.corruptData
        let store = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        XCTAssertFalse(store.storageReady)
        store.clearError()
        XCTAssertFalse(store.saveMember(name: "不能覆盖旧数据", relationship: "本人"))
        XCTAssertEqual(database.saveCount, 0)
        XCTAssertThrowsError(try store.exportBackup(password: "password-123"))
        XCTAssertThrowsError(try store.prepareBackupImport(data: Data(), password: "password-123"))
        XCTAssertFalse(store.storageReady)
    }

    @MainActor
    func testTransientSaveFailureKeepsLoadedDataAndDoesNotDisableSafeRetry() throws {
        let member = FamilyMember()
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        state.records = [ClinicalRecord(memberId: member.id, dayOrder: 0, title: "已有资料")]
        let database = TestHealthStateDatabase(state: state)
        let store = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        XCTAssertTrue(store.storageReady)
        database.saveFailure = StorageError.database("测试写入失败")
        XCTAssertFalse(store.saveMember(name: "测试成员", relationship: "父亲"))
        XCTAssertEqual(store.state, state)
        store.clearError(); database.saveFailure = nil
        XCTAssertTrue(store.saveMember(name: "测试成员", relationship: "父亲"))
        XCTAssertEqual(store.state.records.first?.title, "已有资料")
        XCTAssertEqual(store.state.members.count, 2)
    }

    func testLegacyBackupRejectsDuplicateMedicationIdentityBeforeDictionaryConstruction() throws {
        let member = FamilyMember()
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        state.medications = [Medication(memberId: member.id, name: "测试药", doseAmount: "1", doseUnit: "片")]
        var snapshot = try PortableBackupMapper.export(state)
        snapshot.medications.append(snapshot.medications[0])
        for version in [2, 3] {
            snapshot.schemaVersion = version
            XCTAssertThrowsError(try PortableBackupMapper.normalized(snapshot, targetMember: member))
        }
    }

    @MainActor
    func testStartupMaximumDayOrderIsRejectedBeforeLegacyNormalization() throws {
        let member = FamilyMember()
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        state.records = [ClinicalRecord(memberId: member.id, dayOrder: Int64.max, title: "异常顺序")]
        let database = TestHealthStateDatabase(state: state)
        let store = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        XCTAssertFalse(store.storageReady)
        XCTAssertEqual(database.saveCount, 0)
        XCTAssertEqual(database.state.records.first?.dayOrder, Int64.max)
    }

    func testPortableWireUuidUsesLowercaseForIdentifiersAndAttachmentReferences() throws {
        let member = FamilyMember(id: try XCTUnwrap(UUID(uuidString: "ABCDEFAB-CDEF-4ABC-8ABC-ABCDEFABCDEF")))
        let record = ClinicalRecord(id: try XCTUnwrap(UUID(uuidString: "BCDEFABC-DEFA-4BCD-8BCD-BCDEFABCDEFA")), memberId: member.id, dayOrder: 0, title: "CT MRI 不改变大小写")
        let attachment = Attachment(id: try XCTUnwrap(UUID(uuidString: "CDEFABCD-EFAB-4CDE-8CDE-CDEFABCDEFAB")), recordId: record.id, displayName: "CT报告.pdf", relativePath: "local/file", sizeBytes: 1, sha256: String(repeating: "a", count: 64))
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        state.records = [record]; state.attachments = [attachment]
        let encoded = try PortableBackupMapper.encodedCanonical(PortableBackupMapper.export(state))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        let members = try XCTUnwrap(object["members"] as? [[String: Any]])
        let records = try XCTUnwrap(object["records"] as? [[String: Any]])
        let attachments = try XCTUnwrap(object["attachments"] as? [[String: Any]])
        XCTAssertEqual(members[0]["uuid"] as? String, member.id.uuidString.lowercased())
        XCTAssertEqual(records[0]["memberUuid"] as? String, member.id.uuidString.lowercased())
        XCTAssertEqual(attachments[0]["uuid"] as? String, attachment.id.uuidString.lowercased())
        XCTAssertEqual(attachments[0]["recordUuid"] as? String, record.id.uuidString.lowercased())
        XCTAssertEqual(attachments[0]["archivePath"] as? String, "files/\(attachment.id.uuidString.lowercased())")
        XCTAssertEqual(records[0]["title"] as? String, "CT MRI 不改变大小写")
    }

    func testSameDayIndependentRecordsMergeAfterLocalOrderAndReimportIdempotently() throws {
        let member = FamilyMember()
        let day = try XCTUnwrap(HealthDate.parseDay("2026-09-08"))
        var local = HealthState(); local.members = [member]; local.selectedMemberId = member.id
        local.records = [ClinicalRecord(memberId: member.id, recordDate: day, dayOrder: 2, title: "本机第二条"),
                         ClinicalRecord(memberId: member.id, recordDate: day, dayOrder: 0, title: "本机第一条")]
        var imported = local
        imported.records = [ClinicalRecord(memberId: member.id, recordDate: day, dayOrder: 1, title: "导入第二条"),
                            ClinicalRecord(memberId: member.id, recordDate: day, dayOrder: 0, title: "导入第一条")]
        let merged = try PortableBackupService.merge(local: local, imported: imported)
        XCTAssertEqual(merged.records.map(\.title), ["本机第一条", "本机第二条", "导入第一条", "导入第二条"])
        XCTAssertEqual(merged.records.compactMap(\.dayOrder), [0, 2, 3, 4])
        XCTAssertEqual(try PortableBackupService.merge(local: merged, imported: imported), merged)
        XCTAssertEqual(PortableBackupService.preview(local: merged, imported: imported).conflicts.count, 0)
        XCTAssertEqual(PortableBackupService.preview(local: merged, imported: imported).additions, 0)
    }

    func testPortableImportRepairsDerivedOrderCollisionAndRejectsMaximumOrderSafely() throws {
        let member = FamilyMember()
        let day = try XCTUnwrap(HealthDate.parseDay("2026-09-08"))
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        state.records = [ClinicalRecord(memberId: member.id, recordDate: day, dayOrder: 0, title: "第一条"),
                         ClinicalRecord(memberId: member.id, recordDate: day, dayOrder: 1, title: "第二条")]
        var snapshot = try PortableBackupMapper.export(state)
        snapshot.records[1].dayOrder = 0
        XCTAssertEqual(try PortableBackupMapper.normalized(snapshot, targetMember: member).records.compactMap(\.dayOrder), [0, 1])
        snapshot.records[1].dayOrder = Int64.max
        XCTAssertThrowsError(try PortableBackupMapper.normalized(snapshot, targetMember: member))
    }

    func testPausedByCourseEndSurvivesDatabaseCodableRoundTrip() throws {
        let source = MedicationSchedule(medicationId: UUID(), enabled: false, effectiveFrom: "2026-09-08", doseAmountSnapshot: "1", doseUnitSnapshot: "片", pausedByCourseEnd: true)
        let encoded = try JSONEncoder().encode(source)
        let restored = try JSONDecoder().decode(MedicationSchedule.self, from: encoded)
        XCTAssertTrue(restored.pausedByCourseEnd)
        XCTAssertFalse(restored.enabled)
    }

    func testCalendarDaysAndLocalMedicationClockSurviveTimeZoneAndStorageRoundTrip() throws {
        let originalZone = NSTimeZone.default
        defer { NSTimeZone.default = originalZone }
        NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        let member = FamilyMember()
        let day = try XCTUnwrap(HealthDate.parseDay("2026-09-09"))
        let clock = try XCTUnwrap(HealthDate.parseLocalDateTime("2026-09-09T08:05:00"))
        var source = HealthState(); source.members = [member]; source.selectedMemberId = member.id
        source.records = [ClinicalRecord(memberId: member.id, recordDate: day, dayOrder: 0, title: "测试病历")]
        let followUp = FollowUpSchedule(memberId: member.id, title: "测试复查", recurrenceType: "EVERY_N_MONTHS", interval: 3, anchorDate: day, anchorDayOfMonth: 9, nextDueDate: day)
        source.followUps = [followUp]
        source.occurrences = [FollowUpOccurrence(scheduleId: followUp.id, dueDate: day)]
        let medication = Medication(memberId: member.id, name: "测试药", doseAmount: "1", doseUnit: "片", startDate: day, endDate: day, mode: "AS_NEEDED")
        source.medications = [medication]
        source.medicationLogs = [MedicationLog(medicationId: medication.id, scheduledAt: clock, actualAt: clock, status: "TAKEN", doseAmountSnapshot: "1", doseUnitSnapshot: "片")]
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let stored = try encoder.encode(source)
        let exported = try PortableBackupMapper.export(source)
        for zone in ["America/Los_Angeles", "UTC", "Asia/Tokyo"] {
            NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: zone))
            let reopened = try decoder.decode(HealthState.self, from: stored)
            let roundTrip = try PortableBackupMapper.export(PortableBackupMapper.toState(exported, attachmentPaths: [:]))
            let reopenedExport = try PortableBackupMapper.export(reopened)
            for result in [roundTrip, reopenedExport] {
                XCTAssertEqual(result.records[0].recordDate, "2026-09-09")
                XCTAssertEqual(result.followUps[0].anchorDate, "2026-09-09")
                XCTAssertEqual(result.followUps[0].nextDueDate, "2026-09-09")
                XCTAssertEqual(result.occurrences[0].dueDate, "2026-09-09")
                XCTAssertEqual(result.medications[0].startDate, "2026-09-09")
                XCTAssertEqual(result.medications[0].endDate, "2026-09-09")
                XCTAssertEqual(result.medicationLogs[0].scheduledAt, "2026-09-09T08:05:00")
                XCTAssertEqual(result.medicationLogs[0].actualAt, "2026-09-09T08:05:00")
            }
            XCTAssertEqual(HealthDate.nextOccurrenceDay(for: reopened.followUps[0]), "2026-12-09")
        }
    }

    func testUnrepresentableDSTClockKeepsOriginalStringInBackupAndStorage() throws {
        let originalZone = NSTimeZone.default
        defer { NSTimeZone.default = originalZone }
        NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        let member = FamilyMember()
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        let medication = Medication(memberId: member.id, name: "测试药", doseAmount: "1", doseUnit: "片", startDate: try XCTUnwrap(HealthDate.parseDay("2026-03-01")), mode: "AS_NEEDED")
        state.medications = [medication]
        var log = MedicationLog(medicationId: medication.id, scheduledAt: Date(), status: "TAKEN", doseAmountSnapshot: "1", doseUnitSnapshot: "片")
        // 02:05 never occurred locally on this DST transition date.
        try log.setLocalTimes(scheduled: "2026-03-08T02:05", actual: "2026-03-08T02:10:00")
        state.medicationLogs = [log]
        let snapshot = try PortableBackupMapper.export(state)
        let imported = try PortableBackupMapper.toState(snapshot, attachmentPaths: [:])
        let beforeDisplay = imported
        _ = imported.medicationLogs[0].scheduledAt // UI adaptation must be pure.
        XCTAssertEqual(imported, beforeDisplay)
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let reopened = try decoder.decode(HealthState.self, from: encoder.encode(imported))
        let result = try PortableBackupMapper.export(reopened)
        XCTAssertEqual(result.medicationLogs[0].scheduledAt, "2026-03-08T02:05:00")
        XCTAssertEqual(result.medicationLogs[0].actualAt, "2026-03-08T02:10:00")
        XCTAssertEqual(reopened.medicationLogs[0].$scheduledAt, imported.medicationLogs[0].$scheduledAt)
    }

    @MainActor
    func testQuickEntryAndMergeKeepSkippedCalendarDayWithoutRewriting() throws {
        let originalZone = NSTimeZone.default
        defer { NSTimeZone.default = originalZone }
        NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: "Pacific/Apia"))
        let member = FamilyMember()
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        let store = HealthStore(databaseFactory: { TestHealthStateDatabase(state: state) }, reconcileAttachments: { _ in })
        let text = "日期：2011-12-30\n标题：测试病情 复查\n病情分类：测试病情\n记录类型：复查\n其他备注：原文记录：测试原文"
        let parsed = StructuredQuickEntryParser.parse(text)
        XCTAssertTrue(parsed.issues.isEmpty)
        XCTAssertTrue(store.addQuickRecords(parsed.drafts, memberId: member.id))
        XCTAssertEqual(store.state.records[0].$recordDate, "2011-12-30")
        var incoming = store.state
        incoming.records[0].id = UUID()
        let merged = try PortableBackupService.merge(local: store.state, imported: incoming)
        XCTAssertEqual(merged.records.map { $0.$recordDate }, ["2011-12-30", "2011-12-30"])
        XCTAssertEqual(try PortableBackupMapper.export(merged).records.map(\.recordDate), ["2011-12-30", "2011-12-30"])
    }

    func testWrappedOptionalDatesDecodeWhenLegacyKeysAreMissing() throws {
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        var medication = try XCTUnwrap(JSONSerialization.jsonObject(with: encoder.encode(Medication())) as? [String: Any])
        medication.removeValue(forKey: "endDate"); medication.removeValue(forKey: "archivedPreviousEndDate")
        let decodedMedication = try decoder.decode(Medication.self, from: JSONSerialization.data(withJSONObject: medication))
        XCTAssertNil(decodedMedication.endDate); XCTAssertNil(decodedMedication.archivedPreviousEndDate)
        let source = MedicationLog(medicationId: UUID(), scheduledAt: Date(), status: "SKIPPED", doseAmountSnapshot: "1", doseUnitSnapshot: "片")
        var log = try XCTUnwrap(JSONSerialization.jsonObject(with: encoder.encode(source)) as? [String: Any])
        log.removeValue(forKey: "actualAt")
        XCTAssertNil(try decoder.decode(MedicationLog.self, from: JSONSerialization.data(withJSONObject: log)).actualAt)
    }

    func testEditingOnlyNarrativeOrDoseDoesNotRewriteUnrepresentableCalendarDates() throws {
        let originalZone = NSTimeZone.default
        defer { NSTimeZone.default = originalZone }
        NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: "Pacific/Apia"))
        var record = ClinicalRecord(title: "原标题")
        try record.setRecordDay("2011-12-30")
        let adapted = record.recordDate
        record.title = "仅修改标题"
        record.applyEditedRecordDate(adapted, wasEdited: false)
        XCTAssertEqual(record.$recordDate, "2011-12-30")
        var medication = Medication(doseAmount: "1")
        try medication.setCourseDays(start: "2011-12-30", end: "2011-12-30", previousEnd: nil)
        medication.doseAmount = "2"
        medication.applyEditedCourseDates(start: medication.startDate, startWasEdited: false,
                                          includeEnd: true, end: try XCTUnwrap(medication.endDate), endWasEdited: false)
        XCTAssertEqual(medication.$startDate, "2011-12-30")
        XCTAssertEqual(medication.$endDate, "2011-12-30")
        let changed = try XCTUnwrap(HealthDate.parseDay("2012-01-02"))
        record.applyEditedRecordDate(changed, wasEdited: true)
        medication.applyEditedCourseDates(start: changed, startWasEdited: true,
                                          includeEnd: false, end: changed, endWasEdited: false)
        XCTAssertEqual(record.$recordDate, "2012-01-02")
        XCTAssertEqual(medication.$startDate, "2012-01-02")
        XCTAssertNil(medication.$endDate)
    }

    @MainActor
    func testUnknownFutureCalendarEncodingDoesNotDowngradeOrWrite() {
        var state = HealthState(); state.calendarDateEncodingVersion = 2
        let database = TestHealthStateDatabase(state: state)
        let store = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        XCTAssertFalse(store.storageReady)
        store.clearError()
        XCTAssertFalse(store.saveMember(name: "禁止写入", relationship: "本人"))
        XCTAssertEqual(database.saveCount, 0)
        XCTAssertEqual(database.state.calendarDateEncodingVersion, 2)
    }

    @MainActor
    func testLegacyCalendarMigrationRetainsEncryptedOriginalAndRollsBackOnFailure() throws {
        let originalZone = NSTimeZone.default
        defer { NSTimeZone.default = originalZone }
        NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        let member = FamilyMember()
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        state.records = [ClinicalRecord(memberId: member.id, dayOrder: 0, title: "测试旧资料")]
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoder.encode(state)) as? [String: Any])
        object.removeValue(forKey: "calendarDateEncodingVersion")
        var records = try XCTUnwrap(object["records"] as? [[String: Any]])
        records[0]["recordDate"] = "2026-09-08T16:00:00Z"
        object["records"] = records
        let original = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let database = try SecureDatabase(directory: directory, encryptionKey: Data(repeating: 7, count: 32))
        try database.seedLegacyPayloadForTesting(original)
        database.failAfterMigrationBackupForTesting = true
        let failed = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        XCTAssertFalse(failed.storageReady)
        failed.clearError()
        XCTAssertFalse(failed.saveMember(name: "不能写入", relationship: "本人"))
        XCTAssertEqual(try database.currentPayloadForTesting(), original)
        XCTAssertNil(try database.retainedMigrationPayloadForTesting())
        database.failAfterMigrationBackupForTesting = false
        let migrated = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        XCTAssertTrue(migrated.storageReady)
        XCTAssertEqual(migrated.state.records[0].$recordDate, "2026-09-09")
        XCTAssertTrue(migrated.noticeMessage?.contains("原始加密资料已保留") == true)
        XCTAssertEqual(try database.retainedMigrationPayloadForTesting(), original)
        let reopened = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        XCTAssertTrue(reopened.storageReady)
        XCTAssertNil(reopened.noticeMessage)
    }

    @MainActor
    func testFollowUpHandlesPendingCycleOnceAndPreservesOccurrenceIdentity() throws {
        let member = FamilyMember()
        let due = try XCTUnwrap(HealthDate.parseDay("2027-01-31"))
        let now = try XCTUnwrap(HealthDate.parseLocalDateTime("2027-01-31T12:00:00"))
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        let schedule = FollowUpSchedule(memberId: member.id, title: "测试复查", recurrenceType: "EVERY_N_MONTHS", anchorDate: due, anchorDayOfMonth: 31, nextDueDate: due)
        let pending = FollowUpOccurrence(scheduleId: schedule.id, dueDate: due)
        state.followUps = [schedule]; state.occurrences = [pending]
        let database = TestHealthStateDatabase(state: state)
        let store = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        XCTAssertTrue(store.completeFollowUp(schedule.id, dueDay: "2027-01-31", memberId: member.id, now: now))
        XCTAssertEqual(store.state.followUps[0].$nextDueDate, "2027-02-28")
        XCTAssertEqual(store.state.occurrences.count, 1)
        XCTAssertEqual(store.state.occurrences[0].id, pending.id)
        XCTAssertEqual(store.state.occurrences[0].createdAt, pending.createdAt)
        XCTAssertEqual(store.state.occurrences[0].status, "DONE")
        let saves = database.saveCount
        XCTAssertFalse(store.completeFollowUp(schedule.id, dueDay: "2027-01-31", memberId: member.id, skipped: true, now: now))
        XCTAssertFalse(store.completeFollowUp(schedule.id, dueDay: "2027-02-28", memberId: member.id, now: now))
        XCTAssertEqual(database.saveCount, saves)
        XCTAssertEqual(HealthDate.nextOccurrenceDay(for: store.state.followUps[0]), "2027-03-31")
    }

    @MainActor
    func testFollowUpCanCompleteWithoutNotificationAndRejectsStaleMember() throws {
        let member = FamilyMember(); let other = FamilyMember(name: "测试成员", relationship: "父亲")
        let due = try XCTUnwrap(HealthDate.parseDay("2026-12-31"))
        let now = try XCTUnwrap(HealthDate.parseLocalDateTime("2026-12-31T12:00:00"))
        var state = HealthState(); state.members = [member, other]; state.selectedMemberId = other.id
        let schedule = FollowUpSchedule(memberId: member.id, title: "测试复查", recurrenceType: "EVERY_N_WEEKS", anchorDate: due, anchorDayOfMonth: 31, weekday: 4, nextDueDate: due)
        state.followUps = [schedule]
        let database = TestHealthStateDatabase(state: state)
        let store = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        XCTAssertFalse(store.completeFollowUp(schedule.id, dueDay: "2026-12-31", memberId: member.id, now: now))
        store.selectMember(member.id)
        XCTAssertTrue(store.completeFollowUp(schedule.id, dueDay: "2026-12-31", memberId: member.id, skipped: true, now: now))
        XCTAssertEqual(store.state.occurrences.count, 1)
        XCTAssertEqual(store.state.occurrences[0].status, "SKIPPED")
        XCTAssertEqual(store.state.followUps[0].$nextDueDate, "2027-01-07")
        XCTAssertFalse(store.completeFollowUp(schedule.id, dueDay: "2026-12-31", memberId: member.id, now: now))
    }

    func testReminderPlanningDeduplicatesHandledCyclesAndRecordedMedicationSlots() throws {
        let member = FamilyMember()
        let day = try XCTUnwrap(HealthDate.parseDay("2026-09-09"))
        let now = try XCTUnwrap(HealthDate.parseLocalDateTime("2026-09-09T07:00:00"))
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        let schedule = FollowUpSchedule(memberId: member.id, title: "测试复查", anchorDate: day, anchorDayOfMonth: 9, nextDueDate: day)
        state.followUps = [schedule]
        state.occurrences = [FollowUpOccurrence(scheduleId: schedule.id, dueDate: day, status: "DONE")]
        let medication = Medication(memberId: member.id, name: "测试药", doseAmount: "1", doseUnit: "片", startDate: day, endDate: day)
        state.medications = [medication]
        state.medicationSchedules = [MedicationSchedule(medicationId: medication.id, localTime: "08:05", effectiveFrom: "2026-09-09", effectiveTo: "2026-09-09", doseAmountSnapshot: "1", doseUnitSnapshot: "片"),
                                     MedicationSchedule(medicationId: medication.id, localTime: "08:05", effectiveFrom: "2026-09-09", effectiveTo: "2026-09-09", doseAmountSnapshot: "1", doseUnitSnapshot: "片")]
        XCTAssertEqual(LocalReminderScheduler.plannedReminders(state, now: now).count, 1)
        let clock = try XCTUnwrap(HealthDate.parseLocalDateTime("2026-09-09T08:05:00"))
        state.medicationLogs = [MedicationLog(medicationId: medication.id, scheduledAt: clock, status: "SKIPPED", doseAmountSnapshot: "1", doseUnitSnapshot: "片")]
        XCTAssertTrue(LocalReminderScheduler.plannedReminders(state, now: now).isEmpty)
        state.occurrences[0].status = "PENDING"
        XCTAssertEqual(LocalReminderScheduler.plannedReminders(state, now: now).count, 1)
    }

    func testOverdueFollowUpCatchUpNotificationKeepsFutureSeconds() throws {
        let member = FamilyMember()
        let day = try XCTUnwrap(HealthDate.parseDay("2026-09-09"))
        let now = try XCTUnwrap(HealthDate.parseLocalDateTime("2026-09-09T12:34:10"))
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        state.followUps = [FollowUpSchedule(memberId: member.id, title: "测试复查", anchorDate: day, anchorDayOfMonth: 9, nextDueDate: day)]
        let planned = try XCTUnwrap(LocalReminderScheduler.plannedReminders(state, now: now).first)
        let components = LocalReminderScheduler.notificationComponents(for: planned.fireDate)
        XCTAssertEqual(components.second, 25)
        let reconstructed = try XCTUnwrap(HealthDate.calendar.date(from: components))
        XCTAssertGreaterThan(reconstructed, now)
        XCTAssertEqual(reconstructed, now.addingTimeInterval(15))
    }

    @MainActor
    func testManualOversizedFieldsCannotPersistDataRejectedAtNextLaunch() throws {
        let member = FamilyMember()
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        let record = ClinicalRecord(memberId: member.id, dayOrder: 0, title: "已有病历")
        state.records = [record]
        let database = TestHealthStateDatabase(state: state)
        let store = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        let saveCount = database.saveCount
        var invalidHospital = ClinicalRecord(memberId: member.id, title: "测试病历"); invalidHospital.hospital = String(repeating: "院", count: 201)
        var invalidClinician = ClinicalRecord(memberId: member.id, title: "测试病历"); invalidClinician.clinician = String(repeating: "医", count: 101)
        for candidate in [invalidHospital, invalidClinician] {
            store.clearError()
            XCTAssertFalse(store.addRecord(candidate))
            XCTAssertEqual(store.state, state)
            XCTAssertEqual(database.state, state)
            XCTAssertEqual(database.saveCount, saveCount)
        }
        store.clearError()
        XCTAssertFalse(store.addRecord(ClinicalRecord(memberId: member.id, title: "测试病历"), conditionName: String(repeating: "病", count: 51)))
        XCTAssertEqual(store.state.conditions.count, 0)
        store.clearError()
        var invalidEdit = record; invalidEdit.clinician = String(repeating: "医", count: 101)
        XCTAssertFalse(store.updateRecord(invalidEdit))
        XCTAssertEqual(database.state, state)
        XCTAssertEqual(database.saveCount, saveCount)
        let reopened = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        XCTAssertTrue(reopened.storageReady)
        XCTAssertEqual(reopened.state, state)
    }

    @MainActor
    func testDoseSubmissionRejectsStaleMemberArchivedCourseAndChangedSchedule() throws {
        let member = FamilyMember(); let other = FamilyMember(name: "测试成员", relationship: "父亲")
        let day = try XCTUnwrap(HealthDate.parseDay("2026-09-09"))
        let clock = try XCTUnwrap(HealthDate.parseLocalDateTime("2026-09-09T08:05:00"))
        var state = HealthState(); state.members = [member, other]; state.selectedMemberId = other.id
        let medication = Medication(memberId: member.id, name: "测试药", doseAmount: "1", doseUnit: "片", startDate: day)
        let schedule = MedicationSchedule(medicationId: medication.id, localTime: "08:05", effectiveFrom: "2026-09-09", doseAmountSnapshot: "1", doseUnitSnapshot: "片")
        state.medications = [medication]; state.medicationSchedules = [schedule]
        let database = TestHealthStateDatabase(state: state)
        let store = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        XCTAssertFalse(store.recordDose(medication: medication, schedule: schedule, scheduledAt: clock, actualAt: clock, status: "TAKEN", now: clock))
        store.selectMember(member.id)
        _ = store.mutate { $0.medications[0].archived = true }
        XCTAssertFalse(store.recordDose(medication: medication, schedule: schedule, scheduledAt: clock, actualAt: clock, status: "TAKEN", now: clock))
        _ = store.mutate { $0.medications[0].archived = false; $0.medicationSchedules[0].localTime = "09:05" }
        XCTAssertFalse(store.recordDose(medication: medication, schedule: schedule, scheduledAt: clock, actualAt: clock, status: "TAKEN", now: clock))
        XCTAssertTrue(store.state.medicationLogs.isEmpty)
    }

    @MainActor
    func testDoseCorrectionPreservesExplicitLocalClockEvenInDSTGap() throws {
        let originalZone = NSTimeZone.default
        defer { NSTimeZone.default = originalZone }
        NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        let member = FamilyMember()
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        let medication = Medication(memberId: member.id, name: "测试药", doseAmount: "1", doseUnit: "片", startDate: try XCTUnwrap(HealthDate.parseDay("2026-03-01")), mode: "AS_NEEDED")
        state.medications = [medication]
        var log = MedicationLog(medicationId: medication.id, scheduledAt: Date(), status: "SKIPPED", doseAmountSnapshot: "1", doseUnitSnapshot: "片")
        try log.setLocalTimes(scheduled: "2026-03-08T02:05:00", actual: nil)
        state.medicationLogs = [log]
        let store = HealthStore(databaseFactory: { TestHealthStateDatabase(state: state) }, reconcileAttachments: { _ in })
        XCTAssertTrue(store.correctDose(logId: log.id, status: "TAKEN", actualAt: HealthDate.parseLocalDateTime("2026-03-08T02:10:00"), actualLocalTime: "2026-03-08T02:10:00"))
        XCTAssertEqual(store.state.medicationLogs[0].$actualAt, "2026-03-08T02:10:00")
        XCTAssertEqual(store.state.medicationLogs[0].id, log.id)
        XCTAssertEqual(store.state.medicationLogs[0].doseAmountSnapshot, "1")
        XCTAssertEqual(try PortableBackupMapper.export(store.state).medicationLogs[0].actualAt, "2026-03-08T02:10:00")
    }

    @MainActor
    func testDoseRecordingAndCorrectionRejectFutureActualClockWithoutWriting() throws {
        let member = FamilyMember()
        let day = try XCTUnwrap(HealthDate.parseDay("2026-09-09"))
        let now = try XCTUnwrap(HealthDate.parseLocalDateTime("2026-09-09T12:00:00"))
        let future = try XCTUnwrap(HealthDate.parseLocalDateTime("2026-09-09T20:05:00"))
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        let medication = Medication(memberId: member.id, name: "测试药", doseAmount: "1", doseUnit: "片", startDate: day, mode: "AS_NEEDED")
        state.medications = [medication]
        let existing = MedicationLog(medicationId: medication.id, scheduledAt: now, status: "SKIPPED", doseAmountSnapshot: "1", doseUnitSnapshot: "片")
        state.medicationLogs = [existing]
        let database = TestHealthStateDatabase(state: state)
        let store = HealthStore(databaseFactory: { database }, reconcileAttachments: { _ in })
        let saves = database.saveCount
        XCTAssertFalse(store.recordDose(medication: medication, schedule: nil, scheduledAt: now, actualAt: future, status: "TAKEN", now: now))
        XCTAssertFalse(store.correctDose(logId: existing.id, status: "TAKEN", actualAt: future, now: now))
        XCTAssertFalse(store.correctDose(logId: existing.id, status: "TAKEN", actualAt: now, actualLocalTime: "2026-09-09T20:05:00", now: now))
        XCTAssertEqual(database.saveCount, saves)
        XCTAssertEqual(store.state.medicationLogs[0], existing)
    }

    @MainActor
    func testEditingSameFutureMedicationVersionDoesNotDuplicateOrOverwritePastDose() throws {
        let member = FamilyMember()
        let day = try XCTUnwrap(HealthDate.parseDay("2026-09-09"))
        let future = try XCTUnwrap(HealthDate.parseDay("2026-09-10"))
        var state = HealthState(); state.members = [member]; state.selectedMemberId = member.id
        let medication = Medication(memberId: member.id, name: "测试药", doseAmount: "1", doseUnit: "片", startDate: day)
        state.medications = [medication]
        let prior = MedicationSchedule(medicationId: medication.id, effectiveFrom: "2026-09-09", doseAmountSnapshot: "1", doseUnitSnapshot: "片")
        state.medicationSchedules = [prior]
        let store = HealthStore(databaseFactory: { TestHealthStateDatabase(state: state) }, reconcileAttachments: { _ in })
        var edited = medication; edited.doseAmount = "2"
        XCTAssertTrue(store.updateMedication(edited, times: ["09:05"], effectiveDate: future))
        edited = store.state.medications[0]; edited.doseAmount = "3"
        XCTAssertTrue(store.updateMedication(edited, times: ["10:05"], effectiveDate: future))
        XCTAssertEqual(store.state.medicationSchedules.count, 2)
        XCTAssertEqual(store.state.medicationSchedules.first(where: { $0.id == prior.id })?.doseAmountSnapshot, "1")
        XCTAssertEqual(store.state.medicationSchedules.first(where: { $0.id == prior.id })?.effectiveTo, "2026-09-09")
        XCTAssertEqual(store.medicationEntries(on: day).first?.schedule?.doseAmountSnapshot, "1")
        XCTAssertEqual(store.medicationEntries(on: future).first?.schedule?.localTime, "10:05")
        XCTAssertEqual(store.medicationEntries(on: future).first?.schedule?.doseAmountSnapshot, "3")
    }
}

private final class TestHealthStateDatabase: HealthStateDatabase {
    var state: HealthState
    var loadFailure: Error?
    var saveFailure: Error?
    private(set) var saveCount = 0
    init(state: HealthState = HealthState()) { self.state = state }
    func load() throws -> HealthState { if let loadFailure { throw loadFailure }; return state }
    func save(_ state: HealthState) throws {
        if let saveFailure { throw saveFailure }
        saveCount += 1
        self.state = state
    }
}
