import Foundation

struct PortableSnapshot: Codable {
    var schemaVersion: Int
    var exportedAt: String
    var sourcePlatform: String
    var sourceInstallationId: UUID
    var members: [PortableMember]
    var conditions: [PortableCondition]
    var records: [PortableRecord]
    var attachments: [PortableAttachment]
    var followUps: [PortableFollowUp]
    var occurrences: [PortableOccurrence]
    var medications: [PortableMedication]
    var medicationSchedules: [PortableMedicationSchedule]
    var medicationLogs: [PortableMedicationLog]

    private enum CodingKeys: String, CodingKey {
        case schemaVersion, exportedAt, sourcePlatform, sourceInstallationId, members, conditions, records
        case attachments, followUps, occurrences, medications, medicationSchedules, medicationLogs
    }

    init(schemaVersion: Int = 4, exportedAt: String, sourcePlatform: String, sourceInstallationId: UUID,
         members: [PortableMember] = [], conditions: [PortableCondition] = [], records: [PortableRecord] = [],
         attachments: [PortableAttachment] = [], followUps: [PortableFollowUp] = [], occurrences: [PortableOccurrence] = [],
         medications: [PortableMedication] = [], medicationSchedules: [PortableMedicationSchedule] = [], medicationLogs: [PortableMedicationLog] = []) {
        self.schemaVersion = schemaVersion; self.exportedAt = exportedAt; self.sourcePlatform = sourcePlatform
        self.sourceInstallationId = sourceInstallationId; self.members = members; self.conditions = conditions
        self.records = records; self.attachments = attachments; self.followUps = followUps; self.occurrences = occurrences
        self.medications = medications; self.medicationSchedules = medicationSchedules; self.medicationLogs = medicationLogs
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try c.decode(Int.self, forKey: .schemaVersion)
        exportedAt = try c.decode(String.self, forKey: .exportedAt)
        sourcePlatform = try c.decode(String.self, forKey: .sourcePlatform)
        sourceInstallationId = try c.decode(UUID.self, forKey: .sourceInstallationId)
        members = try c.decodeIfPresent([PortableMember].self, forKey: .members) ?? []
        conditions = try c.decodeIfPresent([PortableCondition].self, forKey: .conditions) ?? []
        records = try c.decodeIfPresent([PortableRecord].self, forKey: .records) ?? []
        attachments = try c.decodeIfPresent([PortableAttachment].self, forKey: .attachments) ?? []
        followUps = try c.decodeIfPresent([PortableFollowUp].self, forKey: .followUps) ?? []
        occurrences = try c.decodeIfPresent([PortableOccurrence].self, forKey: .occurrences) ?? []
        medications = try c.decodeIfPresent([PortableMedication].self, forKey: .medications) ?? []
        medicationSchedules = try c.decodeIfPresent([PortableMedicationSchedule].self, forKey: .medicationSchedules) ?? []
        medicationLogs = try c.decodeIfPresent([PortableMedicationLog].self, forKey: .medicationLogs) ?? []
    }
}

struct PortableMember: Codable { var uuid: UUID; var name: String; var nickname: String; var relationship: String; var archived: Bool; var createdAt: String; var updatedAt: String }
struct PortableCondition: Codable { var uuid: UUID; var name: String; var color: UInt64; var notes: String; var archived: Bool; var createdAt: String; var updatedAt: String?; var memberUuid: UUID? }
struct PortableRecord: Codable { var uuid: UUID; var conditionUuid: UUID?; var recordDate: String; var title: String; var stage: String; var symptoms: String; var diagnosis: String; var treatment: String; var medicationNotes: String; var hospital: String; var clinician: String; var notes: String; var createdAt: String; var updatedAt: String; var memberUuid: UUID?; var dayOrder: Int64? }
struct PortableAttachment: Codable { var uuid: UUID; var recordUuid: UUID; var kind: String; var displayName: String; var mimeType: String; var archivePath: String; var sizeBytes: Int64; var sha256: String; var createdAt: String; var updatedAt: String? }
struct PortableFollowUp: Codable { var uuid: UUID; var conditionUuid: UUID?; var title: String; var recurrenceType: String; var interval: Int; var anchorDate: String; var anchorDayOfMonth: Int; var weekday: Int?; var reminderTime: String; var leadDays: Int; var nextDueDate: String; var enabled: Bool; var createdAt: String; var updatedAt: String; var memberUuid: UUID? }
struct PortableOccurrence: Codable { var uuid: UUID; var scheduleUuid: UUID; var dueDate: String; var status: String; var completedAt: String?; var createdAt: String; var updatedAt: String? }
struct PortableMedication: Codable { var uuid: UUID; var conditionUuid: UUID?; var name: String; var doseAmount: String; var doseUnit: String; var instructions: String; var startDate: String; var endDate: String?; var mode: String; var archived: Bool; var createdAt: String; var updatedAt: String; var memberUuid: UUID?; var endedAt: String?; var archivedPreviousEndDate: String? }
struct PortableMedicationSchedule: Codable { var uuid: UUID; var medicationUuid: UUID; var localTime: String; var enabled: Bool; var updatedAt: String; var effectiveFrom: String?; var effectiveTo: String?; var doseAmountSnapshot: String?; var doseUnitSnapshot: String?; var pausedByCourseEnd: Bool? }
struct PortableMedicationLog: Codable { var uuid: UUID; var medicationUuid: UUID; var scheduleUuid: UUID?; var scheduledAt: String; var actualAt: String?; var status: String; var doseAmountSnapshot: String; var doseUnitSnapshot: String; var createdAt: String; var updatedAt: String? }

struct BackupPreview {
    var additions = 0
    var updates = 0
    var duplicates = 0
    var conflicts: [BackupConflict] = []
}

struct BackupConflict: Identifiable, Hashable {
    let entity: String
    let uuid: UUID
    let localUpdatedAt: String
    let importedUpdatedAt: String
    var id: String { "\(entity):\(uuid.uuidString)" }
    var key: String { id }
}

enum PortableBackupMapper {
    static func export(_ state: HealthState) throws -> PortableSnapshot {
        let snapshot = PortableSnapshot(
            exportedAt: HealthDate.instant(Date()), sourcePlatform: "ios", sourceInstallationId: state.installationId,
            members: state.members.map { PortableMember(uuid: $0.id, name: $0.name, nickname: $0.relationship, relationship: $0.relationship, archived: $0.archived, createdAt: HealthDate.instant($0.createdAt), updatedAt: HealthDate.instant($0.updatedAt)) },
            conditions: state.conditions.map { PortableCondition(uuid: $0.id, name: $0.name, color: $0.color, notes: $0.notes, archived: $0.archived, createdAt: HealthDate.instant($0.createdAt), updatedAt: HealthDate.instant($0.updatedAt), memberUuid: $0.memberId) },
            records: state.records.sorted(by: recordOrder).map { PortableRecord(uuid: $0.id, conditionUuid: $0.conditionId, recordDate: HealthDate.day($0.recordDate), title: $0.title, stage: $0.stage, symptoms: $0.symptoms, diagnosis: $0.diagnosis, treatment: $0.treatment, medicationNotes: $0.medicationNotes, hospital: $0.hospital, clinician: $0.clinician, notes: $0.notes, createdAt: HealthDate.instant($0.createdAt), updatedAt: HealthDate.instant($0.updatedAt), memberUuid: $0.memberId, dayOrder: $0.dayOrder ?? 0) },
            attachments: state.attachments.map { PortableAttachment(uuid: $0.id, recordUuid: $0.recordId, kind: $0.kind, displayName: $0.displayName, mimeType: $0.mimeType, archivePath: "files/\($0.id.uuidString.lowercased())", sizeBytes: $0.sizeBytes, sha256: $0.sha256, createdAt: HealthDate.instant($0.createdAt), updatedAt: HealthDate.instant($0.updatedAt)) },
            followUps: state.followUps.map { PortableFollowUp(uuid: $0.id, conditionUuid: $0.conditionId, title: $0.title, recurrenceType: $0.recurrenceType, interval: $0.interval, anchorDate: HealthDate.day($0.anchorDate), anchorDayOfMonth: $0.anchorDayOfMonth, weekday: $0.weekday, reminderTime: String(format: "%02d:%02d", $0.reminderHour, $0.reminderMinute), leadDays: $0.leadDays, nextDueDate: HealthDate.day($0.nextDueDate), enabled: $0.enabled, createdAt: HealthDate.instant($0.createdAt), updatedAt: HealthDate.instant($0.updatedAt), memberUuid: $0.memberId) },
            occurrences: state.occurrences.map { PortableOccurrence(uuid: $0.id, scheduleUuid: $0.scheduleId, dueDate: HealthDate.day($0.dueDate), status: $0.status, completedAt: $0.completedAt.map(HealthDate.instant), createdAt: HealthDate.instant($0.createdAt), updatedAt: HealthDate.instant($0.updatedAt)) },
            medications: state.medications.map { PortableMedication(uuid: $0.id, conditionUuid: $0.conditionId, name: $0.name, doseAmount: $0.doseAmount, doseUnit: $0.doseUnit, instructions: $0.instructions, startDate: HealthDate.day($0.startDate), endDate: $0.endDate.map(HealthDate.day), mode: $0.mode, archived: $0.archived, createdAt: HealthDate.instant($0.createdAt), updatedAt: HealthDate.instant($0.updatedAt), memberUuid: $0.memberId, endedAt: $0.endedAt.map(HealthDate.instant), archivedPreviousEndDate: $0.archivedPreviousEndDate.map(HealthDate.day)) },
            medicationSchedules: state.medicationSchedules.map { PortableMedicationSchedule(uuid: $0.id, medicationUuid: $0.medicationId, localTime: $0.localTime, enabled: $0.enabled, updatedAt: HealthDate.instant($0.updatedAt), effectiveFrom: $0.effectiveFrom, effectiveTo: $0.effectiveTo, doseAmountSnapshot: $0.doseAmountSnapshot, doseUnitSnapshot: $0.doseUnitSnapshot, pausedByCourseEnd: $0.pausedByCourseEnd) },
            medicationLogs: state.medicationLogs.map { PortableMedicationLog(uuid: $0.id, medicationUuid: $0.medicationId, scheduleUuid: $0.scheduleId, scheduledAt: HealthDate.localDateTime($0.scheduledAt), actualAt: $0.actualAt.map(HealthDate.localDateTime), status: $0.status, doseAmountSnapshot: $0.doseAmountSnapshot, doseUnitSnapshot: $0.doseUnitSnapshot, createdAt: HealthDate.instant($0.createdAt), updatedAt: HealthDate.instant($0.updatedAt)) }
        )
        try PortableBackupValidator.validate(snapshot)
        return snapshot
    }

    static func normalized(_ source: PortableSnapshot, targetMember: FamilyMember?) throws -> PortableSnapshot {
        guard [2, 3, 4].contains(source.schemaVersion) else { throw StoreValidationError.message("不支持的备份数据版本") }
        if source.schemaVersion == 4 { try PortableBackupValidator.validate(source); return source }
        var value = source
        if value.schemaVersion == 2 {
            let member = targetMember ?? FamilyMember()
            let portable = PortableMember(uuid: member.id, name: member.name, nickname: member.relationship, relationship: member.relationship, archived: member.archived, createdAt: HealthDate.instant(member.createdAt), updatedAt: HealthDate.instant(member.updatedAt))
            value.members = [portable]
            value.conditions = value.conditions.map { var item = $0; item.memberUuid = member.id; return item }
            value.records = value.records.map { var item = $0; item.memberUuid = member.id; return item }
            value.followUps = value.followUps.map { var item = $0; item.memberUuid = member.id; return item }
            value.medications = value.medications.map { var item = $0; item.memberUuid = member.id; return item }
        } else {
            value.members = value.members.map { var item = $0; item.nickname = item.relationship; return item }
        }
        var orders: [String: Int64] = [:]
        value.records = value.records.map { record in
            var item = record
            let key = "\(record.memberUuid?.uuidString ?? ""):\(record.recordDate)"
            item.dayOrder = orders[key, default: 0]
            orders[key, default: 0] += 1
            return item
        }
        let medicationById = Dictionary(uniqueKeysWithValues: value.medications.map { ($0.uuid, $0) })
        value.medicationSchedules = value.medicationSchedules.map { schedule in
            var item = schedule
            if let medication = medicationById[item.medicationUuid] {
                item.effectiveFrom = medication.startDate
                item.effectiveTo = medication.endDate
                item.doseAmountSnapshot = medication.doseAmount
                item.doseUnitSnapshot = medication.doseUnit
            }
            return item
        }
        value.schemaVersion = 4
        try PortableBackupValidator.validate(value)
        return value
    }

    static func toState(_ source: PortableSnapshot, attachmentPaths: [UUID: String]) throws -> HealthState {
        try PortableBackupValidator.validate(source)
        var state = HealthState()
        state.schemaVersion = 4
        state.installationId = source.sourceInstallationId
        state.members = source.members.map { FamilyMember(id: $0.uuid, name: $0.name, relationship: $0.relationship, archived: $0.archived, createdAt: HealthDate.parseInstant($0.createdAt)!, updatedAt: HealthDate.parseInstant($0.updatedAt)!) }
        state.selectedMemberId = state.members.first(where: { !$0.archived })?.id
        state.conditions = source.conditions.map { Condition(id: $0.uuid, memberId: $0.memberUuid, name: $0.name, color: $0.color, notes: $0.notes, archived: $0.archived, createdAt: HealthDate.parseInstant($0.createdAt)!, updatedAt: HealthDate.parseInstant($0.updatedAt ?? $0.createdAt)!) }
        state.records = source.records.map { ClinicalRecord(id: $0.uuid, memberId: $0.memberUuid, conditionId: $0.conditionUuid, recordDate: HealthDate.parseDay($0.recordDate)!, dayOrder: $0.dayOrder, title: $0.title, stage: $0.stage, symptoms: $0.symptoms, diagnosis: $0.diagnosis, treatment: $0.treatment, medicationNotes: $0.medicationNotes, hospital: $0.hospital, clinician: $0.clinician, notes: $0.notes, createdAt: HealthDate.parseInstant($0.createdAt)!, updatedAt: HealthDate.parseInstant($0.updatedAt)!) }
        state.attachments = source.attachments.map { Attachment(id: $0.uuid, recordId: $0.recordUuid, kind: $0.kind, displayName: $0.displayName, mimeType: $0.mimeType, relativePath: attachmentPaths[$0.uuid] ?? "", sizeBytes: $0.sizeBytes, sha256: $0.sha256, createdAt: HealthDate.parseInstant($0.createdAt)!, updatedAt: HealthDate.parseInstant($0.updatedAt ?? $0.createdAt)!) }
        state.followUps = source.followUps.map { item in let time = item.reminderTime.split(separator: ":").compactMap { Int($0) }; return FollowUpSchedule(id: item.uuid, memberId: item.memberUuid, conditionId: item.conditionUuid, title: item.title, recurrenceType: item.recurrenceType, interval: item.interval, anchorDate: HealthDate.parseDay(item.anchorDate)!, anchorDayOfMonth: item.anchorDayOfMonth, weekday: item.weekday, reminderHour: time[0], reminderMinute: time[1], leadDays: item.leadDays, nextDueDate: HealthDate.parseDay(item.nextDueDate)!, enabled: item.enabled, createdAt: HealthDate.parseInstant(item.createdAt)!, updatedAt: HealthDate.parseInstant(item.updatedAt)!) }
        state.occurrences = source.occurrences.map { FollowUpOccurrence(id: $0.uuid, scheduleId: $0.scheduleUuid, dueDate: HealthDate.parseDay($0.dueDate)!, status: $0.status, completedAt: $0.completedAt.flatMap(HealthDate.parseInstant), createdAt: HealthDate.parseInstant($0.createdAt)!, updatedAt: HealthDate.parseInstant($0.updatedAt ?? $0.createdAt)!) }
        state.medications = source.medications.map { Medication(id: $0.uuid, memberId: $0.memberUuid, conditionId: $0.conditionUuid, name: $0.name, doseAmount: $0.doseAmount, doseUnit: $0.doseUnit, instructions: $0.instructions, startDate: HealthDate.parseDay($0.startDate)!, endDate: $0.endDate.flatMap(HealthDate.parseDay), endedAt: $0.endedAt.flatMap(HealthDate.parseInstant) ?? ($0.archived ? HealthDate.parseInstant($0.updatedAt) : nil), archivedPreviousEndDate: $0.archivedPreviousEndDate.flatMap(HealthDate.parseDay), mode: $0.mode, archived: $0.archived, times: [], createdAt: HealthDate.parseInstant($0.createdAt)!, updatedAt: HealthDate.parseInstant($0.updatedAt)!) }
        state.medicationSchedules = source.medicationSchedules.map { MedicationSchedule(id: $0.uuid, medicationId: $0.medicationUuid, localTime: $0.localTime, enabled: $0.enabled, effectiveFrom: $0.effectiveFrom!, effectiveTo: $0.effectiveTo, doseAmountSnapshot: $0.doseAmountSnapshot!, doseUnitSnapshot: $0.doseUnitSnapshot!, updatedAt: HealthDate.parseInstant($0.updatedAt)!, pausedByCourseEnd: $0.pausedByCourseEnd ?? false) }
        state.medicationLogs = source.medicationLogs.map { MedicationLog(id: $0.uuid, medicationId: $0.medicationUuid, scheduleId: $0.scheduleUuid, scheduledAt: HealthDate.parseLocalDateTime($0.scheduledAt)!, actualAt: $0.actualAt.flatMap(HealthDate.parseLocalDateTime), status: $0.status, doseAmountSnapshot: $0.doseAmountSnapshot, doseUnitSnapshot: $0.doseUnitSnapshot, createdAt: HealthDate.parseInstant($0.createdAt)!, updatedAt: HealthDate.parseInstant($0.updatedAt ?? $0.createdAt)!) }
        return state
    }

    private static func recordOrder(_ lhs: ClinicalRecord, _ rhs: ClinicalRecord) -> Bool {
        if lhs.recordDate != rhs.recordDate { return lhs.recordDate < rhs.recordDate }
        if lhs.dayOrder != rhs.dayOrder { return (lhs.dayOrder ?? 0) < (rhs.dayOrder ?? 0) }
        return lhs.id.uuidString < rhs.id.uuidString
    }
}

enum PortableBackupValidator {
    static func validate(_ value: PortableSnapshot) throws {
        guard value.schemaVersion == 4 else { throw StoreValidationError.message("备份必须先转换为 v4") }
        guard value.sourcePlatform == "android" || value.sourcePlatform == "ios" || value.sourcePlatform == "harmony" || value.sourcePlatform == "test" else { throw StoreValidationError.message("备份来源平台异常") }
        guard HealthDate.parseInstant(value.exportedAt) != nil, !value.members.isEmpty, value.members.contains(where: { !$0.archived }) else { throw StoreValidationError.message("备份成员或时间异常") }
        try unique(value.members.map(\.uuid), "家庭成员"); try unique(value.conditions.map(\.uuid), "病情分类")
        try unique(value.records.map(\.uuid), "病历"); try unique(value.attachments.map(\.uuid), "附件")
        try unique(value.followUps.map(\.uuid), "复查计划"); try unique(value.occurrences.map(\.uuid), "复查记录")
        try unique(value.medications.map(\.uuid), "药物"); try unique(value.medicationSchedules.map(\.uuid), "用药计划")
        try unique(value.medicationLogs.map(\.uuid), "用药记录")
        let members = Set(value.members.map(\.uuid))
        for member in value.members {
            guard !member.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, member.name.count <= 50,
                  !member.relationship.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, member.relationship.count <= 30,
                  member.nickname == member.relationship,
                  HealthDate.parseInstant(member.createdAt) != nil, HealthDate.parseInstant(member.updatedAt) != nil else { throw StoreValidationError.message("家庭成员内容异常") }
        }
        for item in value.conditions {
            guard let member = item.memberUuid, members.contains(member), !item.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  item.name.count <= 50, item.notes.count <= 5_000, HealthDate.parseInstant(item.createdAt) != nil,
                  HealthDate.parseInstant(item.updatedAt ?? item.createdAt) != nil else { throw StoreValidationError.message("病情分类引用异常") }
        }
        let conditions = Dictionary(uniqueKeysWithValues: value.conditions.map { ($0.uuid, $0.memberUuid!) })
        var recordDayOrders = Set<String>()
        for item in value.records {
            guard let member = item.memberUuid, members.contains(member), !item.title.isEmpty, item.title.count <= 100,
                  HealthDate.parseDay(item.recordDate) != nil, (item.dayOrder ?? -1) >= 0 else { throw StoreValidationError.message("病历内容或引用异常") }
            guard ["BEFORE_VISIT", "AFTER_VISIT", "CHECKUP", "SURGERY", "OTHER"].contains(item.stage),
                  item.symptoms.count <= 20_000, item.diagnosis.count <= 20_000, item.treatment.count <= 20_000,
                  item.medicationNotes.count <= 20_000, item.notes.count <= 20_000, item.hospital.count <= 200,
                  item.clinician.count <= 100, HealthDate.parseInstant(item.createdAt) != nil,
                  HealthDate.parseInstant(item.updatedAt) != nil,
                  recordDayOrders.insert("\(member.uuidString):\(item.recordDate):\(item.dayOrder ?? 0)").inserted else {
                throw StoreValidationError.message("病历字段、时间或同日顺序异常")
            }
            if let condition = item.conditionUuid {
                guard conditions[condition] == member else { throw StoreValidationError.message("病历与病情分类不属于同一成员") }
            }
        }
        let records = Set(value.records.map(\.uuid))
        let hashPattern = try NSRegularExpression(pattern: "^[0-9a-f]{64}$")
        var attachmentPaths = Set<String>()
        for item in value.attachments {
            let range = NSRange(location: 0, length: item.sha256.utf16.count)
            guard records.contains(item.recordUuid), item.archivePath.lowercased() == "files/\(item.uuid.uuidString.lowercased())",
                  attachmentPaths.insert(item.archivePath.lowercased()).inserted, !item.displayName.isEmpty, item.displayName.count <= 200,
                  item.sizeBytes >= 0, item.sizeBytes <= AttachmentFiles.maxBytes, HealthDate.parseInstant(item.createdAt) != nil,
                  HealthDate.parseInstant(item.updatedAt ?? item.createdAt) != nil,
                  hashPattern.firstMatch(in: item.sha256, range: range) != nil else { throw StoreValidationError.message("附件清单异常") }
        }
        for item in value.followUps {
            guard let member = item.memberUuid, members.contains(member), HealthDate.parseDay(item.anchorDate) != nil,
                  item.title.count <= 100, !item.title.isEmpty, ["ONCE", "EVERY_N_DAYS", "EVERY_N_WEEKS", "EVERY_N_MONTHS"].contains(item.recurrenceType),
                  (1...10_000).contains(item.interval), (1...31).contains(item.anchorDayOfMonth), item.weekday == nil || (1...7).contains(item.weekday!),
                  HealthDate.parseDay(item.nextDueDate) != nil, (0...365).contains(item.leadDays), validTime(item.reminderTime),
                  HealthDate.parseInstant(item.createdAt) != nil, HealthDate.parseInstant(item.updatedAt) != nil else { throw StoreValidationError.message("复查计划异常") }
            if let condition = item.conditionUuid { guard conditions[condition] == member else { throw StoreValidationError.message("复查与病情分类不属于同一成员") } }
        }
        let followUps = Set(value.followUps.map(\.uuid))
        var occurrenceKeys = Set<String>()
        for item in value.occurrences {
            guard followUps.contains(item.scheduleUuid), HealthDate.parseDay(item.dueDate) != nil,
                  ["PENDING", "DONE", "SKIPPED"].contains(item.status),
                  item.completedAt == nil || HealthDate.parseInstant(item.completedAt!) != nil,
                  HealthDate.parseInstant(item.createdAt) != nil, HealthDate.parseInstant(item.updatedAt ?? item.createdAt) != nil,
                  occurrenceKeys.insert("\(item.scheduleUuid):\(item.dueDate)").inserted else { throw StoreValidationError.message("复查记录异常或重复") }
        }
        for item in value.medications {
            guard let member = item.memberUuid, members.contains(member), !item.name.isEmpty, item.name.count <= 100,
                  !item.doseAmount.isEmpty, item.doseAmount.count <= 30, !item.doseUnit.isEmpty, item.doseUnit.count <= 20,
                  item.instructions.count <= 5_000, ["SCHEDULED", "AS_NEEDED"].contains(item.mode),
                  let start = HealthDate.parseDay(item.startDate), item.endDate == nil || (HealthDate.parseDay(item.endDate!) ?? .distantPast) >= start,
                  HealthDate.parseInstant(item.createdAt) != nil, HealthDate.parseInstant(item.updatedAt) != nil else { throw StoreValidationError.message("药物内容异常") }
            if let condition = item.conditionUuid { guard conditions[condition] == member else { throw StoreValidationError.message("药物与病情分类不属于同一成员") } }
        }
        let medications = Dictionary(uniqueKeysWithValues: value.medications.map { ($0.uuid, $0) })
        for item in value.medicationSchedules {
            guard let medicine = medications[item.medicationUuid], let effectiveFrom = item.effectiveFrom,
                  HealthDate.parseDay(effectiveFrom) != nil, validTime(item.localTime), !(item.doseAmountSnapshot ?? "").isEmpty,
                  (item.doseAmountSnapshot ?? "").count <= 30, !(item.doseUnitSnapshot ?? "").isEmpty,
                  (item.doseUnitSnapshot ?? "").count <= 20, HealthDate.parseInstant(item.updatedAt) != nil else { throw StoreValidationError.message("用药计划异常") }
            if let end = item.effectiveTo { guard let endDate = HealthDate.parseDay(end), let startDate = HealthDate.parseDay(effectiveFrom), endDate >= startDate else { throw StoreValidationError.message("用药计划有效期异常") } }
            guard effectiveFrom >= medicine.startDate else { throw StoreValidationError.message("用药计划早于疗程") }
        }
        let schedules = Dictionary(uniqueKeysWithValues: value.medicationSchedules.map { ($0.uuid, $0.medicationUuid) })
        var logKeys = Set<String>()
        for item in value.medicationLogs {
            guard medications[item.medicationUuid] != nil, item.scheduleUuid == nil || schedules[item.scheduleUuid!] == item.medicationUuid,
                  HealthDate.parseLocalDateTime(item.scheduledAt) != nil, item.actualAt == nil || HealthDate.parseLocalDateTime(item.actualAt!) != nil,
                  item.status == "TAKEN" || item.status == "SKIPPED", item.doseAmountSnapshot.count <= 30,
                  item.doseUnitSnapshot.count <= 20, HealthDate.parseInstant(item.createdAt) != nil,
                  HealthDate.parseInstant(item.updatedAt ?? item.createdAt) != nil,
                  logKeys.insert("\(item.medicationUuid):\(item.scheduledAt)").inserted else { throw StoreValidationError.message("用药记录异常或重复") }
        }
    }

    private static func unique(_ ids: [UUID], _ label: String) throws { if Set(ids).count != ids.count { throw StoreValidationError.message("\(label) UUID 重复") } }
    private static func validTime(_ value: String) -> Bool { value.range(of: #"^(?:[01]\d|2[0-3]):[0-5]\d$"#, options: .regularExpression) != nil }
}
