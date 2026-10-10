import Foundation
import SwiftUI

@MainActor
final class HealthStore: ObservableObject {
    @Published private(set) var state = HealthState()
    @Published var errorMessage: String?
    @Published var noticeMessage: String?
    @Published private(set) var storageReady = false
    private var database: (any HealthStateDatabase)?

    init(
        databaseFactory: () throws -> any HealthStateDatabase = { try SecureDatabase() },
        reconcileAttachments: (HealthState) throws -> Void = {
            try AttachmentFiles.reconcileTrash(liveAttachments: $0.attachments)
            try? AttachmentFiles.reconcileRestoreDirectories(liveAttachments: $0.attachments)
        }
    ) {
        do {
            let opened = try databaseFactory()
            var loaded = try opened.load()
            guard (0...1).contains(loaded.calendarDateEncodingVersion) else { throw StoreValidationError.message("本机日期编码版本不受支持") }
            let migratedDates = loaded.calendarDateEncodingVersion == 0
            guard (1...4).contains(loaded.schemaVersion) else { throw StoreValidationError.message("本机数据版本不受支持") }
            guard loaded.records.count <= 100_000, loaded.records.allSatisfy({
                $0.dayOrder == nil || (0...PortableBackupValidator.maximumDayOrder).contains($0.dayOrder!)
            }) else { throw StoreValidationError.message("本机病历顺序异常，未修改已有资料") }
            let changed = loaded.normalizeLegacyData()
            _ = try PortableBackupMapper.export(loaded)
            if changed { try opened.save(loaded) }
            try reconcileAttachments(loaded)
            database = opened
            state = loaded
            storageReady = true
            if migratedDates {
                noticeMessage = "旧版未记录原录入时区，本次按手机当前时区（\(TimeZone.current.identifier)）解释旧日期和用药时间；原始加密资料已保留，请核对。"
            }
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
        guard storageReady else { errorMessage = "本机资料未安全加载，已禁止写入。请重新打开应用或先恢复备份。"; return false }
        guard errorMessage == nil else { return false }
        var candidate = state
        do {
            try change(&candidate)
            // Every write must satisfy the same limits/references as startup and
            // backup. Otherwise a valid-looking form could make the next launch
            // reject its own stored data and lock the user out.
            _ = try PortableBackupMapper.export(candidate)
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
        try requireStorageReady()
        return try PortableBackupService.export(state: state, password: password)
    }

    func prepareBackupImport(data: Data, password: String) throws -> PreparedPortableBackup {
        try requireStorageReady()
        return try PortableBackupService.prepareImport(data: data, password: password, local: state, targetMember: currentMember)
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
            try requireStorageReady()
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
        try requireStorageReady()
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

    private func requireStorageReady() throws {
        guard storageReady else { throw StorageError.database("本机资料未安全加载，操作已阻止") }
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
            let day = candidate.$recordDate
            let next = value.records.filter { $0.memberId == memberId && $0.$recordDate == day }.compactMap(\.dayOrder).max().map { $0 + 1 } ?? 0
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
                let day = draft.recordDate
                let nextOrder = value.records.filter { $0.memberId == memberId && $0.$recordDate == day }
                    .compactMap(\.dayOrder).max().map { $0 + 1 } ?? 0
                var record = ClinicalRecord(
                    memberId: memberId, conditionId: conditionId, recordDate: date, dayOrder: nextOrder,
                    title: draft.title.trimmingCharacters(in: .whitespacesAndNewlines), stage: draft.stage,
                    symptoms: draft.symptoms, diagnosis: draft.diagnosis, treatment: draft.treatment,
                    medicationNotes: draft.medicationNotes, hospital: draft.hospital,
                    clinician: draft.clinician, notes: draft.notes
                )
                try record.setRecordDay(day)
                value.records.append(record)
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

    @discardableResult
    func completeFollowUp(_ id: UUID, dueDay: String, memberId: UUID, skipped: Bool = false, now: Date = Date()) -> Bool {
        // The displayed cycle is immutable: a stale second tap cannot complete
        // the newly advanced (possibly future) cycle or another member's event.
        guard currentMemberId == memberId,
              state.members.contains(where: { $0.id == memberId && !$0.archived }),
              HealthDate.canonicalDay(dueDay) != nil, dueDay <= HealthDate.day(now),
              let schedule = state.followUps.first(where: { $0.id == id && $0.memberId == memberId && $0.enabled }),
              schedule.$nextDueDate == dueDay,
              !state.occurrences.contains(where: { $0.scheduleId == id && $0.$dueDate == dueDay && $0.status != "PENDING" }) else { return false }
        return mutate { value in
            guard let index = value.followUps.firstIndex(where: { $0.id == id && $0.memberId == memberId && $0.enabled }),
                  value.followUps[index].$nextDueDate == dueDay else { throw StoreValidationError.message("复查事项已变化，请重新核对") }
            let schedule = value.followUps[index]
            if let existing = value.occurrences.firstIndex(where: { $0.scheduleId == id && $0.$dueDate == dueDay }) {
                guard value.occurrences[existing].status == "PENDING" else { throw StoreValidationError.message("这次复查已经处理") }
                value.occurrences[existing].status = skipped ? "SKIPPED" : "DONE"
                value.occurrences[existing].completedAt = now
                value.occurrences[existing].updatedAt = now
            } else {
                var occurrence = FollowUpOccurrence(scheduleId: id, dueDate: schedule.nextDueDate, status: skipped ? "SKIPPED" : "DONE", completedAt: now, createdAt: now, updatedAt: now)
                try occurrence.setDueDay(dueDay)
                value.occurrences.append(occurrence)
            }
            if let next = HealthDate.nextOccurrenceDay(for: schedule) { try value.followUps[index].setDueDays(anchor: schedule.$anchorDate, next: next) }
            else { value.followUps[index].enabled = false }
            value.followUps[index].updatedAt = now
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
        if let end = medication.$endDate, end < medication.$startDate {
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
                        effectiveFrom: item.$startDate,
                        effectiveTo: item.$endDate,
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
        guard effectiveDay >= medication.$startDate else { errorMessage = "生效日期不能早于疗程开始日期"; return false }
        guard medication.mode == "AS_NEEDED" || !normalized.isEmpty else { errorMessage = "计划用药至少需要一个时间"; return false }
        if state.medicationLogs.contains(where: { $0.medicationId == medication.id && String($0.$scheduledAt.prefix(10)) >= effectiveDay }) {
            errorMessage = "该生效日期或之后已有用药记录，不能覆盖历史计划，请选择更晚的生效日期"
            return false
        }
        return mutate { value in
            guard let index = value.medications.firstIndex(where: { $0.id == medication.id }) else { return }
            let prior = HealthDate.addingDays(-1, to: effectiveDay)
            // Re-editing a future version replaces only unrecorded future plans.
            // Referenced historical versions are never deleted (guard above).
            value.medicationSchedules.removeAll { $0.medicationId == medication.id && $0.effectiveFrom >= effectiveDay }
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
                        effectiveTo: updated.$endDate,
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
                    $0.medicationId == medication.id && $0.scheduleId == schedule.id && String($0.$scheduledAt.prefix(16)) == "\(day)T\(schedule.localTime)"
                }
                result.append(MedicationDayEntry(medication: medication, schedule: schedule, log: log, plannedAt: planned, actualAt: log?.actualAt, status: log?.status ?? "UNRECORDED"))
            }
            let scheduledIds = Set(schedules.map(\.id))
            let extraLogs = state.medicationLogs.filter {
                $0.medicationId == medication.id && String($0.$scheduledAt.prefix(10)) == day
                    && ($0.scheduleId == nil || !scheduledIds.contains($0.scheduleId!))
            }
            extraLogs.forEach { log in result.append(MedicationDayEntry(medication: medication, schedule: nil, log: log, plannedAt: log.scheduledAt, actualAt: log.actualAt, status: log.status)) }
        }
        return result.sorted { ($0.plannedAt ?? $0.actualAt ?? .distantPast) < ($1.plannedAt ?? $1.actualAt ?? .distantPast) }
    }

    func recordDose(medication: Medication, schedule: MedicationSchedule?, scheduledAt: Date, actualAt: Date?, status: String, now: Date = Date(), actualLocalTime: String? = nil) -> Bool {
        guard status == "TAKEN" || status == "SKIPPED" else { return false }
        let day = HealthDate.day(scheduledAt)
        guard storageReady, let memberId = currentMemberId,
              state.members.contains(where: { $0.id == memberId && !$0.archived }),
              let freshMedication = state.medications.first(where: { $0.id == medication.id && $0.memberId == memberId && !$0.archived }),
              freshMedication == medication,
              day <= HealthDate.day(now), HealthDate.isWithinCourse(scheduledAt, medication: freshMedication) else { return false }
        let freshSchedule: MedicationSchedule?
        if let schedule {
            guard let fresh = state.medicationSchedules.first(where: { $0.id == schedule.id && $0.medicationId == medication.id }),
                  fresh == schedule, fresh.enabled, fresh.effectiveFrom <= day,
                  fresh.effectiveTo == nil || fresh.effectiveTo! >= day else { return false }
            freshSchedule = fresh
        } else {
            guard freshMedication.mode == "AS_NEEDED" else { return false }
            freshSchedule = nil
        }
        let takenAt = status == "TAKEN" ? actualAt ?? now : nil
        let takenClock = status == "TAKEN" ? actualLocalTime ?? takenAt.map(HealthDate.localDateTime) : nil
        guard takenClock == nil || (HealthDate.canonicalLocalDateTime(takenClock!) != nil && String(takenClock!.prefix(10)) == day
                                   && HealthDate.canonicalLocalDateTime(takenClock!)! <= HealthDate.localDateTime(now)) else { return false }
        let slot = schedule.map { "\(day)T\($0.localTime):00" } ?? takenClock ?? HealthDate.localDateTime(scheduledAt)
        return mutate { value in
            guard !value.medicationLogs.contains(where: { $0.medicationId == medication.id && $0.$scheduledAt.prefix(16) == slot.prefix(16) }) else {
                throw StoreValidationError.message("这次用药已经记录")
            }
            var log = MedicationLog(
                medicationId: medication.id,
                scheduleId: freshSchedule?.id,
                scheduledAt: scheduledAt,
                actualAt: takenAt,
                status: status,
                doseAmountSnapshot: freshSchedule?.doseAmountSnapshot ?? freshMedication.doseAmount,
                doseUnitSnapshot: freshSchedule?.doseUnitSnapshot ?? freshMedication.doseUnit
            )
            try log.setLocalTimes(scheduled: slot, actual: takenClock)
            value.medicationLogs.append(log)
        }
    }

    func correctDose(logId: UUID, status: String, actualAt: Date?, actualLocalTime: String? = nil, now: Date = Date()) -> Bool {
        guard status == "TAKEN" || status == "SKIPPED", let memberId = currentMemberId,
              state.members.contains(where: { $0.id == memberId && !$0.archived }),
              let log = state.medicationLogs.first(where: { $0.id == logId }),
              state.medications.contains(where: { $0.id == log.medicationId && $0.memberId == memberId }) else { return false }
        let takenAt = status == "TAKEN" ? actualAt ?? now : nil
        let takenClock = status == "TAKEN" ? actualLocalTime ?? takenAt.map(HealthDate.localDateTime) : nil
        guard takenClock == nil || (HealthDate.canonicalLocalDateTime(takenClock!) != nil && takenClock!.prefix(10) == log.$scheduledAt.prefix(10)
                                   && HealthDate.canonicalLocalDateTime(takenClock!)! <= HealthDate.localDateTime(now)) else { return false }
        return mutate { value in
            guard let index = value.medicationLogs.firstIndex(where: { $0.id == logId }) else { return }
            value.medicationLogs[index].status = status
            value.medicationLogs[index].actualAt = takenAt
            try value.medicationLogs[index].setLocalTimes(scheduled: log.$scheduledAt, actual: takenClock)
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
