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
}
