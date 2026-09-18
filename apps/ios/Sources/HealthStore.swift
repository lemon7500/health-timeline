import Foundation
import SwiftUI

@MainActor
final class HealthStore: ObservableObject {
    @Published private(set) var state = HealthState()
    @Published var errorMessage: String?
    @Published var noticeMessage: String?
    private var database: SecureDatabase?

    init() {
        do {
            database = try SecureDatabase()
            var loaded = try database?.load() ?? HealthState()
            if loaded.normalizeLegacyData() { try database?.save(loaded) }
            state = loaded
            try AttachmentFiles.reconcileTrash(liveAttachments: loaded.attachments)
            try? AttachmentFiles.reconcileRestoreDirectories(liveAttachments: loaded.attachments)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    var activeMembers: [FamilyMember] { state.members.filter { !$0.archived } }
    var currentMember: FamilyMember? {
        state.selectedMemberId.flatMap { selected in state.members.first { $0.id == selected } }
            ?? activeMembers.first
    }
    var currentMemberId: UUID? { currentMember?.id }

    @discardableResult
    func mutate(_ change: (inout HealthState) throws -> Void) -> Bool {
        guard errorMessage == nil else { return false }
        var candidate = state
        do {
            try change(&candidate)
            guard let database else { throw StorageError.database("数据库未打开") }
            try database.save(candidate)
            state = candidate
            Task { await LocalReminderScheduler.rebuild(candidate) }
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func clearError() { errorMessage = nil }

    func exportBackup(password: String) throws -> Data {
        try PortableBackupService.export(state: state, password: password)
    }

    func prepareBackupImport(data: Data, password: String) throws -> PreparedPortableBackup {
        try PortableBackupService.prepareImport(data: data, password: password, local: state, targetMember: currentMember)
    }

    func mergeBackup(_ prepared: PreparedPortableBackup, useImported: Set<String> = []) -> Bool {
        do {
            let merged = try PortableBackupService.merge(local: state, imported: prepared.importedState, useImported: useImported)
            let localAttachmentIds = Set(state.attachments.map(\.id))
            let incomingAttachmentIds = Set(merged.attachments.compactMap { attachment in
                !localAttachmentIds.contains(attachment.id) || useImported.contains("attachment:\(attachment.id.uuidString)") ? attachment.id : nil
            })
            try applyPreparedBackup(prepared, candidate: merged, incomingAttachmentIds: incomingAttachmentIds)
            noticeMessage = "导入完成：新增 \(prepared.preview.additions) 项，采用导入版本 \(useImported.count) 项。"
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func replaceBackup(_ prepared: PreparedPortableBackup, password: String) -> Bool {
        do {
            guard password.count >= 8 else { throw StoreValidationError.message("备份密码至少需要 8 位") }
            _ = try BackupSafetyStore.create(state: state, password: password)
            var replacement = prepared.importedState
            replacement.installationId = state.installationId
            if let selected = state.selectedMemberId,
               replacement.members.contains(where: { $0.id == selected && !$0.archived }) {
                replacement.selectedMemberId = selected
            } else {
                replacement.selectedMemberId = replacement.members.first(where: { !$0.archived })?.id
            }
            _ = replacement.normalizeLegacyData()
            try applyPreparedBackup(
                prepared,
                candidate: replacement,
                incomingAttachmentIds: Set(replacement.attachments.map(\.id))
            )
            noticeMessage = "整体恢复完成；替换前的加密安全备份已保存在应用受保护目录。"
            return true
        } catch {
            errorMessage = "整体恢复未执行：\(error.localizedDescription)"
            return false
        }
    }

    private func applyPreparedBackup(
        _ prepared: PreparedPortableBackup,
        candidate source: HealthState,
        incomingAttachmentIds: Set<UUID>
    ) throws {
        guard let database else { throw StorageError.database("数据库未打开") }
        let previous = state
        let batchId = UUID()
        var candidate = source
        var installed: [Attachment] = []
        for index in candidate.attachments.indices where incomingAttachmentIds.contains(candidate.attachments[index].id) {
            let original = candidate.attachments[index]
            guard let bytes = prepared.attachmentBytes[original.id] else {
                throw StoreValidationError.message("附件临时数据缺失：\(original.displayName)")
            }
            candidate.attachments[index].relativePath = AttachmentFiles.restoredPath(
                recordId: original.recordId,
                attachmentId: original.id,
                batchId: batchId
            )
        }
        _ = candidate.normalizeLegacyData()
        _ = try PortableBackupMapper.export(candidate)
        do {
            for attachment in candidate.attachments where incomingAttachmentIds.contains(attachment.id) {
                guard let bytes = prepared.attachmentBytes[attachment.id] else {
                    throw StoreValidationError.message("附件临时数据缺失：\(attachment.displayName)")
                }
                try AttachmentFiles.installVerified(bytes: bytes, attachment: attachment)
                installed.append(attachment)
            }
            try database.save(candidate)
            state = candidate
            let livePaths = Set(candidate.attachments.map(\.relativePath))
            previous.attachments.filter { !livePaths.contains($0.relativePath) }.forEach {
                try? AttachmentFiles.deleteImmediately($0)
            }
            Task { await LocalReminderScheduler.rebuild(candidate) }
        } catch {
            installed.forEach { try? AttachmentFiles.deleteImmediately($0) }
            throw error
        }
    }

    func selectMember(_ id: UUID) {
        guard state.members.contains(where: { $0.id == id && !$0.archived }) else { return }
        mutate { $0.selectedMemberId = id }
    }

    func saveMember(id: UUID? = nil, name: String, relationship: String) -> Bool {
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanRelationship = relationship.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanName.isEmpty, cleanName.count <= 50, !cleanRelationship.isEmpty, cleanRelationship.count <= 30 else {
            errorMessage = "姓名或关系为空，或长度超过限制"
            return false
        }
        return mutate { value in
            if let id, let index = value.members.firstIndex(where: { $0.id == id }) {
                value.members[index].name = cleanName
                value.members[index].relationship = cleanRelationship
                value.members[index].updatedAt = Date()
            } else {
                let member = FamilyMember(name: cleanName, relationship: cleanRelationship)
                value.members.append(member)
                value.selectedMemberId = member.id
            }
        }
    }

    func setMemberArchived(_ id: UUID, archived: Bool) -> Bool {
        mutate { value in
            guard let index = value.members.firstIndex(where: { $0.id == id }) else { return }
            if archived {
                guard value.members.filter({ !$0.archived }).count > 1 else { throw StoreValidationError.message("最后一个有效成员不能归档") }
            }
            value.members[index].archived = archived
            value.members[index].updatedAt = Date()
            if value.selectedMemberId == id, archived { value.selectedMemberId = value.members.first(where: { !$0.archived })?.id }
        }
    }

    func deleteEmptyMember(_ id: UUID) -> Bool {
        mutate { value in
            guard value.members.count > 1 else { throw StoreValidationError.message("最后一个成员不能删除") }
            let referenced = value.conditions.contains { $0.memberId == id }
                || value.records.contains { $0.memberId == id }
                || value.followUps.contains { $0.memberId == id }
                || value.medications.contains { $0.memberId == id }
            guard !referenced else { throw StoreValidationError.message("该成员已有历史资料，只能归档") }
            value.members.removeAll { $0.id == id }
            if value.selectedMemberId == id { value.selectedMemberId = value.members.first(where: { !$0.archived })?.id }
        }
    }

    func conditionsForCurrentMember(includeArchived: Bool = false) -> [Condition] {
        guard let memberId = currentMemberId else { return [] }
        return state.conditions.filter { $0.memberId == memberId && (includeArchived || !$0.archived) }
    }

    func recordsForCurrentMember() -> [ClinicalRecord] {
        guard let memberId = currentMemberId else { return [] }
        return state.records.filter { $0.memberId == memberId }
    }

    func addRecord(_ record: ClinicalRecord, conditionName: String? = nil) -> Bool {
        guard let memberId = record.memberId ?? currentMemberId,
              state.members.contains(where: { $0.id == memberId && !$0.archived }) else { return false }
        let cleanTitle = record.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTitle.isEmpty, cleanTitle.count <= 100 else { errorMessage = "标题不能为空且不能超过 100 个字符"; return false }
        return mutate { value in
            var candidate = record
            candidate.memberId = memberId
            candidate.title = cleanTitle
            candidate.updatedAt = Date()
            if let conditionName = conditionName?.trimmingCharacters(in: .whitespacesAndNewlines), !conditionName.isEmpty {
                if let existing = value.conditions.first(where: { $0.memberId == memberId && $0.name.compare(conditionName, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame }) {
                    candidate.conditionId = existing.id
                } else {
                    let condition = Condition(memberId: memberId, name: conditionName)
                    value.conditions.append(condition)
                    candidate.conditionId = condition.id
                }
            }
            let day = HealthDate.day(candidate.recordDate)
            let next = value.records.filter { $0.memberId == memberId && HealthDate.day($0.recordDate) == day }.compactMap(\.dayOrder).max().map { $0 + 1 } ?? 0
            candidate.dayOrder = next
            value.records.append(candidate)
        }
    }

    func addQuickRecords(_ drafts: [QuickClinicalRecordDraft], memberId: UUID?) -> Bool {
        guard !drafts.isEmpty, let memberId,
              state.members.contains(where: { $0.id == memberId && !$0.archived }) else {
            errorMessage = "快速录入所属成员无效"
            return false
        }
        let validation = drafts.enumerated().flatMap { StructuredQuickEntryParser.validate($0.element, index: $0.offset + 1) }
        guard validation.isEmpty else { errorMessage = validation.joined(separator: "\n"); return false }
        return mutate { value in
            for draft in drafts {
                var conditionId: UUID?
                let conditionName = draft.conditionName.trimmingCharacters(in: .whitespacesAndNewlines)
                if !conditionName.isEmpty {
                    if let index = value.conditions.firstIndex(where: {
                        $0.memberId == memberId && $0.name.compare(conditionName, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
                    }) {
                        value.conditions[index].archived = false
                        value.conditions[index].updatedAt = Date()
                        conditionId = value.conditions[index].id
                    } else {
                        let condition = Condition(memberId: memberId, name: conditionName)
                        value.conditions.append(condition); conditionId = condition.id
                    }
                }
                guard let date = HealthDate.parseDay(draft.recordDate) else { throw StoreValidationError.message("快速录入日期无效") }
                let day = HealthDate.day(date)
                let nextOrder = value.records.filter { $0.memberId == memberId && HealthDate.day($0.recordDate) == day }
                    .compactMap(\.dayOrder).max().map { $0 + 1 } ?? 0
                value.records.append(ClinicalRecord(
                    memberId: memberId, conditionId: conditionId, recordDate: date, dayOrder: nextOrder,
                    title: draft.title.trimmingCharacters(in: .whitespacesAndNewlines), stage: draft.stage,
                    symptoms: draft.symptoms, diagnosis: draft.diagnosis, treatment: draft.treatment,
                    medicationNotes: draft.medicationNotes, hospital: draft.hospital,
                    clinician: draft.clinician, notes: draft.notes
                ))
            }
        }
    }

    func updateRecord(_ record: ClinicalRecord) -> Bool {
        mutate { value in
            guard let index = value.records.firstIndex(where: { $0.id == record.id }) else { return }
            guard value.records[index].memberId == record.memberId else { throw StoreValidationError.message("不能更改病历所属成员") }
            var updated = record
            updated.updatedAt = Date()
            value.records[index] = updated
        }
    }

    func deleteRecord(_ id: UUID) {
        let obsolete = state.attachments.filter { $0.recordId == id }
        var staged: [AttachmentDeleteToken] = []
        do { staged = try obsolete.compactMap(AttachmentFiles.stageDelete) }
        catch { staged.forEach { try? AttachmentFiles.rollbackDelete($0) }; errorMessage = error.localizedDescription; return }
        if mutate({ value in
            value.records.removeAll { $0.id == id }
            value.attachments.removeAll { $0.recordId == id }
        }) {
            staged.forEach { try? AttachmentFiles.commitDelete($0) }
        } else {
            staged.forEach { try? AttachmentFiles.rollbackDelete($0) }
        }
    }

    func addAttachment(recordId: UUID, source: URL) {
        guard recordsForCurrentMember().contains(where: { $0.id == recordId }) else { errorMessage = "病历不属于当前成员"; return }
        do {
            let value = try AttachmentFiles.importFile(recordId: recordId, source: source)
            if !mutate({ $0.attachments.append(value) }) { try? AttachmentFiles.deleteImmediately(value) }
        } catch { errorMessage = error.localizedDescription }
    }

    func deleteAttachment(_ attachment: Attachment) {
        guard state.attachments.contains(where: { $0.id == attachment.id && $0.relativePath == attachment.relativePath }) else {
            noticeMessage = "附件已经删除"
            return
        }
        do {
            let token = try AttachmentFiles.stageDelete(attachment)
            let saved = mutate { $0.attachments.removeAll { $0.id == attachment.id } }
            if saved {
                if let token { try AttachmentFiles.commitDelete(token) }
                noticeMessage = token == nil ? "原文件已不存在，附件记录已清除" : "附件已删除"
            } else if let token { try? AttachmentFiles.rollbackDelete(token) }
        } catch { errorMessage = error.localizedDescription }
    }

    func addFollowUp(_ value: FollowUpSchedule) -> Bool {
        guard let memberId = value.memberId ?? currentMemberId,
              state.members.contains(where: { $0.id == memberId && !$0.archived }) else { return false }
        return mutate { state in var item = value; item.memberId = memberId; item.updatedAt = Date(); state.followUps.append(item) }
    }

    func completeFollowUp(_ id: UUID, skipped: Bool = false) {
        mutate { value in
            guard let index = value.followUps.firstIndex(where: { $0.id == id }) else { return }
            let schedule = value.followUps[index]
            guard !value.occurrences.contains(where: { $0.scheduleId == id && HealthDate.calendar.isDate($0.dueDate, inSameDayAs: schedule.nextDueDate) }) else { return }
            value.occurrences.append(FollowUpOccurrence(scheduleId: id, dueDate: schedule.nextDueDate, status: skipped ? "SKIPPED" : "DONE", completedAt: Date()))
            if let next = HealthDate.nextOccurrence(for: schedule) { value.followUps[index].nextDueDate = next }
            else { value.followUps[index].enabled = false }
            value.followUps[index].updatedAt = Date()
        }
    }

    func medicationsForCurrentMember(includeArchived: Bool = false) -> [Medication] {
        guard let memberId = currentMemberId else { return [] }
        return state.medications.filter { $0.memberId == memberId && (includeArchived || !$0.archived) }
    }

    func addMedication(_ medication: Medication, times: [String]) -> Bool {
        guard let memberId = medication.memberId ?? currentMemberId,
              state.members.contains(where: { $0.id == memberId && !$0.archived }) else { return false }
        let cleanName = medication.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanAmount = medication.doseAmount.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanUnit = medication.doseUnit.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanName.isEmpty, cleanName.count <= 100, !cleanAmount.isEmpty, cleanAmount.count <= 30,
              !cleanUnit.isEmpty, cleanUnit.count <= 20 else { errorMessage = "药名、剂量或单位为空，或长度超过限制"; return false }
        if let end = medication.endDate, end < HealthDate.calendar.startOfDay(for: medication.startDate) {
            errorMessage = "结束日期不能早于开始日期"; return false
        }
        let normalized = Array(Set(times.filter(Self.validTime))).sorted()
        guard medication.mode == "AS_NEEDED" || !normalized.isEmpty else { errorMessage = "计划用药至少需要一个时间"; return false }
        return mutate { value in
            var item = medication
            item.memberId = memberId
            item.name = cleanName
            item.doseAmount = cleanAmount
            item.doseUnit = cleanUnit
            item.times = []
            item.updatedAt = Date()
            value.medications.append(item)
            if item.mode == "SCHEDULED" {
                normalized.forEach { time in
                    value.medicationSchedules.append(MedicationSchedule(
                        medicationId: item.id,
                        localTime: time,
                        effectiveFrom: HealthDate.day(item.startDate),
                        effectiveTo: item.endDate.map(HealthDate.day),
                        doseAmountSnapshot: item.doseAmount,
                        doseUnitSnapshot: item.doseUnit
                    ))
                }
            }
        }
    }

    func updateMedication(_ medication: Medication, times: [String], effectiveDate: Date) -> Bool {
        let normalized = Array(Set(times.filter(Self.validTime))).sorted()
        let effectiveDay = HealthDate.day(effectiveDate)
        guard state.medications.contains(where: { $0.id == medication.id && $0.memberId == medication.memberId }) else { errorMessage = "药物不存在或成员不匹配"; return false }
        guard !medication.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !medication.doseAmount.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !medication.doseUnit.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { errorMessage = "请填写药名、剂量和单位"; return false }
        guard effectiveDate >= HealthDate.calendar.startOfDay(for: medication.startDate) else { errorMessage = "生效日期不能早于疗程开始日期"; return false }
        guard medication.mode == "AS_NEEDED" || !normalized.isEmpty else { errorMessage = "计划用药至少需要一个时间"; return false }
        if state.medicationLogs.contains(where: { $0.medicationId == medication.id && HealthDate.day($0.scheduledAt) == effectiveDay }) {
            errorMessage = "该生效日期已有用药记录，请改为次日生效"
            return false
        }
        return mutate { value in
            guard let index = value.medications.firstIndex(where: { $0.id == medication.id }) else { return }
            let prior = HealthDate.calendar.date(byAdding: .day, value: -1, to: effectiveDate).map(HealthDate.day)
            for scheduleIndex in value.medicationSchedules.indices where value.medicationSchedules[scheduleIndex].medicationId == medication.id
                && value.medicationSchedules[scheduleIndex].effectiveFrom < effectiveDay
                && (value.medicationSchedules[scheduleIndex].effectiveTo == nil || value.medicationSchedules[scheduleIndex].effectiveTo! >= effectiveDay) {
                value.medicationSchedules[scheduleIndex].effectiveTo = prior
                value.medicationSchedules[scheduleIndex].updatedAt = Date()
            }
            var updated = medication
            updated.updatedAt = Date()
            updated.times = []
            value.medications[index] = updated
            if updated.mode == "SCHEDULED" {
                normalized.forEach { time in
                    value.medicationSchedules.append(MedicationSchedule(
                        medicationId: updated.id,
                        localTime: time,
                        effectiveFrom: effectiveDay,
                        effectiveTo: updated.endDate.map(HealthDate.day),
                        doseAmountSnapshot: updated.doseAmount,
                        doseUnitSnapshot: updated.doseUnit
                    ))
                }
            }
        }
    }

    func medicationEntries(on date: Date) -> [MedicationDayEntry] {
        let day = HealthDate.day(date)
        var result: [MedicationDayEntry] = []
        for medication in medicationsForCurrentMember(includeArchived: true) where HealthDate.isWithinCourse(date, medication: medication) {
            let schedules = state.medicationSchedules.filter {
                $0.medicationId == medication.id && $0.enabled && $0.effectiveFrom <= day && ($0.effectiveTo == nil || $0.effectiveTo! >= day)
            }
            for schedule in schedules {
                guard let planned = HealthDate.at(date, time: schedule.localTime) else { continue }
                let log = state.medicationLogs.first {
                    $0.medicationId == medication.id && $0.scheduleId == schedule.id && HealthDate.calendar.isDate($0.scheduledAt, equalTo: planned, toGranularity: .minute)
                }
                result.append(MedicationDayEntry(medication: medication, schedule: schedule, log: log, plannedAt: planned, actualAt: log?.actualAt, status: log?.status ?? "UNRECORDED"))
            }
            let scheduledIds = Set(schedules.map(\.id))
            let extraLogs = state.medicationLogs.filter {
                $0.medicationId == medication.id && HealthDate.calendar.isDate($0.scheduledAt, inSameDayAs: date)
                    && ($0.scheduleId == nil || !scheduledIds.contains($0.scheduleId!))
            }
            extraLogs.forEach { log in result.append(MedicationDayEntry(medication: medication, schedule: nil, log: log, plannedAt: log.scheduledAt, actualAt: log.actualAt, status: log.status)) }
        }
        return result.sorted { ($0.plannedAt ?? $0.actualAt ?? .distantPast) < ($1.plannedAt ?? $1.actualAt ?? .distantPast) }
    }

    func recordDose(medication: Medication, schedule: MedicationSchedule?, scheduledAt: Date, actualAt: Date?, status: String) -> Bool {
        guard status == "TAKEN" || status == "SKIPPED" else { return false }
        return mutate { value in
            guard !value.medicationLogs.contains(where: { $0.medicationId == medication.id && HealthDate.calendar.isDate($0.scheduledAt, equalTo: scheduledAt, toGranularity: .minute) }) else {
                throw StoreValidationError.message("这次用药已经记录")
            }
            value.medicationLogs.append(MedicationLog(
                medicationId: medication.id,
                scheduleId: schedule?.id,
                scheduledAt: scheduledAt,
                actualAt: status == "TAKEN" ? actualAt ?? Date() : nil,
                status: status,
                doseAmountSnapshot: schedule?.doseAmountSnapshot ?? medication.doseAmount,
                doseUnitSnapshot: schedule?.doseUnitSnapshot ?? medication.doseUnit
            ))
        }
    }

    func correctDose(logId: UUID, status: String, actualAt: Date?) -> Bool {
        mutate { value in
            guard let index = value.medicationLogs.firstIndex(where: { $0.id == logId }) else { return }
            value.medicationLogs[index].status = status
            value.medicationLogs[index].actualAt = status == "TAKEN" ? actualAt ?? Date() : nil
            value.medicationLogs[index].updatedAt = Date()
        }
    }

    private static func validTime(_ value: String) -> Bool {
        value.range(of: #"^(?:[01]\d|2[0-3]):[0-5]\d$"#, options: .regularExpression) != nil
    }
}

enum StoreValidationError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let value) = self { return value }; return nil }
}
